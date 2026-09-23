package com.sl.ms.carriage.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sl.ms.carriage.domain.dto.CarriageDTO;
import com.sl.ms.carriage.entity.CarriageEntity;
import com.sl.ms.carriage.mapper.CarriageMapper;
import com.sl.ms.carriage.service.CarriageService;
import com.sl.transport.common.util.BeanUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CarriageServiceImpl extends ServiceImpl<CarriageMapper, CarriageEntity> implements CarriageService {

    @Override
    public List<CarriageDTO> findAll() {
        // 构造查询条件
        LambdaQueryWrapper<CarriageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(CarriageEntity::getCreated);
        // 查询数据库
        List<CarriageEntity> list = this.list(wrapper);

        if (ObjectUtil.isEmpty(list)) {
            return Collections.emptyList();
        }

        return list.stream()
                //转化对象，返回集合数据
                .map(carriageEntity -> {
                    CarriageDTO carriageDTO = BeanUtil.toBean(carriageEntity, CarriageDTO.class);
                    //关联城市数据按照逗号分割成集合
                    carriageDTO.setAssociatedCityList(StrUtil.split(carriageEntity.getAssociatedCity(), ','));
                    return carriageDTO;
                })
                .collect(Collectors.toList());
    }
}