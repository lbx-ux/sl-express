package com.sl.sdn.repository;

import com.sl.sdn.entity.node.AgencyEntity;
import org.springframework.data.neo4j.repository.Neo4jRepository;

/**
 * 网点操作
 */
public interface AgencyRepository extends Neo4jRepository<AgencyEntity, Long> {

    /**
     * 根据bid查询
     *
     * @param bid 业务id
     * @return 网点数据
     */
    AgencyEntity findByBid(Long bid);

    /**
     * 根据bid删除
     *
     * @param bid 业务id
     * @return 删除的数据条数
     */
    Long deleteByBid(Long bid);

}