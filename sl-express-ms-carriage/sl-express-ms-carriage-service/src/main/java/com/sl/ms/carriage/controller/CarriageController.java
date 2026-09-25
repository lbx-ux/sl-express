package com.sl.ms.carriage.controller;

import com.sl.ms.carriage.domain.dto.CarriageDTO;
import com.sl.ms.carriage.domain.dto.WaybillDTO;
import com.sl.ms.carriage.service.CarriageService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
import java.util.List;

@Slf4j
@Validated
@RestController
@Api(tags = "运费管理")
@RequestMapping("/carriages")
@RequiredArgsConstructor
public class CarriageController {
    private final CarriageService carriageService;

    @GetMapping
    @ApiOperation(value = "运费模板列表")
    public List<CarriageDTO> findAll() {
        return this.carriageService.findAll();
    }

    @PostMapping
    @ApiOperation(value = "新增/修改运费模板")
    public CarriageDTO saveOrUpdate(@Valid @RequestBody CarriageDTO carriageDto) {
        return this.carriageService.saveOrUpdate(carriageDto);
    }

    @PostMapping("compute")
    @ApiOperation(value = "运费计算")
    public CarriageDTO compute(@RequestBody WaybillDTO waybillDTO) {
        return carriageService.compute(waybillDTO);
    }

    @DeleteMapping("{id}")
    @ApiOperation(value = "删除运费模板")
    @ApiImplicitParams(
            @ApiImplicitParam(name = "id",value = "运费模板id",required = true, dataTypeClass = String.class)
    )
    public void delete(@PathVariable("id") Long id){
        carriageService.delete(id);
    }

}