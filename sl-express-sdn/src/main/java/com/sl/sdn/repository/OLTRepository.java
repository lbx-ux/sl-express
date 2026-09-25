package com.sl.sdn.repository;

import com.sl.sdn.entity.node.OLTEntity;
import org.springframework.data.neo4j.repository.Neo4jRepository;

/**
 * 一级转运中心数据操作
 */
public interface OLTRepository extends Neo4jRepository<OLTEntity, Long> {

    /**
     * 根据bid查询
     *
     * @param bid 业务id
     * @return 一级转运中心数据
     */
    OLTEntity findByBid(Long bid);

    /**
     * 根据bid删除
     *
     * @param bid 业务id
     * @return 删除的数据条数
     */
    Long deleteByBid(Long bid);

}