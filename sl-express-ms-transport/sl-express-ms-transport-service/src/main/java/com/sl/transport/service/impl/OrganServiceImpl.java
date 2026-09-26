package com.sl.transport.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.sl.transport.common.exception.SLException;
import com.sl.transport.domain.OrganDTO;
import com.sl.transport.enums.ExceptionEnum;
import com.sl.transport.repository.OrganRepository;
import com.sl.transport.service.OrganService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrganServiceImpl implements OrganService {
    private final OrganRepository organRepository;

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
        return "";
    }
}
