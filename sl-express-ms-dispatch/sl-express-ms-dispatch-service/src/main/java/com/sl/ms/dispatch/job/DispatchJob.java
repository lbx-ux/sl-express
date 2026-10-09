package com.sl.ms.dispatch.job;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.sl.ms.base.api.common.MQFeign;
import com.sl.ms.base.api.truck.TruckPlanFeign;
import com.sl.ms.base.domain.truck.TruckDto;
import com.sl.ms.base.domain.truck.TruckPlanDto;
import com.sl.ms.dispatch.dto.DispatchMsgDTO;
import com.sl.ms.dispatch.mq.TransportOrderDispatchMQListener;
import com.sl.transport.common.constant.Constants;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调度运输任务
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DispatchJob {
    private final TruckPlanFeign truckPlanFeign;
    private final RedissonClient redissonClient;
    private final TransportOrderDispatchMQListener transportOrderDispatchMQListener;
    private final StringRedisTemplate stringRedisTemplate;
    private final MQFeign mqFeign;

    @Value("${sl.volume.ratio:0.95}")
    private Double volumeRatio;
    @Value("${sl.weight.ratio:0.95}")
    private Double weightRatio;
    /**
     * 分片广播方式处理运单，生成运输任务
     */
    @XxlJob("transportTask")
    public void transportTask() {
        // 分片参数
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();

        //1.查询2小时发车的车辆计划
        List<TruckPlanDto> truckPlanDtos = truckPlanFeign.pullUnassignedPlan(shardTotal, shardIndex);
        if(CollUtil.isEmpty(truckPlanDtos)){
            //没有查询到车辆，直接返回
            return ;
        }
        //2.对于车辆，一个一个处理，计算每一个车辆的运力，消费对应的队列
        for (TruckPlanDto truckPlanDto : truckPlanDtos) {
            //校验车辆计划对象
            if (ObjectUtil.hasEmpty(truckPlanDto.getStartOrganId(), truckPlanDto.getEndOrganId(),
                    truckPlanDto.getTransportTripsId(), truckPlanDto.getId())) {
                log.error("车辆计划对象数据不符合要求， truckPlanDto -> {}", truckPlanDto);
                continue;
            }
            //开始处理车辆计划
            Long startOrganId = truckPlanDto.getStartOrganId();
            Long endOrganId = truckPlanDto.getEndOrganId();
            //如果有两个车辆并行的处理一个队列，应该是先处理一个，待这个处理完成后，再处理下一个
            String redisKey = this.transportOrderDispatchMQListener.getListRedisKey(startOrganId, endOrganId);
            String lockRedisKey = Constants.LOCKS.DISPATCH_LOCK_PREFIX + redisKey;
            RLock lock = redissonClient.getFairLock(lockRedisKey);
            //该车辆分配的运单列表
            List<DispatchMsgDTO> dispatchMsgDTOList=new ArrayList<>();
            try{
                //一直等待，直到获取到锁为止
                lock.lock();
                //计算运力，分配订单
                this.executeTransportTask(redisKey,truckPlanDto.getTruckDto(),dispatchMsgDTOList);
            }finally{
                lock.unlock();
            }
            //生成运输任务
            this.createTransportTask(truckPlanDto, startOrganId, endOrganId, dispatchMsgDTOList);
        }
        //发送消息通过车辆已经完成调度
        this.completeTruckPlan(truckPlanDtos);
    }

    //计算运力，分配订单
    private void executeTransportTask(String redisKey, TruckDto truckDto, List<DispatchMsgDTO> dispatchMsgDTOList) {
        String redisValue = stringRedisTemplate.opsForList().rightPop(redisKey);
        if(StrUtil.isEmpty(redisValue)){
            //该队列没有要处理的运单了，直接返回就行
            return ;
        }
        DispatchMsgDTO dispatchMsgDTO = JSONUtil.toBean(redisValue, DispatchMsgDTO.class);

        //计算该车辆已经分配的运单，是否超出其运力，载重 或 体积超出，需要将新拿到的运单加进去后进行比较

        BigDecimal totalWeight = NumberUtil.add(NumberUtil.toBigDecimal(dispatchMsgDTOList.stream()
                .mapToDouble(DispatchMsgDTO::getTotalWeight)
                .sum()), dispatchMsgDTO.getTotalWeight());

        BigDecimal totalVolume = NumberUtil.add(NumberUtil.toBigDecimal(dispatchMsgDTOList.stream()
                .mapToDouble(DispatchMsgDTO::getTotalVolume)
                .sum()), dispatchMsgDTO.getTotalVolume());

        //车辆最大的容积和载重要留有余量，否则可能会超重 或 装不下
        BigDecimal maxAllowableLoad = NumberUtil.mul(truckDto.getAllowableLoad(), weightRatio, 1000);
        BigDecimal maxAllowableVolume = NumberUtil.mul(truckDto.getAllowableVolume(), volumeRatio);

        if (NumberUtil.isGreaterOrEqual(totalWeight, maxAllowableLoad)
                || NumberUtil.isGreaterOrEqual(totalVolume, maxAllowableVolume)) {
            //超出车辆运力，需要取货的运单再放回去，放到最右边，以便保证运单处理的顺序
            this.stringRedisTemplate.opsForList().rightPush(redisKey, redisValue);
            return;
        }

        //没有超出运力，将该运单加入到集合中
        dispatchMsgDTOList.add(dispatchMsgDTO);
        //递归处理运单
        executeTransportTask(redisKey, truckDto, dispatchMsgDTOList);

    }

    //生成运输任务
    private void createTransportTask(TruckPlanDto truckPlanDto, Long startOrganId, Long endOrganId, List<DispatchMsgDTO> dispatchMsgDTOList) {
        //将运单合并的结果以消息的方式发送出去
        //key-> 车辆id，value ->  运单id列表
        //{"driverIds":[123,456], "truckPlanId":456, "truckId":1210114964812075008,"totalVolume":4.2,"endOrganId":90001,
        // "totalWeight":7,"transportOrderIdList":[320733749248,420733749248],"startOrganId":100280}
        List<String> transportOrderIdList = CollStreamUtil.toList(dispatchMsgDTOList, DispatchMsgDTO::getTransportOrderId);
        //司机列表确保不为null
        List<Long> driverIds = CollUtil.isNotEmpty(truckPlanDto.getDriverIds()) ?
                truckPlanDto.getDriverIds() :
                ListUtil.empty();
        Map<String, Object> msgResult = MapUtil.<String, Object>builder()
                .put("truckId", truckPlanDto.getTruckId()) //车辆id
                .put("driverIds", driverIds) //司机id
                .put("truckPlanId", truckPlanDto.getId()) //车辆计划id
                .put("transportTripsId", truckPlanDto.getTransportTripsId()) //车次id
                .put("startOrganId", startOrganId) //开始机构id
                .put("endOrganId", endOrganId) //结束机构id
                //运单id列表
                .put("transportOrderIdList", transportOrderIdList)
                //总重量
                .put("totalWeight", StreamUtil.of(dispatchMsgDTOList)
                        .mapToDouble(DispatchMsgDTO::getTotalWeight)
                        .sum())
                //总体积
                .put("totalVolume", StreamUtil.of(dispatchMsgDTOList)
                        .mapToDouble(DispatchMsgDTO::getTotalVolume)
                        .sum())
                .build();

        //发送消息
        String jsonMsg = JSONUtil.toJsonStr(msgResult);
        this.mqFeign.sendMsg(Constants.MQ.Exchanges.TRANSPORT_TASK,
                Constants.MQ.RoutingKeys.TRANSPORT_TASK_CREATE, jsonMsg);

        if (CollUtil.isNotEmpty(transportOrderIdList)) {
            //删除redis中set存储的运单数据
            String setRedisKey = this.transportOrderDispatchMQListener.getSetRedisKey(startOrganId, endOrganId);
            this.stringRedisTemplate.opsForSet().remove(setRedisKey, transportOrderIdList.toArray());
        }
    }

    //发送消息车辆已完成调度
    private void completeTruckPlan(List<TruckPlanDto> truckDtoList){
        //{"ids":[1,2,3], "created":123456}
        Map<String, Object> msg = MapUtil.<String, Object>builder()
                .put("ids", CollUtil.getFieldValues(truckDtoList, "id", Long.class))
                .put("created", System.currentTimeMillis()).build();
        String jsonMsg = JSONUtil.toJsonStr(msg);
        //发送消息
        this.mqFeign.sendMsg(Constants.MQ.Exchanges.TRUCK_PLAN,
                Constants.MQ.RoutingKeys.TRUCK_PLAN_COMPLETE, jsonMsg);
    }
}
