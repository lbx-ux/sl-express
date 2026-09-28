package com.sl.transport.controller;

import com.sl.transport.domain.CostConfigurationDTO;
import com.sl.transport.service.CostConfigurationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 成本配置相关业务对外提供接口服务
 *
 * @author wxb
 * @version 1.0
 */
@Api(tags = "成本配置")
@RequestMapping("cost-configuration")
@Validated
@RestController
@RequiredArgsConstructor
public class CostConfigurationController {
    private final CostConfigurationService costConfigurationService;

    @ApiOperation(value = "查询成本配置")
    @GetMapping
    public List<CostConfigurationDTO> findConfiguration() {
        return costConfigurationService.findConfiguration();
    }

    @ApiOperation(value = "保存成本配置")
    @PostMapping
    public void saveConfiguration(@RequestBody List<CostConfigurationDTO> dto) {
        costConfigurationService.saveConfiguration(dto);
    }
}
