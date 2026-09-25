package com.sl.sdn.repository.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.sl.sdn.dto.OrganDTO;
import com.sl.sdn.dto.TransportLineNodeDTO;
import com.sl.sdn.entity.node.AgencyEntity;
import com.sl.sdn.enums.OrganTypeEnum;
import com.sl.sdn.repository.TransportLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.internal.value.PathValue;
import org.neo4j.driver.types.Path;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class TransportLineRepositoryImpl implements TransportLineRepository {
    private final Neo4jClient neo4jClient;
    @Override
    public TransportLineNodeDTO findShortestPath(AgencyEntity start, AgencyEntity end) {
        //1.定义要执行的Cypher语句
        String type = AgencyEntity.class.getAnnotation(Node.class).value()[0];
        String cypher = StrUtil.format("MATCH path = shortestPath((start:{}) -[*1..10]-> (end:{})) " +
                "WHERE start.bid = $startId AND end.bid= $endId " +
                "RETURN path", type, type);

        //2.执行语句
        Optional<TransportLineNodeDTO> optional = neo4jClient.query(cypher)
                .bind(start.getBid()).to("startId") //绑定参数
                .bind(end.getBid()).to("endId")     //绑定参数
                .fetchAs(TransportLineNodeDTO.class)       //返回值映射的对象类型
                .mappedBy((typeSystem, record) -> {    //手动结果映射
                    PathValue pathValue = (PathValue) record.get(0);
                    Path path = pathValue.asPath();
                    TransportLineNodeDTO dto = new TransportLineNodeDTO();
                    //获取路线中的所有节点
                    List<OrganDTO> nodeList = StreamUtil.of(path.nodes())
                            .map(node -> {
                                Map<String, Object> map = node.asMap();
                                OrganDTO organDTO = BeanUtil.toBean(map, OrganDTO.class);
                                OrganTypeEnum organTypeEnum = OrganTypeEnum.valueOf(CollUtil.getFirst(node.labels()));
                                organDTO.setType(organTypeEnum.getCode());
                                //查询出来的数据，x：经度，y：纬度
                                organDTO.setLatitude(BeanUtil.getProperty(map.get("location"), "y"));
                                organDTO.setLongitude(BeanUtil.getProperty(map.get("location"), "x"));
                                return organDTO;
                            }).collect(Collectors.toList());

                    dto.setNodeList(nodeList);
                    //提取关系中的 cost 数据，进行求和计算，算出该路线的总成本
                    double cost = StreamUtil.of(path.relationships())
                            .mapToDouble(relationship -> {
                                Map<String, Object> objectMap = relationship.asMap();
                                return Convert.toDouble(objectMap.get("cost"), 0d);
                            }).sum();
                    dto.setCost(cost);

                    //取2位小数
                    dto.setCost(NumberUtil.round(dto.getCost(), 2).doubleValue());

                    return dto;
                })
                .one();

        //3.返回数据
        return optional.orElse(null);
    }

    @Override
    public TransportLineNodeDTO findCostLowerPath(AgencyEntity start, AgencyEntity end) {
        //1.定义要执行的Cypher语句
        String type = AgencyEntity.class.getAnnotation(Node.class).value()[0];
        String cypher = StrUtil.format("MATCH path = (start:{}) -[*..10]->(end:{})\n" +
                "WHERE start.bid = $startId AND end.bid = $endId\n" +
                "UNWIND relationships(path) AS r\n" +
                "WITH sum(r.cost) AS cost, path\n" +
                "RETURN path ORDER BY cost ASC, LENGTH(path) ASC LIMIT 1",type,type);

        //2.执行语句
        Optional<TransportLineNodeDTO> optional = neo4jClient.query(cypher)
                .bind(start.getBid()).to("startId") //绑定参数
                .bind(end.getBid()).to("endId")     //绑定参数
                .fetchAs(TransportLineNodeDTO.class)       //返回值映射的对象类型
                .mappedBy((typeSystem, record) -> {    //手动结果映射
                    PathValue pathValue = (PathValue) record.get(0);
                    Path path = pathValue.asPath();
                    TransportLineNodeDTO dto = new TransportLineNodeDTO();
                    //获取路线中的所有节点
                    List<OrganDTO> nodeList = StreamUtil.of(path.nodes())
                            .map(node -> {
                                Map<String, Object> map = node.asMap();
                                OrganDTO organDTO = BeanUtil.toBean(map, OrganDTO.class);
                                OrganTypeEnum organTypeEnum = OrganTypeEnum.valueOf(CollUtil.getFirst(node.labels()));
                                organDTO.setType(organTypeEnum.getCode());
                                //查询出来的数据，x：经度，y：纬度
                                organDTO.setLatitude(BeanUtil.getProperty(map.get("location"), "y"));
                                organDTO.setLongitude(BeanUtil.getProperty(map.get("location"), "x"));
                                return organDTO;
                            }).collect(Collectors.toList());

                    dto.setNodeList(nodeList);
                    //提取关系中的 cost 数据，进行求和计算，算出该路线的总成本
                    double cost = StreamUtil.of(path.relationships())
                            .mapToDouble(relationship -> {
                                Map<String, Object> objectMap = relationship.asMap();
                                return Convert.toDouble(objectMap.get("cost"), 0d);
                            }).sum();
                    dto.setCost(cost);

                    //取2位小数
                    dto.setCost(NumberUtil.round(dto.getCost(), 2).doubleValue());

                    return dto;
                })
                .one();

        //3.返回数据
        return optional.orElse(null);
    }
}
