package com.sl.transport.mq;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sl.transport.common.constant.Constants;
import com.sl.transport.entity.node.AgencyEntity;
import com.sl.transport.entity.node.BaseEntity;
import com.sl.transport.entity.node.OLTEntity;
import com.sl.transport.entity.node.TLTEntity;
import com.sl.transport.enums.OrganTypeEnum;
import com.sl.transport.service.IService;
import com.sl.transport.utils.OrganServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuthMQListener {

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = Constants.MQ.Queues.AUTH_TRANSPORT),
            exchange = @Exchange(name = "${rabbitmq.exchange},type=ExchangeTypes.TOPIC"),
            key = "#"
    ))
    public void listenAgencyMsg(String msg){
        log.info("消费者接收到消息：{}",msg);
        //解析消息
        JSONObject jsonObject = JSONUtil.parseObj(msg);
        String type = jsonObject.getStr("type");
        if(ObjectUtil.notEqual(type,"ORG")){
            //非机构同步的消息,忽略
            return ;
        }
        String operation = jsonObject.getStr("operation");
        JSONObject content = (JSONObject) jsonObject.getJSONArray("content").getObj(0);
        String name = content.getStr("name");
        Long parentId= content.getLong("parentId");
        BaseEntity entity = null;
        IService iService=null;
        if(StrUtil.endWith(name,"转运中心")){
            //一级转运中心
            entity=new OLTEntity();
            entity.setParentId(parentId);
            iService = OrganServiceFactory.getBean(OrganTypeEnum.OLT.getCode());
        }else if(StrUtil.endWith(name,"分拣中心")){
            //二级转运中心
            entity=new TLTEntity();
            entity.setParentId(parentId);
            iService = OrganServiceFactory.getBean(OrganTypeEnum.TLT.getCode());
        }else if(StrUtil.endWith(name,"营业部")){
            //网点
            entity=new AgencyEntity();
            entity.setParentId(parentId);
            iService = OrganServiceFactory.getBean(OrganTypeEnum.AGENCY.getCode());
        }else{
            log.info("机构的名称不符合规范：{}",msg);
            return ;
        }
        //填充数据
        entity.setName(name);
        entity.setBid(content.getLong("id"));
        entity.setStatus(content.getBool("status"));

        switch (operation) {
            case "ADD": {
                iService.create(entity);
                break;
            }
            case "UPDATE": {
                iService.update(entity);
                break;
            }
            case "DEL": {
                iService.deleteByBid(entity.getBid());
                break;
            }
        }
    }
}
