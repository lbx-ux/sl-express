package com.sl.gateway.filter;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.itheima.auth.sdk.dto.AuthUserInfoDTO;
import com.sl.gateway.config.MyConfig;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;


public class TokenGatewayFilter implements GatewayFilter , Ordered {
    private final MyConfig myConfig;
    private final AuthFilter authFilter;

    public TokenGatewayFilter(AuthFilter authFilter,MyConfig myConfig) {
        this.authFilter = authFilter;
        this.myConfig = myConfig;
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //1.校验请求路径是否在白名单中，如果在，直接放行，否则就需要进行后续
        String path = exchange.getRequest().getPath().toString();
        if(StrUtil.startWithAny(path,myConfig.getNoAuthPaths())){
            return chain.filter(exchange);
        }

        //2. 获取请求中的token，校验token是否有效
        String token = exchange.getRequest().getHeaders().getFirst(authFilter.tokenHeaderName());
        if(StrUtil.isEmpty(token)){
            // 非法请求，响应401
            exchange.getResponse().setStatusCode(HttpStatus.MULTI_STATUS);
            // 拦截请求
            return exchange.getResponse().setComplete();
        }

        // 解析token
        AuthUserInfoDTO authUserInfoDTO = authFilter.check(token);

        if(ObjectUtil.isEmpty(authUserInfoDTO)){
            // token不可用，响应401
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            //拦截请求
            return  exchange.getResponse().setComplete();
        }

        //3. 校验该用户是否有权限访问,根据角色进行判断
        Boolean bool = authFilter.auth(token, authUserInfoDTO, path);
        if(ObjectUtil.notEqual(bool,Boolean.TRUE)){
            // 没有权限访问,响应400
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            //拦截请求
            return  exchange.getResponse().setComplete();
        }

        //4. 校验通过，向下游传递用户信息和token
        exchange.getRequest().mutate().header("userInfo", JSONUtil.toJsonStr(authUserInfoDTO));
        exchange.getRequest().mutate().header("token", token);

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }
}
