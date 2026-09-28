package com.sl.transport.repository.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.PageUtil;
import cn.hutool.core.util.StrUtil;
import com.sl.transport.common.util.PageResponse;
import com.sl.transport.domain.TransportLineNodeDTO;
import com.sl.transport.domain.TransportLineSearchDTO;
import com.sl.transport.entity.line.TransportLine;
import com.sl.transport.entity.node.AgencyEntity;
import com.sl.transport.entity.node.BaseEntity;
import com.sl.transport.repository.TransportLineRepository;
import com.sl.transport.utils.TransportLineUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Record;
import org.neo4j.driver.internal.value.PathValue;
import org.neo4j.driver.types.Relationship;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TransportLineRepositoryImpl implements TransportLineRepository {
    private final Neo4jClient neo4jClient;

    //查询两个网点之间最短的路线，查询深度为：10
    @Override
    public TransportLineNodeDTO findShortestPath(AgencyEntity start, AgencyEntity end) {
        return findShortestPath(start, end,8);
    }

    //查询两个网点之间最短的路线，最大查询深度为：10
    @Override
    public TransportLineNodeDTO findShortestPath(AgencyEntity start, AgencyEntity end, int depth) {
        String type = AgencyEntity.class.getAnnotation(Node.class).value()[0];
        String query = StrUtil.format("MATCH path = shortestPath((start:{}) -[*1..{}]-> (end:{})) " +
                "WHERE start.bid = $startId AND end.bid= $endId AND start.status=true AND end.status=true " +
                "RETURN path", type, depth,type);
        List<TransportLineNodeDTO> list = this.executeQueryPath(query, start, end);
        if(CollUtil.isEmpty(list)){
            return null;
        }
        return CollUtil.getFirst(list);
    }

    //查询两个网点之间的路线列表，成本优先 > 转运节点优先
    @Override
    public List<TransportLineNodeDTO> findPathList(AgencyEntity start, AgencyEntity end, int depth, int limit) {
        String type = AgencyEntity.class.getAnnotation(Node.class).value()[0];
        String query = StrUtil.format("MATCH path = (start:{}) -[*1..{}]-> (end:{}) " +
                "WHERE start.bid = $startId AND end.bid= $endId AND start.status=true AND end.status=true " +
                "UNWIND relationships(path) AS r\n" +
                "WITH sum(r.cost) AS cost, path\n" +
                "RETURN path ORDER BY cost ASC, LENGTH(path) ASC LIMIT {}",type,depth,type,limit);
        return this.executeQueryPath(query, start, end);
    }

    private List<TransportLineNodeDTO> executeQueryPath(String query,AgencyEntity start, AgencyEntity end) {
        return ListUtil.toList(neo4jClient.query(query)
                .bind(start.getBid()).to("startId") //绑定参数
                .bind(end.getBid()).to("endId")     //绑定参数
                .fetchAs(TransportLineNodeDTO.class)       //返回值映射的对象类型
                .mappedBy((typeSystem, record) -> {    //手动结果映射
                    PathValue pathValue = (PathValue) record.get(0);
                    return TransportLineUtils.convert(pathValue);
                })
                .all());
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
        //1.实体转 Map，剔除非关系属性：id 是 Neo4j 内部 id，startOrganName/endOrganName 存在节点上
        Map<String, Object> props = BeanUtil.beanToMap(transportLine);
        MapUtil.removeAny(props, "id", "startOrganName", "endOrganName");

        //2.反向关系的属性：起点终点互换（create 时成对创建的另一半）
        Map<String, Object> reverseProps = new HashMap<>(props);
        reverseProps.put("startOrganId", transportLine.getEndOrganId());
        reverseProps.put("endOrganId", transportLine.getStartOrganId());

        //3.成对更新：正向按 id 匹配，反向按相同节点 + 起终点互换匹配
        String query = "MATCH (m) -[r]-> (n)\n" +
                "WHERE id(r)=$id\n" +
                "MATCH (n) -[r2]-> (m)\n" +
                "WHERE r2.startOrganId = r.endOrganId AND r2.endOrganId = r.startOrganId\n" +
                "SET r += $props, r2 += $reverseProps\n" +
                "RETURN count(r) AS count";

        return neo4jClient.query(query)
                .bind(transportLine.getId()).to("id")
                .bind(props).to("props")
                .bind(reverseProps).to("reverseProps")
                .fetchAs(Long.class)
                .one()
                .orElse(0L);
    }

    //删除路线
    @Override
    public Long remove(Long lineId) {
        String delete = StrUtil.format("MATCH (n)-[r]->(m) \n" +
                "WHERE id(r)=$id\n" +
                "DELETE r\n" +
                "RETURN count(r) AS count");
        return neo4jClient.query(delete)
                .bind(lineId).to("id")
                .fetchAs(Long.class)
                .one()
                .orElse(0L);
    }

    //分页查询路线
    @Override
    public PageResponse<TransportLine> queryPageList(TransportLineSearchDTO transportLineSearchDTO) {
        int page = Math.max(transportLineSearchDTO.getPage(), 1);
        int pageSize = transportLineSearchDTO.getPageSize();
        int skip = (page - 1) * pageSize;

        //将查询 DTO 对象提取为一个“干净”的业务查询参数 Map，自动过滤掉空值字段，并剥离分页参数。
        Map<String, Object> map = BeanUtil.beanToMap(transportLineSearchDTO, false, true);
        MapUtil.removeAny(map,"page","pageSize");

        String[] query = buildPageQueryCypher(map);
        String queryCypher = query[0];
        String countCypher = query[1];
        //查询路线
        List<TransportLine> transportLines = ListUtil.toList(neo4jClient.query(queryCypher)
                .bind(skip).to("skip")
                .bind(pageSize).to("limit")
                .bindAll(map)
                .fetchAs(TransportLine.class)
                .mappedBy(((typeSystem, record) -> toTranLine(record)))
                .all());

        //计算查询出来的总数
        Long total = neo4jClient.query(countCypher)
                .bindAll(map)
                .fetchAs(Long.class)
                .one()
                .orElse(0L);

        PageResponse<TransportLine> pageResponse = new PageResponse<>();
        pageResponse.setPage(page);
        pageResponse.setPageSize(pageSize);
        pageResponse.setItems(transportLines);
        pageResponse.setCounts(total);
        Long pages = Convert.toLong(PageUtil.totalPage(Convert.toInt(total), pageSize));
        pageResponse.setPages(pages);

        return pageResponse;
    }

    private TransportLine toTranLine(Record record) {
        org.neo4j.driver.types.Node startNode = record.get("m").asNode();
        org.neo4j.driver.types.Node endNode = record.get("n").asNode();
        Relationship relationship = record.get("r").asRelationship();
        Map<String, Object> map = relationship.asMap();

        TransportLine transportLine = BeanUtil.toBeanIgnoreError(map, TransportLine.class);
        transportLine.setStartOrganName(startNode.get("name").asString());
        transportLine.setStartOrganId(startNode.get("bid").asLong());
        transportLine.setEndOrganName(endNode.get("name").asString());
        transportLine.setEndOrganId(endNode.get("bid").asLong());
        transportLine.setId(relationship.id());
        return transportLine;
    }

    private String[] buildPageQueryCypher(Map<String, Object> searchParam){
        String prefix="MATCH (m) -[r]-> (n) WHERE 1=1 ";
        String query="";
        if(CollUtil.isNotEmpty(searchParam)){
            if(ObjectUtil.isNotEmpty(searchParam.get("name"))){
                query+="AND r.name CONTAINS $name ";
            }
            if(ObjectUtil.isNotEmpty(searchParam.get("number"))){
                query+="AND r.number = $number ";
            }
            if(ObjectUtil.isNotEmpty(searchParam.get("startOrganId"))){
                query+="AND r.startOrganId = $startOrganId ";
            }
            if(ObjectUtil.isNotEmpty(searchParam.get("endOrganId"))){
                query+="AND r.endOrganId = $endOrganId ";
            }
        }
        String queryCypher=prefix+query+" RETURN m,r,n ORDER BY id(r) DESC SKIP $skip LIMIT $limit";
        String countCypher=prefix+query+" return count(r) AS count";

        return new  String[]{queryCypher,countCypher};
    }

    //根据ids批量查询路线
    @Override
    public List<TransportLine> queryByIds(List<Long> ids) {
        String query = StrUtil.format("MATCH (n)-[r]->(m)\n" +
                "WHERE id(r) in {}\n" +
                "return n,m,r",ids);
        return executeQuery(query);
    }

    //根据id查询路线
    @Override
    public TransportLine queryById(Long id) {
        String query = StrUtil.format("MATCH (n)-[r]->(m)\n" +
                "WHERE id(r) = {}\n" +
                "return n,m,r",id);
        return CollUtil.getFirst(executeQuery(query));
    }

    private List<TransportLine> executeQuery(String query) {
        return ListUtil.toList(neo4jClient.query(query)
                .fetchAs(TransportLine.class)
                .mappedBy(((typeSystem, record) -> {
                    org.neo4j.driver.types.Node start = record.get("n").asNode();
                    org.neo4j.driver.types.Node end = record.get("m").asNode();
                    Relationship relationship = record.get("r").asRelationship();
                    Map<String, Object> map = relationship.asMap();
                    TransportLine transportLine = BeanUtil.toBeanIgnoreError(map, TransportLine.class);
                    transportLine.setStartOrganName(start.get("name").asString());
                    transportLine.setEndOrganName(end.get("name").asString());
                    transportLine.setId(relationship.id());
                    return transportLine;
                }))
                .all());
    }
}
