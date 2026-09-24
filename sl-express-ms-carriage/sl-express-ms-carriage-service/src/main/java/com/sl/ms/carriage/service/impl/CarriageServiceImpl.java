package com.sl.ms.carriage.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.*;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sl.ms.base.api.common.AreaFeign;
import com.sl.ms.carriage.domain.constant.CarriageConstant;
import com.sl.ms.carriage.domain.dto.CarriageDTO;
import com.sl.ms.carriage.domain.dto.WaybillDTO;
import com.sl.ms.carriage.domain.enums.EconomicRegionEnum;
import com.sl.ms.carriage.entity.CarriageEntity;
import com.sl.ms.carriage.enums.CarriageExceptionEnum;
import com.sl.ms.carriage.mapper.CarriageMapper;
import com.sl.ms.carriage.service.CarriageService;
import com.sl.ms.carriage.utils.CarriageUtils;
import com.sl.transport.common.exception.SLException;
import com.sl.transport.common.exception.SLWebException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CarriageServiceImpl extends ServiceImpl<CarriageMapper, CarriageEntity> implements CarriageService {
    private final AreaFeign areaFeign;

    @Override
    public List<CarriageDTO> findAll() {
        //查询数据库
        List<CarriageEntity> list = this.lambdaQuery()
                .orderByDesc(CarriageEntity::getCreated)
                .list();

        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }

        return list.stream()
                //转化对象，返回集合数据
                .map(CarriageUtils::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public CarriageDTO saveOrUpdate(CarriageDTO carriageDto) {
        //1.从数据库中根据模板类型查询数据
        List<CarriageEntity> list = this.lambdaQuery()
                .eq(CarriageEntity::getTemplateType, carriageDto.getTemplateType())
                .eq(CarriageEntity::getTransportType, carriageDto.getTransportType())
                .list();

        //2.判断是否为经济区互寄
        //非经济区
        if(ObjectUtil.notEqual(carriageDto.getTemplateType(),CarriageConstant.ECONOMIC_ZONE)){
            //新增(id为空) 且该类型模板已存在 → 抛异常
            if(ObjectUtil.isEmpty(carriageDto.getId()) && CollUtil.isNotEmpty(list)){
                throw new SLException(CarriageExceptionEnum.NOT_ECONOMIC_ZONE_REPEAT);
            }
            //更新：排除自己后还有同类型模板 → 抛异常
            long count = list.stream()
                    .filter(carriageEntity -> ObjectUtil.notEqual(carriageEntity.getId(), carriageDto.getId()))
                    .count();
            if(count > 0){
                throw new SLException(CarriageExceptionEnum.NOT_ECONOMIC_ZONE_REPEAT);
            }
            return this.saveOrUpdateCarriage(carriageDto);
        }
        //经济区
        //无同类模板，无需查重，直接落库
        if(CollUtil.isEmpty(list)){
            return saveOrUpdateCarriage(carriageDto);
        }

        //判断重复的思路：先将查询出的运费模板中的关联城市收集起来，传入的关联城市是否在此集合中
        List<String> associatedCityList = list.stream()
                .filter(e -> ObjectUtil.notEqual(e.getId(), carriageDto.getId()))
                .map(CarriageEntity::getAssociatedCity)      // Stream<String>
                .map(city -> StrUtil.split(city, ','))       // Stream<List<String>>
                .flatMap(List::stream)                       // Stream<String> ← JDK 写法
                .collect(Collectors.toList());
        //取交集，如果存在交集说明重复
        Collection<String> intersection = CollUtil.intersection(associatedCityList, carriageDto.getAssociatedCityList());
        if(CollUtil.isNotEmpty(intersection)){
            throw new SLException(CarriageExceptionEnum.ECONOMIC_ZONE_CITY_REPEAT);
        }
        //不重复
        return this.saveOrUpdateCarriage(carriageDto);
    }

    private CarriageDTO saveOrUpdateCarriage(CarriageDTO carriageDto){
        CarriageEntity entity = CarriageUtils.toEntity(carriageDto);
        boolean result = this.saveOrUpdate(entity);
        if(BooleanUtil.isTrue(result)){
            return CarriageUtils.toDTO(entity);
        }
        throw new SLWebException(CarriageExceptionEnum.SAVE_OR_UPDATE_ERROR);
    }

    //运费计算
    @Override
    public CarriageDTO compute(WaybillDTO waybillDTO) {
        //1.查找运费模板
        CarriageEntity carriage = findCarriage(waybillDTO);

        //2.计算实际的计费重量，结果保留一位小数
        double weight = getComputeWeight(waybillDTO, carriage);

        //3.计算运费
        double expense = carriage.getFirstWeight() + (weight - 1) * carriage.getContinuousWeight();
        expense = NumberUtil.round(expense, 1).doubleValue();

        //4.封装数据返回
        CarriageDTO carriageDTO = CarriageUtils.toDTO(carriage);
        carriageDTO.setExpense(expense);
        carriageDTO.setComputeWeight(weight);
        return carriageDTO;
    }
    
    //查询运费模板
    private CarriageEntity findCarriage(WaybillDTO waybillDTO){
        //获取寄件和收件地址
        Long receiverCityId = waybillDTO.getReceiverCityId();
        Long senderCityId = waybillDTO.getSenderCityId();

        //1. 校验是否为同城寄
        if(ObjectUtil.equal(receiverCityId,senderCityId)){
            CarriageEntity carriageEntity = findByTemplateType(CarriageConstant.SAME_CITY);
            if(ObjectUtil.isNotEmpty(carriageEntity)){
                return carriageEntity;
            }
        }

        //2. 校验是否为省内寄
        Long receiverProvinceId = areaFeign.get(receiverCityId).getParentId();
        Long senderProvinceId = areaFeign.get(senderCityId).getParentId();
        if(ObjectUtil.equal(receiverProvinceId,senderProvinceId)){
            CarriageEntity carriageEntity = findByTemplateType(CarriageConstant.SAME_PROVINCE);
            if(ObjectUtil.isNotEmpty(carriageEntity)){
                return carriageEntity;
            }
        }

        //3. 校验是否为经济区互寄
        //获取经济区省份的数据
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
            CarriageEntity carriage = this.lambdaQuery()
                    .eq(CarriageEntity::getTemplateType, CarriageConstant.ECONOMIC_ZONE)
                    .eq(CarriageEntity::getTransportType, CarriageConstant.REGULAR_FAST)
                    .like(CarriageEntity::getAssociatedCity, economicRegionEnum.getCode())
                    .one();
            if(ObjectUtil.isNotEmpty(carriage)){
                return carriage;
            }
        }

        //4. 最后兜底跨省寄
        CarriageEntity carriageEntity = findByTemplateType(CarriageConstant.TRANS_PROVINCE);
        if(ObjectUtil.isEmpty(carriageEntity)){
            throw new SLException(CarriageExceptionEnum.NOT_FOUND);
        }
        return carriageEntity;
    }

    //根据体积参数与实际重量计算计费重量
    private double getComputeWeight(WaybillDTO waybillDTO, CarriageEntity carriage){
        //1.计算体积
        Integer volume = waybillDTO.getVolume();
        if(ObjectUtil.isEmpty(volume)){
            try {
                volume=waybillDTO.getMeasureHigh()*waybillDTO.getMeasureLong()*waybillDTO.getMeasureWidth();
            } catch (Exception e) {
                volume=0;
            }
        }

        //2.体积转换为重量
        BigDecimal volumeWeight = NumberUtil.div(volume, carriage.getLightThrowingCoefficient(), 1);

        //3.取最大值
        double weight = NumberUtil.max(volumeWeight.doubleValue(), NumberUtil.round(waybillDTO.getWeight(), 1).doubleValue());

        //4.根据规则计算重量
        if(weight<=1){
            return 1;
        }
        if(weight<=10){
            return weight;
        }
        if(weight<100){
            double v = Math.ceil(weight * 2) / 2;
            return NumberUtil.round(v,1).doubleValue();
        }
        return NumberUtil.round(weight,0).doubleValue();
    }
    
    private CarriageEntity findByTemplateType(Integer templateType) {
        if(ObjectUtil.equal(templateType,CarriageConstant.ECONOMIC_ZONE)){
            throw new SLException(CarriageExceptionEnum.METHOD_CALL_ERROR);
        }
        return this.lambdaQuery()
                .eq(CarriageEntity::getTemplateType, templateType)
                .eq(CarriageEntity::getTransportType, CarriageConstant.REGULAR_FAST)
                .one();
    }
}