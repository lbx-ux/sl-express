package com.sl.sdn.repository;

import com.sl.sdn.entity.node.TLTEntity;
import org.springframework.data.neo4j.repository.Neo4jRepository;

/**
 * 二级转运中心数据操作
 */
public interface TLTRepository extends Neo4jRepository<TLTEntity, Long> {

    /**
     * 根据bid查询
     *
     * @param bid 业务id
     * @return 二级转运中心数据
     */
    TLTEntity findByBid(Long bid);

    /**
     * 根据bid删除
     *
     * @param bid 业务id
     * @return 删除的数据条数
     */
    Long deleteByBid(Long bid);

}