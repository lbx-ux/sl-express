package com.sl.transport.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.lang.tree.TreeUtil;
import cn.hutool.core.util.ObjectUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sl.transport.common.exception.SLException;
import com.sl.transport.domain.OrganDTO;
import com.sl.transport.enums.ExceptionEnum;
import com.sl.transport.repository.OrganRepository;
import com.sl.transport.service.OrganService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganServiceImpl implements OrganService {
    private final OrganRepository organRepository;
    private final ObjectMapper objectMapper;

    //根据id查询
    @Override
    public OrganDTO findByBid(Long bid) {
        OrganDTO organDTO = organRepository.findByBid(bid);
        if(ObjectUtil.isNotEmpty(organDTO)){
            return organDTO;
        }
        throw new SLException(ExceptionEnum.ORGAN_NOT_FOUND);
    }

    //根据ids查询
    @Override
    public List<OrganDTO> findByBids(List<Long> bids) {
        List<OrganDTO> organDTOS = organRepository.findByBids(bids);
        if(ObjectUtil.isNotEmpty(organDTOS)){
            return organDTOS;
        }
        throw new SLException(ExceptionEnum.ORGAN_NOT_FOUND);
    }

    //查询所有的机构，如果name不为空的按照name模糊查询
    @Override
    public List<OrganDTO> findAll(String name) {
        return organRepository.findAll(name);
    }

    //查询机构树
    @Override
    public String findAllTree() {
        List<OrganDTO> organDTOList = this.findAll(null);
        if(CollUtil.isEmpty(organDTOList)){
            return "";
        }
        List<Tree<Long>> treeList = TreeUtil.build(organDTOList, 0L, (object, node) -> {
            node.setId(object.getId());
            node.setParentId(object.getParentId());
            node.putAll(BeanUtil.beanToMap(object));
            node.remove("bid");
        });
        try {
            return this.objectMapper.writeValueAsString(treeList);
        } catch (JsonProcessingException e) {
            throw new SLException("序列化json出错！", e);
        }
    }
}
