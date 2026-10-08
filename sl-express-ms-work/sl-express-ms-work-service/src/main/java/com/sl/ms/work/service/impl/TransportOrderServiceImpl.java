package com.sl.ms.work.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sl.ms.base.api.common.MQFeign;
import com.sl.ms.oms.api.OrderFeign;
import com.sl.ms.oms.dto.OrderCargoDTO;
import com.sl.ms.oms.dto.OrderDetailDTO;
import com.sl.ms.oms.dto.OrderLocationDTO;
import com.sl.ms.transport.api.TransportLineFeign;
import com.sl.ms.work.domain.dto.TransportOrderDTO;
import com.sl.ms.work.domain.dto.request.TransportOrderQueryDTO;
import com.sl.ms.work.domain.dto.response.TransportOrderStatusCountDTO;
import com.sl.ms.work.domain.enums.WorkExceptionEnum;
import com.sl.ms.work.domain.enums.pickupDispatchtask.PickupDispatchTaskType;
import com.sl.ms.work.domain.enums.transportorder.TransportOrderSchedulingStatus;
import com.sl.ms.work.domain.enums.transportorder.TransportOrderStatus;
import com.sl.ms.work.entity.TransportOrderEntity;
import com.sl.ms.work.mapper.TransportOrderMapper;
import com.sl.ms.work.service.TransportOrderService;
import com.sl.transport.common.constant.Constants;
import com.sl.transport.common.enums.IdEnum;
import com.sl.transport.common.exception.SLException;
import com.sl.transport.common.service.IdService;
import com.sl.transport.common.util.PageResponse;
import com.sl.transport.common.vo.OrderMsg;
import com.sl.transport.common.vo.TransportOrderMsg;
import com.sl.transport.common.vo.TransportOrderStatusMsg;
import com.sl.transport.domain.TransportLineNodeDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransportOrderServiceImpl extends ServiceImpl<TransportOrderMapper, TransportOrderEntity> implements TransportOrderService {
    private final IdService idService;
    private final OrderFeign orderFeign;
    private final TransportLineFeign transportLineFeign;
    private final MQFeign mqFeign;
    private final TransportOrderMapper transportOrderMapper;

    /**
     * 订单转运单
     *
     * @param orderId 订单号
     * @return 运单号
     */
    @Override
    public TransportOrderEntity orderToTransportOrder(Long orderId) {
        //1.进行幂等性校验
        //运单是否存在
        TransportOrderEntity entity = this.findByOrderId(orderId);
        if (ObjectUtil.isNotEmpty(entity)) {
            return entity;
        }
        //订单是否存在
        OrderDetailDTO detailByOrderId = orderFeign.findDetailByOrderId(orderId);
        if (ObjectUtil.isEmpty(detailByOrderId)) {
            //订单不存在
            throw new SLException(WorkExceptionEnum.ORDER_NOT_FOUND);
        }
        //查询重量，体积数据
        OrderCargoDTO orderCargoDto = detailByOrderId.getOrderDTO().getOrderCargoDto();
        if (ObjectUtil.isEmpty(orderCargoDto)) {
            //订单货物信息不存在
            throw new SLException(WorkExceptionEnum.ORDER_CARGO_NOT_FOUND);
        }
        BigDecimal weight = orderCargoDto.getWeight();
        BigDecimal volume = orderCargoDto.getVolume();
        if(ObjectUtil.hasEmpty(weight, volume)){
            throw new SLException(WorkExceptionEnum.ORDER_CARGO_NOT_FOUND);
        }

        //查询收发件人位置
        //位置：先判对象，再取字段，取完立即判字段
        OrderLocationDTO orderLocationDTO = detailByOrderId.getOrderLocationDTO();
        if (ObjectUtil.isEmpty(orderLocationDTO)) {
            //订单位置信息不存在
            throw new SLException(WorkExceptionEnum.ORDER_LOCATION_NOT_FOUND);
        }
        Long startAgentId = Convert.toLong(orderLocationDTO.getSendAgentId());
        Long endAgentId = Convert.toLong(orderLocationDTO.getReceiveAgentId());
        if (ObjectUtil.hasEmpty(startAgentId, endAgentId)) {
            throw new SLException(WorkExceptionEnum.ORDER_LOCATION_NOT_FOUND);
        }

        //2.判断是否需要进行路线规划
        TransportLineNodeDTO transportLineNodeDTO = null;
        boolean isSameAgent = true;
        if (ObjectUtil.notEqual(startAgentId, endAgentId)) {
            isSameAgent = false;
            //路线规划
            transportLineNodeDTO = transportLineFeign.queryPathByDispatchMethod(startAgentId, endAgentId);
            if (ObjectUtil.isEmpty(transportLineNodeDTO) || CollUtil.isEmpty(transportLineNodeDTO.getNodeList())) {
                throw new SLException(WorkExceptionEnum.TRANSPORT_LINE_NOT_FOUND);
            }
        }

        //3.构建运单对象，设置各种属性值
        String id = idService.getId(IdEnum.TRANSPORT_ORDER);
        TransportOrderEntity transportOrderEntity = TransportOrderEntity.builder()
                .id(id)
                .orderId(orderId)
                .startAgencyId(startAgentId)
                .endAgencyId(endAgentId)
                .totalWeight(weight)
                .totalVolume(volume)
                .currentAgencyId(startAgentId)
                .isRejection(false)
                .build();

        if (isSameAgent) {
            //同一网点的任务
            transportOrderEntity.setStatus(TransportOrderStatus.ARRIVED_END);
            transportOrderEntity.setNextAgencyId(startAgentId);
            transportOrderEntity.setSchedulingStatus(TransportOrderSchedulingStatus.SCHEDULED);
            transportOrderEntity.setTransportLine(null);
        } else {
            //调度中心
            transportOrderEntity.setStatus(TransportOrderStatus.CREATED);
            transportOrderEntity.setNextAgencyId(transportLineNodeDTO.getNodeList().get(1).getId());
            transportOrderEntity.setSchedulingStatus(TransportOrderSchedulingStatus.TO_BE_SCHEDULED);
            transportOrderEntity.setTransportLine(JSONUtil.toJsonStr(transportLineNodeDTO));
        }

        //4.保存数据到数据库
        boolean result = this.save(transportOrderEntity);
        if (!result) {
            throw new SLException(WorkExceptionEnum.TRANSPORT_ORDER_SAVE_ERROR);
        }
        if (isSameAgent) {
            //不需要调度 发送消息更新订单状态
            this.sendUpdateStatusMsg(ListUtil.toList(transportOrderEntity.getId()), TransportOrderStatus.ARRIVED_END);
            //不需要调度，发送消息生成派件任务
            this.sendDispatchTaskMsgToDispatch(transportOrderEntity);
        } else {
            //发送消息到调度中心，进行调度
            this.sendTransportOrderMsgToDispatch(transportOrderEntity);
        }

        //发消息通知其他系统，运单已经生成
        String msg = TransportOrderMsg.builder()
                .id(transportOrderEntity.getId())
                .orderId(transportOrderEntity.getOrderId())
                .created(DateUtil.current())
                .build().toJson();
        this.mqFeign.sendMsg(Constants.MQ.Exchanges.TRANSPORT_ORDER_DELAYED,
                Constants.MQ.RoutingKeys.TRANSPORT_ORDER_CREATE, msg, Constants.MQ.NORMAL_DELAY);

        return transportOrderEntity;
    }


    //发送消息生成派件任务
    private void sendDispatchTaskMsgToDispatch(TransportOrderEntity transportOrderEntity) {
        //预计完成时间，如果是中午12点到的快递，当天22点前，否则，第二天22点前
        int offset = 0;
        if (LocalDateTime.now().getHour() >= 12) {
            offset = 1;
        }
        LocalDateTime estimatedEndTime = DateUtil.offsetDay(new Date(), offset)
                .setField(DateField.HOUR_OF_DAY, 22)
                .setField(DateField.MINUTE, 0)
                .setField(DateField.SECOND, 0)
                .setField(DateField.MILLISECOND, 0).toLocalDateTime();

        //发送分配快递员派件任务的消息
        OrderMsg orderMsg = OrderMsg.builder()
                .agencyId(transportOrderEntity.getCurrentAgencyId())
                .orderId(transportOrderEntity.getOrderId())
                .created(DateUtil.current())
                .taskType(PickupDispatchTaskType.DISPATCH.getCode()) //派件任务
                .mark("系统提示：派件前请于收件人电话联系.")
                .estimatedEndTime(estimatedEndTime).build();

        //发送消息
        this.sendPickupDispatchTaskMsgToDispatch(transportOrderEntity, orderMsg);
    }

    //发送消息更新订单状态
    private void sendUpdateStatusMsg(List<String> list, TransportOrderStatus transportOrderStatus) {
        String msg = TransportOrderStatusMsg.builder()
                .idList(list)
                .statusName(transportOrderStatus.name())
                .statusCode(transportOrderStatus.getCode())
                .build().toJson();

        //将状态名称写入到路由key中，方便消费方选择性的接收消息
        String routingKey = Constants.MQ.RoutingKeys.TRANSPORT_ORDER_UPDATE_STATUS_PREFIX + transportOrderStatus.name();
        this.mqFeign.sendMsg(Constants.MQ.Exchanges.TRANSPORT_ORDER_DELAYED, routingKey, msg, Constants.MQ.LOW_DELAY);
    }

    //发送消息到调度中心，进行调度
    private void sendTransportOrderMsgToDispatch(TransportOrderEntity transportOrderEntity) {
        Map<String, Object> msg = MapUtil.<String, Object>builder()
                .put("transportOrderId", transportOrderEntity.getId())
                .put("currentAgencyId", transportOrderEntity.getCurrentAgencyId())
                .put("nextAgencyId", transportOrderEntity.getNextAgencyId())
                .put("totalWeight", transportOrderEntity.getTotalWeight())
                .put("totalVolume", transportOrderEntity.getTotalVolume())
                .put("created", System.currentTimeMillis()).build();
        String jsonMsg = JSONUtil.toJsonStr(msg);
        //发送消息，延迟5秒，确保本地事务已经提交，可以查询到数据
        this.mqFeign.sendMsg(Constants.MQ.Exchanges.TRANSPORT_ORDER_DELAYED,
                Constants.MQ.RoutingKeys.JOIN_DISPATCH, jsonMsg, Constants.MQ.LOW_DELAY);
    }

    /**
     * 发送消息到调度中心，用于生成取派件任务
     *
     * @param transportOrder 运单对象
     * @param orderMsg       消息对象
     */
    @Override
    public void sendPickupDispatchTaskMsgToDispatch(TransportOrderEntity transportOrder, OrderMsg orderMsg) {
        //查询订单对应的位置信息
        OrderLocationDTO orderLocationDTO = this.orderFeign.findOrderLocationByOrderId(orderMsg.getOrderId());

        //(1)运单为空：取件任务取消，取消原因为返回网点；重新调度位置取寄件人位置
        //(2)运单不为空：生成的是派件任务，需要根据拒收状态判断位置是寄件人还是收件人
        // 拒收：寄件人  其他：收件人
        String location;
        if (ObjectUtil.isEmpty(transportOrder)) {
            location = orderLocationDTO.getSendLocation();
        } else {
            location = transportOrder.getIsRejection() ? orderLocationDTO.getSendLocation() : orderLocationDTO.getReceiveLocation();
        }

        Double[] coordinate = Convert.convert(Double[].class, StrUtil.split(location, ","));
        Double longitude = coordinate[0];
        Double latitude = coordinate[1];

        //设置消息中的位置信息
        orderMsg.setLongitude(longitude);
        orderMsg.setLatitude(latitude);

        //发送消息,用于生成取派件任务
        this.mqFeign.sendMsg(Constants.MQ.Exchanges.ORDER_DELAYED, Constants.MQ.RoutingKeys.ORDER_CREATE,
                orderMsg.toJson(), Constants.MQ.NORMAL_DELAY);
    }

    /**
     * 获取运单分页数据
     *
     * @return 运单分页数据
     */
    @Override
    public Page<TransportOrderEntity> findByPage(TransportOrderQueryDTO transportOrderQueryDTO) {
        //分页对象
        Page<TransportOrderEntity> page = new Page<>(transportOrderQueryDTO.getPage(), transportOrderQueryDTO.getPageSize());

        return this.lambdaQuery()
                //运单id
                .like(StrUtil.isNotEmpty(transportOrderQueryDTO.getId()), TransportOrderEntity::getId, transportOrderQueryDTO.getId())
                //订单id
                .eq(ObjectUtil.isNotEmpty(transportOrderQueryDTO.getOrderId()), TransportOrderEntity::getOrderId, transportOrderQueryDTO.getOrderId())
                //运单状态
                .eq(ObjectUtil.isNotEmpty(transportOrderQueryDTO.getStatus()), TransportOrderEntity::getStatus, transportOrderQueryDTO.getStatus())
                //调度状态
                .eq(ObjectUtil.isNotEmpty(transportOrderQueryDTO.getSchedulingStatus()), TransportOrderEntity::getSchedulingStatus, transportOrderQueryDTO.getSchedulingStatus())
                //起始网点id
                .eq(ObjectUtil.isNotEmpty(transportOrderQueryDTO.getStartAgencyId()), TransportOrderEntity::getStartAgencyId, transportOrderQueryDTO.getStartAgencyId())
                //终点网点id
                .eq(ObjectUtil.isNotEmpty(transportOrderQueryDTO.getEndAgencyId()), TransportOrderEntity::getEndAgencyId, transportOrderQueryDTO.getEndAgencyId())
                //当前所在机构id
                .eq(ObjectUtil.isNotEmpty(transportOrderQueryDTO.getCurrentAgencyId()), TransportOrderEntity::getCurrentAgencyId, transportOrderQueryDTO.getCurrentAgencyId())
                //按照创建时间倒序排序
                .orderByDesc(TransportOrderEntity::getCreated)
                .page(page);
    }

    /**
     * 通过订单id获取运单信息
     *
     * @param orderId 订单id
     * @return 运单信息
     */
    @Override
    public TransportOrderEntity findByOrderId(Long orderId) {
        return CollUtil.getFirst(this.findByOrderIds(new Long[] {orderId}));
    }

    /**
     * 通过订单id列表获取运单列表
     *
     * @param orderIds 订单id列表
     * @return 运单列表
     */
    @Override
    public List<TransportOrderEntity> findByOrderIds(Long[] orderIds) {
        return this.lambdaQuery()
                .in(TransportOrderEntity::getOrderId, orderIds)
                .list();
    }

    /**
     * 通过运单id列表获取运单列表
     *
     * @param ids 订单id列表
     * @return 运单列表
     */
    @Override
    public List<TransportOrderEntity> findByIds(String[] ids) {
        return this.lambdaQuery()
                .in(TransportOrderEntity::getId, ids)
                .list();
    }

    /**
     * 根据运单号搜索运单
     *
     * @param id 运单号
     * @return 运单列表
     */
    @Override
    public List<TransportOrderEntity> searchById(String id) {
        return this.findByIds(new String[]{id});
    }

    /**
     * 修改运单状态
     *
     * @param ids                  运单id列表
     * @param transportOrderStatus 修改的状态
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(List<String> ids, TransportOrderStatus transportOrderStatus) {
        //1.参数校验
        if(ObjectUtil.hasEmpty(ids, transportOrderStatus)) {
            return false;
        }
        //修改订单状态不应该修改为新建状态
        if(ObjectUtil.equal(transportOrderStatus,TransportOrderStatus.CREATED)){
            throw new SLException(WorkExceptionEnum.TRANSPORT_ORDER_STATUS_NOT_CREATED);
        }

        //2.根据不同的状态进行处理
        List<TransportOrderEntity> transportOrderEntities;
        if(ObjectUtil.equal(transportOrderStatus,TransportOrderStatus.REJECTED)){
            //拒收需要重新查询路线，将包裹逆向回去
            //查询出运单列表一个一个处理，将起点终点的网点进行对调，重新进行路线规划
            transportOrderEntities = this.listByIds(ids);
            for (TransportOrderEntity transportOrderEntity : transportOrderEntities) {
                //设置为拒收运单
                transportOrderEntity.setStatus(TransportOrderStatus.REJECTED);
                transportOrderEntity.setIsRejection(true);
                //根据起始机构规划运输路线，这里要将起点和终点互换
                Long sendAgentId = transportOrderEntity.getEndAgencyId();//起始网点id
                Long receiveAgentId = transportOrderEntity.getStartAgencyId();//终点网点id

                //默认参与调度
                boolean isDispatch = true;
                if (ObjectUtil.equal(sendAgentId, receiveAgentId)) {
                    //相同节点，无需调度，直接生成派件任务
                    isDispatch = false;
                } else {
                    TransportLineNodeDTO transportLineNodeDTO = this.transportLineFeign.queryPathByDispatchMethod(sendAgentId, receiveAgentId);
                    if (ObjectUtil.hasEmpty(transportLineNodeDTO, transportLineNodeDTO.getNodeList())) {
                        throw new SLException(WorkExceptionEnum.TRANSPORT_LINE_NOT_FOUND);
                    }
                    //删除掉第一个机构，逆向回去的第一个节点就是当前所在节点
                    transportLineNodeDTO.getNodeList().remove(0);
                    transportOrderEntity.setSchedulingStatus(TransportOrderSchedulingStatus.TO_BE_SCHEDULED);//调度状态：待调度
                    transportOrderEntity.setCurrentAgencyId(sendAgentId);//当前所在机构id
                    transportOrderEntity.setNextAgencyId(transportLineNodeDTO.getNodeList().get(0).getId());//下一个机构id

                    //获取到原有节点信息
                    TransportLineNodeDTO transportLineNode = JSONUtil.toBean(transportOrderEntity.getTransportLine(), TransportLineNodeDTO.class);
                    //将逆向节点追加到节点列表中
                    transportLineNode.getNodeList().addAll(transportLineNodeDTO.getNodeList());
                    //合并成本
                    transportLineNode.setCost(NumberUtil.add(transportLineNode.getCost(), transportLineNodeDTO.getCost()));
                    transportOrderEntity.setTransportLine(JSONUtil.toJsonStr(transportLineNode));//完整的运输路线
                }
                if (isDispatch) {
                    //发送消息参与调度
                    this.sendTransportOrderMsgToDispatch(transportOrderEntity);
                } else {
                    //不需要调度，发送消息生成派件任务
                    transportOrderEntity.setStatus(TransportOrderStatus.ARRIVED_END);
                    this.sendDispatchTaskMsgToDispatch(transportOrderEntity);
                }
            }
        }else{
            //非拒收，直接修改状态就行
            transportOrderEntities=ids.stream()
                    .map(id-> {
                        //TODO 发送运单跟踪消息

                        return TransportOrderEntity.builder()
                                .id(id)
                                .status(transportOrderStatus)
                                .build();
                    })
                    .collect(Collectors.toList());
        }
        //3.更新数据库
        boolean result = this.updateBatchById(transportOrderEntities);
        if(!result){
            throw new SLException("修改运单状态失败,请重新尝试");
        }
        //发消息通知其他系统运单状态的变化
        this.sendUpdateStatusMsg(ids, transportOrderStatus);
        return true;
    }

    /**
     * 根据运输任务id批量修改运单，其中会涉及到下一个节点的流转，已经发送消息的业务
     *
     * @param taskId 运输任务id
     * @return 是否成功
     */
    @Override
    public boolean updateByTaskId(Long taskId) {
        return false;
    }

    /**
     * 统计各个状态的数量
     *
     * @return 状态数量数据
     */
    @Override
    public List<TransportOrderStatusCountDTO> findStatusCount() {
        Map<Integer, Long> countMap = transportOrderMapper.findStatusCount().stream()
                .collect(Collectors.toMap(TransportOrderStatusCountDTO::getStatusCode, TransportOrderStatusCountDTO::getCount));
        return StreamUtil.of(TransportOrderStatus.values())
                .map(transportOrderStatus -> TransportOrderStatusCountDTO.builder()
                            .status(transportOrderStatus)
                            .statusCode(transportOrderStatus.getCode())
                            .count(countMap.getOrDefault(transportOrderStatus.getCode(), 0L))
                            .build()
                )
                .collect(Collectors.toList());
    }


    /**
     * 根据运输任务id分页查询运单信息
     *
     * @param page             页码
     * @param pageSize         页面大小
     * @param taskId           运输任务id
     * @param transportOrderId 运单id
     * @return 运单对象分页数据
     */
    @Override
    public PageResponse<TransportOrderDTO> pageQueryByTaskId(Integer page, Integer pageSize, String taskId, String transportOrderId) {
        return null;
    }
}
