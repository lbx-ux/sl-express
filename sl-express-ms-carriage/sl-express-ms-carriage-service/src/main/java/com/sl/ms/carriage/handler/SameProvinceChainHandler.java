package com.sl.ms.carriage.handler;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sl.ms.base.api.common.AreaFeign;
import com.sl.ms.carriage.domain.constant.CarriageConstant;
import com.sl.ms.carriage.domain.dto.WaybillDTO;
import com.sl.ms.carriage.entity.CarriageEntity;
import com.sl.ms.carriage.mapper.CarriageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(200)
@Component
@RequiredArgsConstructor
public class SameProvinceChainHandler extends AbstractCarriageChainHandler {
    private final CarriageMapper carriageMapper;
    private final AreaFeign areaFeign;

    @Override
    public CarriageEntity doHandler(WaybillDTO waybillDTO) {
        Long receiverCityId = waybillDTO.getReceiverCityId();
        Long senderCityId = waybillDTO.getSenderCityId();
        Long receiverProvinceId = areaFeign.get(receiverCityId).getParentId();
        Long senderProvinceId = areaFeign.get(senderCityId).getParentId();
        CarriageEntity carriageEntity = null;
        if(ObjectUtil.equal(receiverProvinceId,senderProvinceId)){
            carriageEntity = carriageMapper.selectOne(Wrappers.<CarriageEntity>lambdaQuery()
                    .eq(CarriageEntity::getTemplateType, CarriageConstant.SAME_PROVINCE)
                    .eq(CarriageEntity::getTransportType, CarriageConstant.REGULAR_FAST));
        }
        return  super.doNextHandler(waybillDTO,carriageEntity);
    }
}
