package com.sl.ms.carriage.handler;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.EnumUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sl.ms.base.api.common.AreaFeign;
import com.sl.ms.carriage.domain.constant.CarriageConstant;
import com.sl.ms.carriage.domain.dto.WaybillDTO;
import com.sl.ms.carriage.domain.enums.EconomicRegionEnum;
import com.sl.ms.carriage.entity.CarriageEntity;
import com.sl.ms.carriage.mapper.CarriageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

@Order(300)
@Component
@RequiredArgsConstructor
public class EconomicZoneChainHandler extends  AbstractCarriageChainHandler{
    private final CarriageMapper carriageMapper;
    private final AreaFeign areaFeign;

    @Override
    public CarriageEntity doHandler(WaybillDTO waybillDTO) {
        Long receiverCityId = waybillDTO.getReceiverCityId();
        Long senderCityId = waybillDTO.getSenderCityId();
        Long receiverProvinceId = areaFeign.get(receiverCityId).getParentId();
        Long senderProvinceId = areaFeign.get(senderCityId).getParentId();
        CarriageEntity carriageEntity = null;
        LinkedHashMap<String, EconomicRegionEnum> enumMap = EnumUtil.getEnumMap(EconomicRegionEnum.class);
        EconomicRegionEnum economicRegionEnum = null;
        for (EconomicRegionEnum regionEnum : enumMap.values()) {
            //判断收，发件人所在id是否全部存在某一个经济区中
            boolean result = ArrayUtil.containsAll(regionEnum.getValue(), senderProvinceId, receiverProvinceId);
            if(result){
                economicRegionEnum = regionEnum;
                break;
            }
        }

        if(ObjectUtil.isNotEmpty(economicRegionEnum)){
            carriageEntity = carriageMapper.selectOne(Wrappers.<CarriageEntity>lambdaQuery()
                    .eq(CarriageEntity::getTemplateType, CarriageConstant.ECONOMIC_ZONE)
                    .eq(CarriageEntity::getTransportType, CarriageConstant.REGULAR_FAST)
                    .like(CarriageEntity::getAssociatedCity, economicRegionEnum.getCode()));
        }
        return super.doNextHandler(waybillDTO,carriageEntity);
    }
}
