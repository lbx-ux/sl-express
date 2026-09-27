package com.sl.transport.repository.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.sl.transport.common.util.PageResponse;
import com.sl.transport.domain.TransportLineNodeDTO;
import com.sl.transport.domain.TransportLineSearchDTO;
import com.sl.transport.entity.line.TransportLine;
import com.sl.transport.entity.node.AgencyEntity;
import com.sl.transport.entity.node.BaseEntity;
import com.sl.transport.repository.TransportLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TransportLineRepositoryImpl implements TransportLineRepository {
    private final Neo4jClient neo4jClient;

    //查询两个网点之间最短的路线，查询深度为：10
    @Override
    public TransportLineNodeDTO findShortestPath(AgencyEntity start, AgencyEntity end) {
        return null;
    }

    //查询两个网点之间最短的路线，最大查询深度为：10
    @Override
    public TransportLineNodeDTO findShortestPath(AgencyEntity start, AgencyEntity end, int depth) {
        return null;
    }

    //查询两个网点之间的路线列表，成本优先 > 转运节点优先
    @Override
    public List<TransportLineNodeDTO> findPathList(AgencyEntity start, AgencyEntity end, int depth, int limit) {
        return List.of();
    }

    //查询数据节点之间的关系数量
    @Override
    public Long queryCount(BaseEntity firstNode, BaseEntity endNode) {
        String firstNodeType = firstNode.getClass().getAnnotation(Node.class).value()[0];
        String endNodeType = endNode.getClass().getAnnotation(Node.class).value()[0];
        String query = StrUtil.format("MATCH (n:{}) -[r]- (m:{})\n" +
                "WHERE n.bid=$firstBid AND m.bid=$endBid \n" +
                "RETURN count(r) AS count", firstNodeType, endNodeType);
        return neo4jClient.query(query)
                .bind(firstNode.getBid()).to("firstBid")
                .bind(endNode.getBid()).to("endBid")
                .fetchAs(Long.class)
                .one()
                .orElse(0L);
    }

    //新增路线
    @Override
    public Long create(BaseEntity firstNode, BaseEntity endNode, TransportLine transportLine) {
        //获取起点、终点节点的类型
        String firstNodeType = firstNode.getClass().getAnnotation(Node.class).value()[0];
        String endNodeType = endNode.getClass().getAnnotation(Node.class).value()[0];
        //定义cypher语句，成对创建路线
        String cypherQuery = StrUtil.format("MATCH (m:{} {bid : $firstBid})\n" +
                "WITH m\n" + "MATCH (n:{} {bid : $endBid})\n" +
                "WITH m,n\n" +
                "CREATE\n" +
                " (m) -[r:IN_LINE {cost:$cost, number:$number, type:$type, name:$name, distance:$distance, time:$time, extra:$extra, startOrganId:$startOrganId, endOrganId:$endOrganId,created:$created, updated:$updated}]-> (n),\n" +
                " (m) <-[:OUT_LINE {cost:$cost, number:$number, type:$type, name:$name, distance:$distance, time:$time, extra:$extra, startOrganId:$endOrganId, endOrganId:$startOrganId, created:$created, updated:$updated}]- (n)\n" +
                "RETURN count(r) AS c", firstNodeType, endNodeType);

        return neo4jClient.query(cypherQuery)
                .bind(firstNode.getBid()).to("firstBid")
                .bind(endNode.getBid()).to("endBid")
                .bindAll(BeanUtil.beanToMap(transportLine))
                .fetchAs(Long.class)
                .one()
                .orElse(0L);
    }

    //更新路线
    @Override
    public Long update(TransportLine transportLine) {
        return 0L;
    }

    //删除路线
    @Override
    public Long remove(Long lineId) {
        return 0L;
    }

    //分页查询路线
    @Override
    public PageResponse<TransportLine> queryPageList(TransportLineSearchDTO transportLineSearchDTO) {
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
