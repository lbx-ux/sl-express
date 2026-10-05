package com.sl.ms.dispatch.mq;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.sl.ms.api.CourierFeign;
import com.sl.ms.base.api.common.MQFeign;
import com.sl.ms.transport.api.DispatchConfigurationFeign;
import com.sl.ms.work.api.PickupDispatchTaskFeign;
import com.sl.ms.work.domain.dto.CourierTaskCountDTO;
import com.sl.ms.work.domain.enums.pickupDispatchtask.PickupDispatchTaskType;
import com.sl.transport.common.constant.Constants;
import com.sl.transport.common.vo.CourierTaskMsg;
import com.sl.transport.common.vo.OrderMsg;
import com.sl.transport.domain.DispatchConfigurationDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单业务消息，接收到新订单后，根据快递员的负载情况，分配快递员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderMQListener {
    private final CourierFeign courierFeign;
    private final DispatchConfigurationFeign dispatchConfigurationFeign;
    private final MQFeign mqFeign;
    private final PickupDispatchTaskFeign pickupDispatchTaskFeign;

    /**
     * 如果有多个快递员，需要查询快递员今日的取派件数，根据此数量进行计算
     * 计算的逻辑：优先分配取件任务少的，取件数相同取第一个分配
     * <p>
     * 发送生成取件任务时需要计算时间差，如果小于2小时，实时发送；大于2小时，延时发送
     * 举例：
     * 1、现在10:30分，用户期望：11:00 ~ 12:00上门，实时发送
     * 2、现在10:30分，用户期望：13:00 ~ 14:00上门，延时发送，12点发送消息，延时1.5小时发送
     *
     * @param msg 消息内容
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = Constants.MQ.Queues.DISPATCH_ORDER_TO_PICKUP_DISPATCH_TASK),
            exchange = @Exchange(name = Constants.MQ.Exchanges.ORDER_DELAYED, type = ExchangeTypes.TOPIC, delayed = Constants.MQ.DELAYED),
            key = Constants.MQ.RoutingKeys.ORDER_CREATE
    ))
    public void listenOrderMsg(String msg) {
        //{"orderId":123, "agencyId": 8001, "taskType":1, "mark":"带包装", "longitude":116.111, "latitude":39.00, "created":1654224658728, "estimatedStartTime": 1654224658728}
        log.info("接收到订单的消息 >>> msg = {}", msg);
        //1.解析消息
        OrderMsg orderMsg = JSONUtil.toBean(msg, OrderMsg.class);
        Long agencyId = orderMsg.getAgencyId();
        Double longitude = orderMsg.getLongitude();
        Double latitude = orderMsg.getLatitude();
        LocalDateTime estimatedEndTime = orderMsg.getEstimatedEndTime();

        //2.查询有排班、符合条件的快递员，并且选择快递员
        List<Long> courierIds = courierFeign.queryCourierIdListByCondition(agencyId, longitude, latitude, LocalDateTimeUtil.toEpochMilli(estimatedEndTime));
        Long courierId=null;
        if(CollUtil.isNotEmpty(courierIds)){
            courierId=this.selectCourier(courierIds, orderMsg.getTaskType());
        }

        //3.如果是取件任务，需要计算时间差，来决定是发送实时消息还是延时消息
        long between = LocalDateTimeUtil.between(LocalDateTime.now(),estimatedEndTime, ChronoUnit.MINUTES);
        //获取系统设定的最晚任务下发时间
        DispatchConfigurationDTO configuration = dispatchConfigurationFeign.findConfiguration();
        int dispatchTime=configuration.getDispatchTime()*60;
        int delay=Constants.MQ.DEFAULT_DELAY;

        if(ObjectUtil.equal(orderMsg.getTaskType(),1)&&between>dispatchTime){
            //延迟消息
            LocalDateTime date = LocalDateTimeUtil.offset(estimatedEndTime, dispatchTime * -1, ChronoUnit.MINUTES);
            delay=Convert.toInt(LocalDateTimeUtil.between(LocalDateTime.now(), date, ChronoUnit.MILLIS));
        }

        //4. 发送消息，通知work微服务，用于创建快递员取派件任务
        CourierTaskMsg courierTaskMsg = BeanUtil.toBeanIgnoreError(orderMsg, CourierTaskMsg.class);
        courierTaskMsg.setCourierId(courierId);
        courierTaskMsg.setCreated(System.currentTimeMillis());

        mqFeign.sendMsg(Constants.MQ.Exchanges.PICKUP_DISPATCH_TASK_DELAYED,
                Constants.MQ.RoutingKeys.PICKUP_DISPATCH_TASK_CREATE, courierTaskMsg.toJson(), delay);

    }

    //选取快递员
    private Long selectCourier(List<Long> courierIds,Integer taskType) {
        //如果代选择的快递员有一个，那就直接返回
        if(courierIds.size()==1){
            return courierIds.get(0);
        }
        //如果为多个，那就选择工作量最少的那一个
        String date = DateUtil.today();
        //查询每个快递员的任务
        List<CourierTaskCountDTO> countByCourierIds = pickupDispatchTaskFeign.findCountByCourierIds(courierIds, PickupDispatchTaskType.codeOf(taskType), date);
        //寻找最少快递员
        if(CollUtil.isEmpty(countByCourierIds)){
            return CollUtil.getFirst(courierIds);
        }
        //查看任务数是否与快递员数相同，如果不相同需要补齐，设置任务数为0，这样就可以确保每个快递员都能分配到任务
        if(ObjectUtil.notEqual(courierIds.size(),countByCourierIds.size())){
            Set<Long> existIds = countByCourierIds.stream()
                    .map(CourierTaskCountDTO::getCourierId)
                    .collect(Collectors.toSet());
            List<CourierTaskCountDTO> dtoList = courierIds.stream()
                    .filter(courierId -> !existIds.contains(courierId))
                    .map(id -> CourierTaskCountDTO.builder()
                                .count(0L)
                                .courierId(id)
                                .build()
                    )
                    .collect(Collectors.toList());
            countByCourierIds.addAll(dtoList);
        }
        //按照任务数量从小到大排序
        CollUtil.sortByProperty(countByCourierIds, "count");
        return CollUtil.getFirst(countByCourierIds).getCourierId();
    }
}
