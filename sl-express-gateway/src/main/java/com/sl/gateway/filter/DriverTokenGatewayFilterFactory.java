package com.sl.gateway.filter;

import cn.hutool.core.collection.CollUtil;
import com.itheima.auth.factory.AuthTemplateFactory;
import com.itheima.auth.sdk.AuthTemplate;
import com.itheima.auth.sdk.dto.AuthUserInfoDTO;
import com.itheima.auth.sdk.service.TokenCheckService;
import com.sl.gateway.config.MyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DriverTokenGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> implements AuthFilter{
    private final MyConfig myConfig;
    private final TokenCheckService tokenCheckService;

    @Value("${role.driver}")
    private List<Long> managerRoleIds;

    @Override
    public GatewayFilter apply(Object config) {
        return new  TokenGatewayFilter(this,myConfig) {};
    }

    @Override
    public AuthUserInfoDTO check(String token) {
        AuthUserInfoDTO authUserInfoDTO = null;
        try{
            authUserInfoDTO = tokenCheckService.parserToken(token);
        }catch (Exception e){
            //忽略
        }
        return authUserInfoDTO;
    }

    @Override
    public Boolean auth(String token, AuthUserInfoDTO authUserInfo, String path) {
        //获取用户的角色
        AuthTemplate authTemplate = AuthTemplateFactory.get(token);
        List<Long> roleIdList = authTemplate.opsForRole().findRoleByUserId(authUserInfo.getUserId()).getData();

        //判断用户的角色是否在允许访问的列表中
        Collection<Long> intersection = CollUtil.intersection(roleIdList, managerRoleIds);
        return CollUtil.isNotEmpty(intersection);
    }
}
