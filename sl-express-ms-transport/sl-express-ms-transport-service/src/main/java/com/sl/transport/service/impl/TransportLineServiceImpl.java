package com.sl.transport.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.itheima.em.sdk.EagleMapTemplate;
import com.itheima.em.sdk.enums.ProviderEnum;
import com.itheima.em.sdk.vo.Coordinate;
import com.sl.transport.common.exception.SLException;
import com.sl.transport.common.util.PageResponse;
import com.sl.transport.domain.OrganDTO;
import com.sl.transport.domain.TransportLineNodeDTO;
import com.sl.transport.domain.TransportLineSearchDTO;
import com.sl.transport.entity.line.TransportLine;
import com.sl.transport.entity.node.AgencyEntity;
import com.sl.transport.entity.node.BaseEntity;
import com.sl.transport.entity.node.OLTEntity;
import com.sl.transport.entity.node.TLTEntity;
import com.sl.transport.enums.ExceptionEnum;
import com.sl.transport.enums.TransportLineEnum;
import com.sl.transport.repository.TransportLineRepository;
import com.sl.transport.service.OrganService;
import com.sl.transport.service.TransportLineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransportLineServiceImpl implements TransportLineService {
    private final TransportLineRepository transportLineRepository;
    private final EagleMapTemplate eagleMapTemplate;
    private final OrganService organService;
    //private final CostConfigurationService costConfigurationService;

    //新增路线
    @Override
    public Boolean createLine(TransportLine transportLine) {
        //1.校验路线类型是否正确
        Integer type = transportLine.getType();
        TransportLineEnum lineEnum = TransportLineEnum.codeOf(type);
        if(ObjectUtil.isEmpty(lineEnum)){
            throw new SLException(ExceptionEnum.TRANSPORT_LINE_TYPE_ERROR);
        }

        //2.判断起点和终点是否相同
        Long startOrganId = transportLine.getStartOrganId();
        Long endOrganId = transportLine.getEndOrganId();
        if(ObjectUtil.equals(startOrganId, endOrganId)){
            throw new SLException(ExceptionEnum.TRANSPORT_LINE_ORGAN_CANNOT_SAME);
        }

        BaseEntity firstNode = null;
        BaseEntity endNode = null;
        //3.判断起点和终点是否正确
        switch (lineEnum){
            //干线：一级转运中心到一级转运中心
            case TRUNK_LINE:{
                firstNode=OLTEntity.builder().bid(startOrganId).build();
                endNode=OLTEntity.builder().bid(endOrganId).build();
                break;
            }
            //支线：二级转运中心到一级转运中心
            case BRANCH_LINE:{
                firstNode= TLTEntity.builder().bid(startOrganId).build();
                endNode=OLTEntity.builder().bid(endOrganId).build();
                break;
            }
            //接驳路线：网点到二级转运中心
            case CONNECT_LINE:{
                firstNode= AgencyEntity.builder().bid(startOrganId).build();
                endNode=TLTEntity.builder().bid(endOrganId).build();
                break;
            }
            default:{
                throw new SLException(ExceptionEnum.TRANSPORT_LINE_TYPE_ERROR);
            }
        }
        if(ObjectUtil.hasEmpty(firstNode,endNode)){
            throw new SLException(ExceptionEnum.START_END_ORGAN_NOT_FOUND);
        }

        //4.查询是否有存在路线
        Long count = transportLineRepository.queryCount(firstNode, endNode);
        if(count>0){
            throw new SLException(ExceptionEnum.TRANSPORT_LINE_ALREADY_EXISTS);
        }

        //5.补充其他数据
        transportLine.setId(null);
        transportLine.setCreated(System.currentTimeMillis());
        transportLine.setUpdated(transportLine.getCreated());
        infoFromMap(firstNode, endNode, transportLine);

        Long result = transportLineRepository.create(firstNode, endNode, transportLine);
        return result>0;
    }

    //通过地图查询距离，时间，成本等参数
    private void infoFromMap(BaseEntity firstNode, BaseEntity endNode, TransportLine transportLine) {
        OrganDTO firstOrgan = organService.findByBid(firstNode.getBid());
        OrganDTO endOrgan = organService.findByBid(endNode.getBid());

        Coordinate origin = new Coordinate(firstOrgan.getLongitude(), firstOrgan.getLatitude());
        Coordinate destination = new Coordinate(endOrgan.getLongitude(), endOrgan.getLatitude());
        //设置高德地图参数，默认是不返回预计耗时的，需要额外设置参数
        Map<String, Object> param = MapUtil.<String, Object>builder().put("show_fields", "cost").build();
        String driving = this.eagleMapTemplate.opsForDirection().driving(ProviderEnum.AMAP, origin, destination, param);
        if (StrUtil.isEmpty(driving)) {
            return;
        }
        JSONObject jsonObject = JSONUtil.parseObj(driving);
        //时间，单位：秒
        Long duration = Convert.toLong(jsonObject.getByPath("route.paths[0].cost.duration"), -1L);
        transportLine.setTime(duration);
        //距离，单位：米
        Double distance = Convert.toDouble(jsonObject.getByPath("route.paths[0].distance"), -1d);
        transportLine.setDistance(NumberUtil.round(distance, 0).doubleValue());

        // 总成本 = 每公里平均成本 * 距离（单位：米） / 1000
        /*Double cost = costConfigurationService.findCostByType(transportLine.getType());
        transportLine.setCost(NumberUtil.round(cost * distance / 1000, 2).doubleValue());*/
    }

    //更新路线
    @Override
    public Boolean updateLine(TransportLine transportLine) {
        return null;
    }


    //删除路线
    @Override
    public Boolean deleteLine(Long id) {
        return null;
    }

    //分页查询路线
    @Override
    public PageResponse<TransportLine> queryPageList(TransportLineSearchDTO transportLineSearchDTO) {
        return null;
    }

    //查询两个网点之间最短的路线，最大查询深度为：10
    @Override
    public TransportLineNodeDTO queryShortestPath(Long startId, Long endId) {
        return null;
    }

    //查询两个网点之间成本最低的路线，最大查询深度为：10
    @Override
    public TransportLineNodeDTO findLowestPath(Long startId, Long endId) {
        return null;
    }

    //根据调度策略查询路线
    @Override
    public TransportLineNodeDTO queryPathByDispatchMethod(Long startId, Long endId) {
        return null;
    }

    //根据ids批量查询路线
    @Override
    public List<TransportLine> queryByIds(Long... ids) {
        return List.of();
    }

    //根据id查询路线
    @Override
    public TransportLine queryById(Long id) {
        return null;
    }
}
