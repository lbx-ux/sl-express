package com.sl.transport.repository.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.sl.transport.domain.OrganDTO;
import com.sl.transport.enums.OrganTypeEnum;
import com.sl.transport.repository.OrganRepository;
import lombok.RequiredArgsConstructor;
import org.neo4j.driver.internal.InternalPoint2D;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OrganRepositoryImpl implements OrganRepository {
    private final Neo4jClient neo4jClient;

    //根据id进行查询
    @Override
    public OrganDTO findByBid(Long bid) {
        String query = StrUtil.format("MATCH (n)\n" +
                "WHERE n.bid = {}\n" +
                "RETURN n", bid);
        return CollUtil.getFirst(executeQuery(query));
    }

    //根据ids进行查询
    @Override
    public List<OrganDTO> findByBids(List<Long> bids) {
        String query = StrUtil.format("MATCH (n)\n" +
                "WHERE n.bid in {}\n" +
                "RETURN n", bids);
        return executeQuery(query);
    }

    //查询所有的机构，如果name不为空的按照name模糊查询
    @Override
    public List<OrganDTO> findAll(String name) {
        name = StrUtil.removeAll(name, '\'', '"');
        String cypherQuery = StrUtil.isEmpty(name) ?
                "MATCH (n) RETURN n" :
                StrUtil.format("MATCH (n) WHERE n.name CONTAINS '{}' RETURN n", name);
        return executeQuery(cypherQuery);
    }

    private List<OrganDTO> executeQuery(String query){
        Collection<OrganDTO> list = neo4jClient.query(query)
                .fetchAs(OrganDTO.class)
                .mappedBy(((typeSystem, record) -> {
                    Map<String, Object> map = record.get(0).asMap();
                    OrganDTO organDTO = BeanUtil.toBean(map, OrganDTO.class);
                    InternalPoint2D location = (InternalPoint2D) map.get("location");
                    if (ObjectUtil.isNotEmpty(location)) {
                        organDTO.setLongitude(location.x());
                        organDTO.setLatitude(location.y());
                    }
                    String type = CollUtil.getFirst(record.get(0).asNode().labels());
                    organDTO.setType(OrganTypeEnum.valueOf(type).getCode());
                    return organDTO;
                })).all();
        return ListUtil.toList(list);
    }
}
