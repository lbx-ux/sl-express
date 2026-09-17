package com.sl.ms.web.customer.service.impl;

import com.sl.ms.web.customer.properties.JwtProperties;
import com.sl.ms.web.customer.service.TokenService;
import com.sl.ms.web.customer.vo.user.UserLoginVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

@Service
public class TokenServiceImpl implements TokenService {

    @Resource
    private JwtProperties jwtProperties;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public static final String REDIS_REFRESH_TOKEN_PREFIX = "SL_CUSTOMER_REFRESH_TOKEN_";

    @Override
    public String createAccessToken(Map<String, Object> claims) {
        //生成短令牌的有效期时间单位为：分钟
        //TODO 待实现
        return null;
    }

    @Override
    public String createRefreshToken(Map<String, Object> claims) {
        //生成长令牌的有效期时间单位为：小时
        //TODO 待实现
        return null;
    }

    @Override
    public UserLoginVO refreshToken(String refreshToken) {
        //TODO 待实现
        return null;
    }

}
