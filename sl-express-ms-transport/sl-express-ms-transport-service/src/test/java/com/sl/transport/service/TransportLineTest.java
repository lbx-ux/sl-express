package com.sl.transport.service;

import com.sl.transport.entity.line.TransportLine;
import com.sl.transport.entity.node.OLTEntity;
import com.sl.transport.repository.TransportLineRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Slf4j
public class TransportLineTest {
    @Autowired
    private TransportLineRepository transportLineRepository;
    @Test
    public void queryCount(){
        OLTEntity firstNode=new OLTEntity();
        firstNode.setBid(8003L);
        OLTEntity endNode = new OLTEntity();
        endNode.setBid(8005L);
        Long count = transportLineRepository.queryCount(firstNode, endNode);
        log.info("查询到的结果条数为{}",count);
    }

    @Test
    public void create(){
        OLTEntity firstNode=new OLTEntity();
        firstNode.setBid(10003L);
        firstNode.setName("北京");
        OLTEntity endNode = new OLTEntity();
        endNode.setBid(10005L);
        endNode.setName("上海");
        TransportLine transportLine=new  TransportLine();
        transportLine.setName("京沪");
        Long count = transportLineRepository.create(firstNode, endNode, transportLine);
        log.info("新增后的结果条数为{}",count);
    }

}
