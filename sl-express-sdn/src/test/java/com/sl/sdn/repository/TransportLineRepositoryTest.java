package com.sl.sdn.repository;

import com.sl.sdn.dto.TransportLineNodeDTO;
import com.sl.sdn.entity.node.AgencyEntity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import javax.annotation.Resource;

@SpringBootTest
class TransportLineRepositoryTest {

    @Resource
    private TransportLineRepository transportLineRepository;

    @Test
    void findShortestPath() {
        AgencyEntity start = AgencyEntity.builder().bid(100280L).build();
        AgencyEntity end = AgencyEntity.builder().bid(210057L).build();
        TransportLineNodeDTO transportLineNodeDTO = this.transportLineRepository.findShortestPath(start, end);
        System.out.println(transportLineNodeDTO);
    }

    @Test
    void findCostLowerPath() {
        AgencyEntity start = AgencyEntity.builder().bid(100280L).build();
        AgencyEntity end = AgencyEntity.builder().bid(210057L).build();
        TransportLineNodeDTO transportLineNodeDTO = this.transportLineRepository.findCostLowerPath(start, end);
        System.out.println(transportLineNodeDTO);
    }
}