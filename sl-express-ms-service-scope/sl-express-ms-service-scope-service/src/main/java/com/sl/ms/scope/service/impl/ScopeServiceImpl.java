package com.sl.ms.scope.service.impl;

import com.itheima.em.sdk.EagleMapTemplate;
import com.itheima.em.sdk.vo.Coordinate;
import com.itheima.em.sdk.vo.GeoResult;
import com.mongodb.client.result.DeleteResult;
import com.sl.ms.scope.entity.ServiceScopeEntity;
import com.sl.ms.scope.enums.ServiceTypeEnum;
import com.sl.ms.scope.service.ScopeService;
import com.sl.transport.common.util.ObjectUtil;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service
public class ScopeServiceImpl implements ScopeService {

    @Resource
    private MongoTemplate mongoTemplate;
    @Resource
    private EagleMapTemplate eagleMapTemplate;

    @Override
    public Boolean saveOrUpdate(Long bid, ServiceTypeEnum type, GeoJsonPolygon polygon) {
        //根据条件查询
        Query query = Query.query(Criteria.where("bid").is(bid).and("type").is(type.getCode()));
        ServiceScopeEntity serviceScopeEntity = this.mongoTemplate.findOne(query, ServiceScopeEntity.class);

        if (ObjectUtil.isEmpty(serviceScopeEntity)) {
            //新增操作
            serviceScopeEntity = new ServiceScopeEntity();
            serviceScopeEntity.setBid(bid);
            serviceScopeEntity.setType(type.getCode());
            serviceScopeEntity.setPolygon(polygon); //作业范围多边形坐标
            serviceScopeEntity.setCreated(System.currentTimeMillis());
            serviceScopeEntity.setUpdated(serviceScopeEntity.getCreated());
        } else {
            //更新操作
            serviceScopeEntity.setUpdated(System.currentTimeMillis());
            serviceScopeEntity.setPolygon(polygon); //作业范围多边形坐标
        }

        //新增操作
        ServiceScopeEntity saveServiceScopeEntity = this.mongoTemplate.save(serviceScopeEntity);
        return ObjectUtil.isNotEmpty(saveServiceScopeEntity);
    }

    @Override
    public Boolean delete(String id) {
        Query query = Query.query(Criteria.where("id").is(new ObjectId(id)));
        DeleteResult deleteResult = this.mongoTemplate.remove(query, ServiceScopeEntity.class);
        return deleteResult.getDeletedCount() > 0;
    }

    @Override
    public Boolean delete(Long bid, ServiceTypeEnum type) {
        Query query = Query.query(Criteria.where("bid").is(bid).and("type").is(type.getCode()));
        DeleteResult deleteResult = this.mongoTemplate.remove(query, ServiceScopeEntity.class);
        return deleteResult.getDeletedCount() > 0;
    }

    @Override
    public ServiceScopeEntity queryById(String id) {
        return this.mongoTemplate.findById(new ObjectId(id), ServiceScopeEntity.class);
    }

    @Override
    public ServiceScopeEntity queryByBidAndType(Long bid, ServiceTypeEnum type) {
        Query query = Query.query(Criteria.where("bid").is(bid).and("type").is(type.getCode()));
        return this.mongoTemplate.findOne(query, ServiceScopeEntity.class);
    }

    @Override
    public List<ServiceScopeEntity> queryListByPoint(ServiceTypeEnum type, GeoJsonPoint point) {
        Query query = Query.query(Criteria.where("type").is(type.getCode())
                .and("polygon").intersects(point));
        return this.mongoTemplate.find(query, ServiceScopeEntity.class);
    }

    @Override
    public List<ServiceScopeEntity> queryListByPoint(ServiceTypeEnum type, String address) {
        // 将中文地址转化为经纬度坐标点
        GeoResult geoResult = this.eagleMapTemplate.opsForBase().geoCode(address);
        Coordinate location = geoResult.getLocation();
        GeoJsonPoint point = new GeoJsonPoint(location.getLongitude(), location.getLatitude());
        return this.queryListByPoint(type, point);
    }
}
