package com.sl.ms.carriage.handler;


import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sl.ms.carriage.domain.constant.CarriageConstant;
import com.sl.ms.carriage.domain.dto.WaybillDTO;
import com.sl.ms.carriage.entity.CarriageEntity;
import com.sl.ms.carriage.mapper.CarriageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(100)
@Component
@RequiredArgsConstructor
public class SameCityChainHandler extends AbstractCarriageChainHandler{
    private final CarriageMapper carriageMapper;

    @Override
    public CarriageEntity doHandler(WaybillDTO waybillDTO) {
        Long receiverCityId = waybillDTO.getReceiverCityId();
        Long senderCityId = waybillDTO.getSenderCityId();
        CarriageEntity carriageEntity =null;
        //1. 校验是否为同城寄
        if(ObjectUtil.equal(receiverCityId,senderCityId)){
            carriageEntity = carriageMapper.selectOne(Wrappers.<CarriageEntity>lambdaQuery()
                    .eq(CarriageEntity::getTemplateType, CarriageConstant.SAME_CITY)
                    .eq(CarriageEntity::getTransportType, CarriageConstant.REGULAR_FAST));
        }
        return super.doNextHandler(waybillDTO,carriageEntity);
    }
}
