package com.sl.ms.carriage.handler;

import com.sl.ms.carriage.domain.dto.WaybillDTO;
import com.sl.ms.carriage.entity.CarriageEntity;

public abstract class AbstractCarriageChainHandler {

    private AbstractCarriageChainHandler nextHandler;

    //设置下游Handler
    public void setNextHandler(AbstractCarriageChainHandler nextHandler) {
        this.nextHandler = nextHandler;
    }

    //执行过滤方法，通过输入参数查找运费模板
    public abstract CarriageEntity doHandler(WaybillDTO waybillDTO);

    //执行下一个处理器
    protected CarriageEntity doNextHandler(WaybillDTO waybillDTO, CarriageEntity carriageEntity) {
        if (nextHandler == null || carriageEntity != null) {
            //如果下游Handler为空 或 上个Handler已经找到运费模板就返回
            return carriageEntity;
        }
        return nextHandler.doHandler(waybillDTO);
    }
}
