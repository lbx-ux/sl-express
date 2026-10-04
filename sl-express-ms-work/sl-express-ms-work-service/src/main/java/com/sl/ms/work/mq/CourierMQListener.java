package com.sl.ms.work.mq;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.sl.ms.oms.api.OrderFeign;
import com.sl.ms.oms.dto.OrderDTO;
import com.sl.ms.oms.enums.OrderStatus;
import com.sl.ms.work.domain.enums.pickupDispatchtask.PickupDispatchTaskAssignedStatus;
import com.sl.ms.work.domain.enums.pickupDispatchtask.PickupDispatchTaskSignStatus;
import com.sl.ms.work.domain.enums.pickupDispatchtask.PickupDispatchTaskStatus;
import com.sl.ms.work.domain.enums.pickupDispatchtask.PickupDispatchTaskType;
import com.sl.ms.work.entity.PickupDispatchTaskEntity;
import com.sl.ms.work.service.PickupDispatchTaskService;
import com.sl.transport.common.constant.Constants;
import com.sl.transport.common.exception.SLException;
import com.sl.transport.common.vo.CourierMsg;
import com.sl.transport.common.vo.CourierTaskMsg;
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
import java.util.stream.Collectors;

/**
 * 快递员的消息处理，该处理器处理两个消息：
 * 1. 生成快递员取派件任务
 * 2. 快递员取件成功，订单转运单
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CourierMQListener {
    private final PickupDispatchTaskService  pickupDispatchTaskService;
    private final OrderFeign orderFeign;

    /**
     * 生成快递员取派件任务
     *
     * @param msg 消息
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = Constants.MQ.Queues.WORK_PICKUP_DISPATCH_TASK_CREATE),
            exchange = @Exchange(name = Constants.MQ.Exchanges.PICKUP_DISPATCH_TASK_DELAYED, type = ExchangeTypes.TOPIC, delayed = Constants.MQ.DELAYED),
            key = Constants.MQ.RoutingKeys.PICKUP_DISPATCH_TASK_CREATE
    ))
    public void listenCourierTaskMsg(String msg) {
        //{"taskType":1,"orderId":225125208064,"created":1654767899885,"courierId":1001,"agencyId":8001,"estimatedStartTime":1654224658728,"mark":"带包装"}
        log.info("接收到快递员任务的消息 >>> msg = {}", msg);
        //解析消息
        CourierTaskMsg courierTaskMsg = JSONUtil.toBean(msg, CourierTaskMsg.class);
        Long orderId = courierTaskMsg.getOrderId();
        //1.幂等性的校验：判断订单对应的取派件任务是否存在，判断条件：订单号+任务状态
        List<PickupDispatchTaskEntity> pickupDispatchTaskEntities =
                pickupDispatchTaskService.findByOrderId(orderId, PickupDispatchTaskType.codeOf(courierTaskMsg.getTaskType()));
        //查找任务状态为NEW的任务
        List<PickupDispatchTaskEntity> newTaskList = pickupDispatchTaskEntities.stream()
                .filter(pickupDispatchTask -> pickupDispatchTask.getStatus().equals(PickupDispatchTaskStatus.NEW))
                .filter(pickupDispatchTask -> pickupDispatchTask.getOrderId().equals(orderId))
                .collect(Collectors.toList());
        if(CollUtil.isNotEmpty(newTaskList)){
            //消息重复，不能处理
            return ;
        }
        //2.生成取派件任务
        //校验订单是否存在
        OrderDTO orderDTO = orderFeign.findById(orderId);
        if(ObjectUtil.isEmpty(orderDTO)){
            //订单不存在，忽略该消息
            return ;
        }
        //校验订单的状态
        if(ObjectUtil.equals(orderDTO.getStatus(), OrderStatus.CLOSE.getCode())||ObjectUtil.equals(orderDTO.getStatus(), OrderStatus.CANCELLED.getCode())){
            //订单已取消或关闭，无需处理
            return ;
        }

        PickupDispatchTaskEntity entity = BeanUtil.toBean(courierTaskMsg, PickupDispatchTaskEntity.class);
        entity.setTaskType(PickupDispatchTaskType.codeOf(courierTaskMsg.getTaskType()));
        LocalDateTime estimatedStartTime = LocalDateTimeUtil.offset(courierTaskMsg.getEstimatedEndTime(), -1, ChronoUnit.HOURS);
        entity.setEstimatedStartTime(estimatedStartTime);
        entity.setSignStatus(PickupDispatchTaskSignStatus.NOT_SIGNED);
        //任务分配状态
        if(ObjectUtil.isNotEmpty(entity.getCourierId())){
            entity.setAssignedStatus(PickupDispatchTaskAssignedStatus.DISTRIBUTED);
        }else{
            entity.setAssignedStatus(PickupDispatchTaskAssignedStatus.MANUAL_DISTRIBUTED);
        }

        PickupDispatchTaskEntity pickupDispatchTaskEntity = pickupDispatchTaskService.saveTaskPickupDispatch(entity);
        if(ObjectUtil.isEmpty(pickupDispatchTaskEntity)){
            //创建失败，抛出异常，激活重试机制，持久化数据
            throw new SLException("新增快递员取派件任务失败");
        }
    }

    /**
     * 快递员取件成功
     *
     * @param msg 消息
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = Constants.MQ.Queues.WORK_COURIER_PICKUP_SUCCESS),
            exchange = @Exchange(name = Constants.MQ.Exchanges.COURIER, type = ExchangeTypes.TOPIC),
            key = Constants.MQ.RoutingKeys.COURIER_PICKUP
    ))
    public void listenCourierPickupMsg(String msg) {
        log.info("接收到快递员取件成功的消息 >>> msg = {}", msg);
        //解析消息
        CourierMsg courierMsg = JSONUtil.toBean(msg, CourierMsg.class);
        System.out.println(courierMsg);
        //TODO 未实现具体逻辑
    }
}
