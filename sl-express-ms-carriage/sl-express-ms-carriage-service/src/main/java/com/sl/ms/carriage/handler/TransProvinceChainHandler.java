package com.sl.ms.carriage.handler;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sl.ms.carriage.domain.constant.CarriageConstant;
import com.sl.ms.carriage.domain.dto.WaybillDTO;
import com.sl.ms.carriage.entity.CarriageEntity;
import com.sl.ms.carriage.enums.CarriageExceptionEnum;
import com.sl.ms.carriage.mapper.CarriageMapper;
import com.sl.transport.common.exception.SLException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(400)
@Component
@RequiredArgsConstructor
public class TransProvinceChainHandler extends AbstractCarriageChainHandler{
    private final CarriageMapper carriageMapper;
    @Override
    public CarriageEntity doHandler(WaybillDTO waybillDTO) {
        CarriageEntity carriageEntity = carriageMapper.selectOne(Wrappers.<CarriageEntity>lambdaQuery()
                .eq(CarriageEntity::getTemplateType, CarriageConstant.TRANS_PROVINCE)
                .eq(CarriageEntity::getTransportType, CarriageConstant.REGULAR_FAST));
        if(ObjectUtil.isEmpty(carriageEntity)){
            throw new SLException(CarriageExceptionEnum.NOT_FOUND);
        }
        return super.doNextHandler(waybillDTO,carriageEntity);
    }
}
