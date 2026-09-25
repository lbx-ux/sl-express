package com.sl.sdn.repository;

import com.sl.sdn.entity.node.AgencyEntity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.*;

import javax.annotation.Resource;
import java.util.List;

@SpringBootTest
class AgencyRepositoryTest {

    @Resource
    private AgencyRepository agencyRepository;

    @Test
    public void testFindByBid() {
        AgencyEntity agencyEntity = this.agencyRepository.findByBid(25023L);
        System.out.println(agencyEntity);
    }

    @Test
    public void testSave() {
        AgencyEntity agencyEntity = new AgencyEntity();
        agencyEntity.setAddress("测试数据地址");
        agencyEntity.setBid(9001L);
        agencyEntity.setName("测试节点");
        agencyEntity.setPhone("1388888888888");

        this.agencyRepository.save(agencyEntity);
        System.out.println(agencyEntity);
    }

    @Test
    public void testUpdate() {
        AgencyEntity agencyEntity = this.agencyRepository.findByBid(9001L);
        agencyEntity.setName("测试节点1");

        this.agencyRepository.save(agencyEntity);
        System.out.println(agencyEntity);
    }

    @Test
    public void testDeleteByBid() {
        Long count = this.agencyRepository.deleteByBid(9001L);
        System.out.println(count);
    }

    /**
     * 查询全部
     */
    @Test
    public void testFindAll() {
        List<AgencyEntity> list = this.agencyRepository.findAll();
        for (AgencyEntity agencyEntity : list) {
            System.out.println(agencyEntity);
        }
    }

    /**
     * 分页查询
     */
    @Test
    public void testPage() {
        //设置分页、排序条件，page从0开始
        PageRequest pageRequest = PageRequest.of(1, 2, Sort.by(Sort.Order.desc("bid")));
        Page<AgencyEntity> page = this.agencyRepository.findAll(pageRequest);
        page.getContent().forEach(agencyEntity -> {
            System.out.println(agencyEntity);
        });
    }

}