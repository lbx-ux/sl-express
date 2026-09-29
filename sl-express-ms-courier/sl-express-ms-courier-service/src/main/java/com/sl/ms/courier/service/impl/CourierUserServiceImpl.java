package com.sl.ms.courier.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.sl.ms.base.api.common.WorkSchedulingFeign;
import com.sl.ms.base.domain.base.WorkSchedulingDTO;
import com.sl.ms.base.domain.enums.WorkUserTypeEnum;
import com.sl.ms.courier.service.CourierUserService;
import com.sl.ms.scope.api.ServiceScopeFeign;
import com.sl.ms.scope.dto.ServiceScopeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourierUserServiceImpl implements CourierUserService {
    private final ServiceScopeFeign serviceScopeFeign;
    private final WorkSchedulingFeign workSchedulingFeign;

    /**
     * 条件查询快递员列表（结束取件时间当天快递员有排班）
     * 如果服务范围内无快递员，或满足服务范围的快递员无排班，则返回该网点所有满足排班的快递员
     *
     * @param agencyId         网点id
     * @param longitude        用户地址的经度
     * @param latitude         用户地址的纬度
     * @param estimatedEndTime 结束取件时间
     * @return 快递员id列表
     */
    @Override
    public List<Long> queryCourierIdListByCondition(Long agencyId, Double longitude, Double latitude, Long estimatedEndTime) {
        //1.根据条件查询服务范围内的快递员
        List<ServiceScopeDTO> serviceScopeDTOS = serviceScopeFeign.queryListByLocation(2, longitude, latitude);

        //2.查询快递员的排班情况，如果有排班就返回符合条件的快递员，如果这些快递员今日都没有排班，就选择同网点有排班的快递员
        //如果该网点的快递员全部休息，就返回null，由人工处理
        if(CollUtil.isNotEmpty(serviceScopeDTOS)){
            List<Long> courierIdList = serviceScopeDTOS.stream()
                    .map(ServiceScopeDTO::getBid)
                    .collect(Collectors.toList());
            String courierIds = StrUtil.join(",", courierIdList);


            //查询排班数据，对满足服务范围、网点的快递员筛选排班
            List<WorkSchedulingDTO> workSchedulingDTOS = workSchedulingFeign.monthSchedule(courierIds, agencyId, WorkUserTypeEnum.COURIER.getCode(), estimatedEndTime);
            //拿到今天有排班的快递员id
            List<Long> nowCourierIds = workSchedulingDTOS.stream()
                    .filter(w -> CollUtil.isNotEmpty(w.getWorkSchedules()) && Boolean.TRUE.equals(w.getWorkSchedules().get(0)))
                    .map(WorkSchedulingDTO::getUserId)
                    .collect(Collectors.toList());
            //存在同时满足服务范围、网点、排班的快递员，直接返回
            if(CollUtil.isNotEmpty(nowCourierIds)){
                return nowCourierIds;
            }
        }
        //3.如果服务范围内没有快递员，或服务范围内的快递员没有排班，则查询该网点的任一有排班快递员
        List<WorkSchedulingDTO> workSchedulingDTOS = workSchedulingFeign.monthSchedule(null,agencyId, WorkUserTypeEnum.COURIER.getCode(), estimatedEndTime);
        if(CollUtil.isNotEmpty(workSchedulingDTOS)){
            //过滤出有排班的快递员
            List<Long> nowCourierIds = workSchedulingDTOS.stream()
                    .filter(w -> CollUtil.isNotEmpty(w.getWorkSchedules()) && Boolean.TRUE.equals(w.getWorkSchedules().get(0)))
                    .map(WorkSchedulingDTO::getUserId)
                    .collect(Collectors.toList());
            if(CollUtil.isNotEmpty(nowCourierIds)){
                return nowCourierIds;
            }
        }
        //该网点有快递员，但是没有今日排班的快递员
        return null;
    }
}
