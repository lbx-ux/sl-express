package com.sl.ms.work.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sl.ms.oms.api.OrderFeign;
import com.sl.ms.oms.enums.OrderStatus;
import com.sl.ms.work.domain.dto.CourierTaskCountDTO;
import com.sl.ms.work.domain.dto.PickupDispatchTaskDTO;
import com.sl.ms.work.domain.dto.request.PickupDispatchTaskPageQueryDTO;
import com.sl.ms.work.domain.dto.response.PickupDispatchTaskStatisticsDTO;
import com.sl.ms.work.domain.enums.WorkExceptionEnum;
import com.sl.ms.work.domain.enums.pickupDispatchtask.*;
import com.sl.ms.work.entity.PickupDispatchTaskEntity;
import com.sl.ms.work.mapper.TaskPickupDispatchMapper;
import com.sl.ms.work.service.PickupDispatchTaskService;
import com.sl.ms.work.service.TransportOrderService;
import com.sl.transport.common.exception.SLException;
import com.sl.transport.common.util.PageResponse;
import com.sl.transport.common.vo.OrderMsg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PickupDispatchTaskServiceImpl extends ServiceImpl<TaskPickupDispatchMapper, PickupDispatchTaskEntity> implements PickupDispatchTaskService {
    private final TransportOrderService transportOrderService;
    private final OrderFeign orderFeign;
    private final TaskPickupDispatchMapper taskPickupDispatchMapper;

    //更新取派件任务状态
    @Override
    public Boolean updateStatus(PickupDispatchTaskDTO pickupDispatchTaskDTO) {
        //参数校验
        PickupDispatchTaskStatus status = pickupDispatchTaskDTO.getStatus();
        if (ObjectUtil.hasEmpty(pickupDispatchTaskDTO.getId(), status)) {
            throw new SLException("更新取派件任务状态，id或status不能为空");
        }
        //根据id查询任务数据
        PickupDispatchTaskEntity entity = this.getById(pickupDispatchTaskDTO.getId());
        if(ObjectUtil.isEmpty(entity)){
            throw new SLException(WorkExceptionEnum.PICKUP_DISPATCH_TASK_NOT_FOUND);
        }
        //根据不同的状态进行不同业务的处理
        switch (status) {
            case NEW :{
                throw new SLException(WorkExceptionEnum.PICKUP_DISPATCH_TASK_STATUS_NOT_NEW);
            }
            case COMPLETED:{
                //完成状态
                entity.setStatus(PickupDispatchTaskStatus.COMPLETED);
                entity.setActualEndTime(LocalDateTime.now());
                //如果是派件任务，必须设置签收状态和签收人
                if(ObjectUtil.equal(pickupDispatchTaskDTO.getTaskType(),PickupDispatchTaskType.DISPATCH)){
                    if(ObjectUtil.isEmpty(pickupDispatchTaskDTO.getSignStatus())){
                        throw new SLException("完成派件任务，签收状态不能为空");
                    }
                    //设置签收状态
                    entity.setSignStatus(pickupDispatchTaskDTO.getSignStatus());
                    if(ObjectUtil.equal(pickupDispatchTaskDTO.getSignStatus(), PickupDispatchTaskSignStatus.RECEIVED)){
                        if(ObjectUtil.isEmpty(pickupDispatchTaskDTO.getSignRecipient())){
                            throw new SLException("完成派件任务，签收人不能为空");
                        }
                        //设置签收人
                        entity.setSignRecipient(pickupDispatchTaskDTO.getSignRecipient());
                    }
                }
                break;
            }
            case CANCELLED:{
                //取消状态
                if(ObjectUtil.isEmpty(pickupDispatchTaskDTO.getCancelReason())){
                    throw new SLException("取消任务，原因不能为空");
                }
                entity.setStatus(PickupDispatchTaskStatus.CANCELLED);
                entity.setCancelReason(pickupDispatchTaskDTO.getCancelReason());
                entity.setCancelReasonDescription(pickupDispatchTaskDTO.getCancelReasonDescription());
                entity.setCancelTime(LocalDateTime.now());

                if(ObjectUtil.equal(PickupDispatchTaskCancelReason.RETURN_TO_AGENCY,pickupDispatchTaskDTO.getCancelReason())){
                    //重新调度，向调度中心发送新订单的消息
                    OrderMsg orderMsg = OrderMsg.builder()
                            .agencyId(entity.getAgencyId())
                            .orderId(entity.getOrderId())
                            .created(DateUtil.current())
                            .taskType(PickupDispatchTaskType.PICKUP.getCode()) //取件任务
                            .mark(entity.getMark())
                            .estimatedEndTime(entity.getEstimatedEndTime()).build();
                    //发送消息（取消任务发生在取件之前，没有运单，参数直接填入null）
                    //TODO 目前还没有实现，暂时先注释掉
                    // this.transportOrderService.sendPickupDispatchTaskMsgToDispatch(null, orderMsg);

                }else if(pickupDispatchTaskDTO.getCancelReason() == PickupDispatchTaskCancelReason.CANCEL_BY_USER){
                    //原因是用户取消，则订单状态改为取消
                    orderFeign.updateStatus(ListUtil.toList(entity.getOrderId()), OrderStatus.CANCELLED.getCode());
                }else {
                    //其他原因则关闭订单
                    orderFeign.updateStatus(ListUtil.toList(entity.getOrderId()), OrderStatus.CLOSE.getCode());
                }
                break;
            }
            default:{
                throw new SLException("其他未知状态，不能完成更新操作");
            }
        }

        boolean result = this.updateById(entity);
        if(result){
            //TODO 同步到es
            return true;
        }
        throw new SLException("更新操作失败，请重试");
    }

    //批量改派快递员
    @Override
    public Boolean updateCourierId(List<Long> ids, Long originalCourierId, Long targetCourierId) {
        //校验传入的三个参数是否为空
        if(ObjectUtil.hasEmpty(ids, originalCourierId, targetCourierId)) {
            throw new SLException(WorkExceptionEnum.UPDATE_COURIER_PARAM_ERROR);
        }
        //校验原快递员id和目标快递员id是否一致
        if(ObjectUtil.equals(originalCourierId, targetCourierId)) {
            throw new SLException(WorkExceptionEnum.UPDATE_COURIER_EQUAL_PARAM_ERROR);
        }
        //校验要转单的ids的原快递员id和提供的快递员id是否一致,快递员id为空也可以保留
        List<PickupDispatchTaskEntity> pickupDispatchTaskEntities = this.listByIds(ids);
        List<PickupDispatchTaskEntity> list = pickupDispatchTaskEntities.stream()
                .filter(pickupDispatchTaskEntity -> ObjectUtil.isNotEmpty(pickupDispatchTaskEntity.getCourierId()))
                .filter(entity -> ObjectUtil.notEqual(entity.getCourierId(), originalCourierId))
                .collect(Collectors.toList());
        if(CollUtil.isNotEmpty(list)) {
            throw new SLException(WorkExceptionEnum.UPDATE_COURIER_ID_PARAM_ERROR);
        }

        //获取取件任务的id
        List<Long> taskIds = pickupDispatchTaskEntities.stream()
                .map(PickupDispatchTaskEntity::getId)
                .collect(Collectors.toList());

        boolean result = this.lambdaUpdate()
                .in(PickupDispatchTaskEntity::getId, taskIds)
                .set(PickupDispatchTaskEntity::getCourierId, targetCourierId)
                .set(PickupDispatchTaskEntity::getAssignedStatus, PickupDispatchTaskAssignedStatus.DISTRIBUTED)
                .update();
        if(result) {
            //TODO 更新同步到es

        }
        return result;
    }

    @Override
    public PickupDispatchTaskEntity saveTaskPickupDispatch(PickupDispatchTaskEntity taskPickupDispatch) {
        //设置任务状态为NEW
        taskPickupDispatch.setStatus(PickupDispatchTaskStatus.NEW);
        boolean result = this.save(taskPickupDispatch);
        if(result){
            //TODO 同步快递员任务到es

            //TODO 生成运单跟踪消息和快递员端取件/派件消息通知

            return taskPickupDispatch;
        }
        throw new SLException(WorkExceptionEnum.PICKUP_DISPATCH_TASK_SAVE_ERROR);
    }

    //分页查询
    @Override
    public PageResponse<PickupDispatchTaskDTO> findByPage(PickupDispatchTaskPageQueryDTO dto) {
        //构造条件
        Page<PickupDispatchTaskEntity> iPage = new Page<>(dto.getPage(), dto.getPageSize());
        LambdaQueryChainWrapper<PickupDispatchTaskEntity> queryWrapper = this.lambdaQuery()
                .like(ObjectUtil.isNotEmpty(dto.getId()), PickupDispatchTaskEntity::getId, dto.getId())
                .like(ObjectUtil.isNotEmpty(dto.getOrderId()), PickupDispatchTaskEntity::getOrderId, dto.getOrderId())
                .eq(ObjectUtil.isNotEmpty(dto.getAgencyId()), PickupDispatchTaskEntity::getAgencyId, dto.getAgencyId())
                .eq(ObjectUtil.isNotEmpty(dto.getCourierId()), PickupDispatchTaskEntity::getCourierId, dto.getCourierId())
                .eq(ObjectUtil.isNotEmpty(dto.getTaskType()), PickupDispatchTaskEntity::getTaskType, dto.getTaskType())
                .eq(ObjectUtil.isNotEmpty(dto.getStatus()), PickupDispatchTaskEntity::getStatus, dto.getStatus())
                .eq(ObjectUtil.isNotEmpty(dto.getAssignedStatus()), PickupDispatchTaskEntity::getAssignedStatus, dto.getAssignedStatus())
                .eq(ObjectUtil.isNotEmpty(dto.getSignStatus()), PickupDispatchTaskEntity::getSignStatus, dto.getSignStatus())
                .eq(ObjectUtil.isNotEmpty(dto.getIsDeleted()), PickupDispatchTaskEntity::getIsDeleted, dto.getIsDeleted())
                .between(ObjectUtil.isNotEmpty(dto.getMinEstimatedEndTime()), PickupDispatchTaskEntity::getEstimatedEndTime, dto.getMinEstimatedEndTime(), dto.getMaxEstimatedEndTime())
                .between(ObjectUtil.isNotEmpty(dto.getMinActualEndTime()), PickupDispatchTaskEntity::getActualEndTime, dto.getMinActualEndTime(), dto.getMaxActualEndTime())
                .orderByDesc(PickupDispatchTaskEntity::getUpdated);

        //分页查询
        Page<PickupDispatchTaskEntity> result = queryWrapper.page(iPage);

        //实体类转为dto
        return PageResponse.of(result, PickupDispatchTaskDTO.class);
    }

    /**
     * 按照当日快递员id列表查询每个快递员的取派件任务数
     *
     * @param courierIds             快递员id列表
     * @param pickupDispatchTaskType 任务类型
     * @param date                   日期，格式：yyyy-MM-dd 或 yyyyMMdd
     * @return 任务数
     */
    @Override
    public List<CourierTaskCountDTO> findCountByCourierIds(List<Long> courierIds, PickupDispatchTaskType pickupDispatchTaskType, String date) {
        //计算一天的时间的边界
        DateTime dateTime = DateUtil.parse(date);
        LocalDateTime startDateTime = DateUtil.beginOfDay(dateTime).toLocalDateTime();
        LocalDateTime endDateTime = DateUtil.endOfDay(dateTime).toLocalDateTime();
        return taskPickupDispatchMapper
                .findCountByCourierIds(courierIds, pickupDispatchTaskType.getCode(), startDateTime, endDateTime);
    }

    @Override
    public List<PickupDispatchTaskEntity> findByOrderId(Long orderId, PickupDispatchTaskType taskType) {
        return this.lambdaQuery()
                .eq(PickupDispatchTaskEntity::getOrderId, orderId)
                .eq(PickupDispatchTaskEntity::getTaskType, taskType)
                .orderByAsc(PickupDispatchTaskEntity::getCreated)
                .list();
    }

    @Override
    public boolean deleteByIds(List<Long> ids) {
        //逻辑删除：将is_deleted设置为1，同时将updated刷新
        if (CollUtil.isEmpty(ids)) {
            return false;
        }
        return this.lambdaUpdate()
                .in(PickupDispatchTaskEntity::getId, ids)
                .set(PickupDispatchTaskEntity::getIsDeleted, PickupDispatchTaskIsDeleted.IS_DELETED)
                .update();
    }

    @Override
    public Integer todayTasksCount(Long courierId, PickupDispatchTaskType taskType, PickupDispatchTaskStatus status, PickupDispatchTaskIsDeleted isDeleted) {
        //今日的时间边界
        DateTime dateTime = DateUtil.date();
        LocalDateTime startDateTime = DateUtil.beginOfDay(dateTime).toLocalDateTime();
        LocalDateTime endDateTime = DateUtil.endOfDay(dateTime).toLocalDateTime();

        Long count = this.lambdaQuery()
                .eq(ObjectUtil.isNotEmpty(courierId), PickupDispatchTaskEntity::getCourierId, courierId)
                .eq(ObjectUtil.isNotEmpty(taskType), PickupDispatchTaskEntity::getTaskType, taskType)
                .eq(ObjectUtil.isNotEmpty(status), PickupDispatchTaskEntity::getStatus, status)
                .eq(ObjectUtil.isNotEmpty(isDeleted), PickupDispatchTaskEntity::getIsDeleted, isDeleted)
                .between(PickupDispatchTaskEntity::getCreated, startDateTime, endDateTime)
                .count();
        return Convert.toInt(count, 0);
    }

    @Override
    public List<PickupDispatchTaskDTO> findAll(Long courierId, PickupDispatchTaskType taskType, PickupDispatchTaskStatus taskStatus, PickupDispatchTaskIsDeleted isDeleted) {
        List<PickupDispatchTaskEntity> entities = this.lambdaQuery()
                .eq(ObjectUtil.isNotEmpty(courierId), PickupDispatchTaskEntity::getCourierId, courierId)
                .eq(ObjectUtil.isNotEmpty(taskType), PickupDispatchTaskEntity::getTaskType, taskType)
                .eq(ObjectUtil.isNotEmpty(taskStatus), PickupDispatchTaskEntity::getStatus, taskStatus)
                .eq(ObjectUtil.isNotEmpty(isDeleted), PickupDispatchTaskEntity::getIsDeleted, isDeleted)
                .orderByDesc(PickupDispatchTaskEntity::getUpdated)
                .list();
        //实体类转为dto
        return BeanUtil.copyToList(entities, PickupDispatchTaskDTO.class);
    }

    @Override
    public PickupDispatchTaskStatisticsDTO todayTaskStatistics(Long courierId) {
        PickupDispatchTaskStatisticsDTO statisticsDTO = new PickupDispatchTaskStatisticsDTO();
        //今日的时间边界
        DateTime dateTime = DateUtil.date();
        LocalDateTime startDateTime = DateUtil.beginOfDay(dateTime).toLocalDateTime();
        LocalDateTime endDateTime = DateUtil.endOfDay(dateTime).toLocalDateTime();

        //一次查询出今日该快递员的所有任务，在内存中分组统计，避免多次查库
        List<PickupDispatchTaskEntity> taskEntities = this.lambdaQuery()
                .eq(PickupDispatchTaskEntity::getCourierId, courierId)
                .between(PickupDispatchTaskEntity::getCreated, startDateTime, endDateTime)
                .list();

        //按任务类型分组：1为取件任务，2为派件任务
        Map<Integer, List<PickupDispatchTaskEntity>> typeGroupMap = taskEntities.stream()
                .collect(Collectors.groupingBy(entity -> entity.getTaskType().getCode()));

        //取件任务统计：总数、新任务、已完成、已取消
        List<PickupDispatchTaskEntity> pickupList = typeGroupMap.getOrDefault(PickupDispatchTaskType.PICKUP.getCode(), Collections.emptyList());
        statisticsDTO.setPickupNum(pickupList.size());
        Map<PickupDispatchTaskStatus, Long> pickupStatusCountMap = pickupList.stream()
                .collect(Collectors.groupingBy(PickupDispatchTaskEntity::getStatus, Collectors.counting()));
        statisticsDTO.setNewPickUpNum(Convert.toInt(pickupStatusCountMap.getOrDefault(PickupDispatchTaskStatus.NEW, 0L), 0));
        statisticsDTO.setCompletePickUpNum(Convert.toInt(pickupStatusCountMap.getOrDefault(PickupDispatchTaskStatus.COMPLETED, 0L), 0));
        statisticsDTO.setCancelPickUpNum(Convert.toInt(pickupStatusCountMap.getOrDefault(PickupDispatchTaskStatus.CANCELLED, 0L), 0));

        //派件任务统计：总数、新任务（待派件）、已签收、已取消
        List<PickupDispatchTaskEntity> dispatchList = typeGroupMap.getOrDefault(PickupDispatchTaskType.DISPATCH.getCode(), Collections.emptyList());
        statisticsDTO.setDispatchNum(dispatchList.size());
        Map<PickupDispatchTaskStatus, Long> dispatchStatusCountMap = dispatchList.stream()
                .collect(Collectors.groupingBy(PickupDispatchTaskEntity::getStatus, Collectors.counting()));
        statisticsDTO.setNewDispatchNum(Convert.toInt(dispatchStatusCountMap.getOrDefault(PickupDispatchTaskStatus.NEW, 0L), 0));
        //已签收按签收状态统计：1为已签收（拒收2不属于已签收）
        statisticsDTO.setSignedNum(Convert.toInt(dispatchList.stream()
                .filter(entity -> ObjectUtil.equal(entity.getSignStatus(), PickupDispatchTaskSignStatus.RECEIVED))
                .count(), 0));
        statisticsDTO.setCancelDispatchNum(Convert.toInt(dispatchStatusCountMap.getOrDefault(PickupDispatchTaskStatus.CANCELLED, 0L), 0));

        return statisticsDTO;
    }
}
