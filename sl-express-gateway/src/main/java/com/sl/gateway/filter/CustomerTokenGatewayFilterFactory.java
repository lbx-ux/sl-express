package com.sl.gateway.filter;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import com.itheima.auth.sdk.dto.AuthUserInfoDTO;
import com.sl.gateway.config.MyConfig;
import com.sl.gateway.properties.JwtProperties;
import com.sl.transport.common.constant.Constants;
import com.sl.transport.common.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class CustomerTokenGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> implements AuthFilter{
    private final MyConfig myConfig;
    private final JwtProperties jwtProperties;

    @Override
    public GatewayFilter apply(Object config) {
        return new  TokenGatewayFilter(this,myConfig) {};
    }

    @Override
    public AuthUserInfoDTO check(String token) {
        Map<String, Object> claims = JwtUtils.checkToken(token, jwtProperties.getPublicKey());
        if(ObjectUtil.isEmpty(claims)){
            return null;
        }
        Long userId = Convert.toLong(claims.get(Constants.GATEWAY.USER_ID));
        AuthUserInfoDTO authUserInfoDTO = new AuthUserInfoDTO();
        authUserInfoDTO.setUserId(userId);
        return authUserInfoDTO;
    }

    @Override
    public Boolean auth(String token, AuthUserInfoDTO authUserInfo, String path) {
        return true;
    }

    @Override
    public String tokenHeaderName(){
        return Constants.GATEWAY.ACCESS_TOKEN;
    }
}
