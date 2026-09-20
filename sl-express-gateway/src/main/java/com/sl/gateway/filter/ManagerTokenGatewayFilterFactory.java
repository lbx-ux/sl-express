package com.sl.gateway.filter;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.itheima.auth.factory.AuthTemplateFactory;
import com.itheima.auth.sdk.AuthTemplate;
import com.itheima.auth.sdk.dto.AuthUserInfoDTO;
import com.itheima.auth.sdk.service.TokenCheckService;
import com.sl.gateway.config.MyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ManagerTokenGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {
    private final MyConfig myConfig;
    private final TokenCheckService  tokenCheckService;

    @Value("${role.manager}")
    private List<Long> managerRoleIds;


    @Override
    public GatewayFilter apply(Object config) {
        return ((exchange, chain) -> {
            //1.校验请求路径是否在白名单中，如果在，直接放行，否则就需要进行后续
            String path = exchange.getRequest().getPath().toString();
            if(StrUtil.startWithAny(path,myConfig.getNoAuthPaths())){
                return chain.filter(exchange);
            }

            //2. 获取请求中的token，校验token是否有效
            String token = exchange.getRequest().getHeaders().getFirst("Authorization");
            if(StrUtil.isEmpty(token)){
                // 非法请求，响应401
                exchange.getResponse().setStatusCode(HttpStatus.MULTI_STATUS);
                // 拦截请求
                return exchange.getResponse().setComplete();
            }

            // 解析token
            AuthUserInfoDTO authUserInfoDTO = tokenCheckService.parserToken(token);
            if(ObjectUtil.isEmpty(authUserInfoDTO)){
                // token不可用，响应401
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                //拦截请求
                return  exchange.getResponse().setComplete();
            }

            //3. 校验该用户是否有权限访问,根据角色进行判断
            // 获取用户的角色
            AuthTemplate authTemplate = AuthTemplateFactory.get(token);
            List<Long> roleIdList = authTemplate.opsForRole().findRoleByUserId(authUserInfoDTO.getUserId()).getData();

            //判断用户的角色是否在允许访问的列表中
            Collection<Long> intersection = CollUtil.intersection(roleIdList, managerRoleIds);
            if(CollUtil.isEmpty(intersection)){
                // 没有权限访问,响应400
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                //拦截请求
                return  exchange.getResponse().setComplete();
            }

            //4. 校验通过，向下游传递用户信息和token
            exchange.getRequest().mutate().header("userInfo", JSONUtil.toJsonStr(authUserInfoDTO));
            exchange.getRequest().mutate().header("token", token);

            return chain.filter(exchange);
        });
    }
}
