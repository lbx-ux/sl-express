package com.sl.ms.web.customer.service.impl;

import cn.hutool.core.date.DateField;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.sl.ms.web.customer.properties.JwtProperties;
import com.sl.ms.web.customer.service.TokenService;
import com.sl.ms.web.customer.vo.user.UserLoginVO;
import com.sl.transport.common.util.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public static final String REDIS_REFRESH_TOKEN_PREFIX = "wx:SL_CUSTOMER_REFRESH_TOKEN_";

    @Override
    public String createAccessToken(Map<String, Object> claims) {
        //生成短令牌的有效期时间单位为：分钟
        return JwtUtils.createToken(
                claims,
                jwtProperties.getPrivateKey(),
                jwtProperties.getAccessTtl(),
                DateField.MINUTE
        );
    }

    @Override
    public String createRefreshToken(Map<String, Object> claims) {
        //生成长令牌的有效期时间单位为：小时
        Integer ttl = jwtProperties.getRefreshTtl();
        String refreshToken = JwtUtils.createToken(claims, jwtProperties.getPrivateKey(), ttl);
        //将refreshToken存入到Redis中去
        stringRedisTemplate.opsForValue().set(getRedisRefreshToken(refreshToken), refreshToken,ttl, TimeUnit.HOURS);
        return refreshToken;
    }

    private String getRedisRefreshToken(String refreshToken) {
        //md5是为了缩短key的长度
        return REDIS_REFRESH_TOKEN_PREFIX + SecureUtil.md5(refreshToken);
    }

    @Override
    public UserLoginVO refreshToken(String refreshToken) {
        if(StrUtil.isBlank(refreshToken)){
            return null;
        }
        //校验token的有效期（二次校验）
        Map<String, Object> map = JwtUtils.checkToken(refreshToken, jwtProperties.getPublicKey());
        if(ObjectUtil.isEmpty(map)){
            return null;
        }

        //查询redis，确保refreshToken只能使用一次(三次校验）
        Boolean delete = stringRedisTemplate.delete(getRedisRefreshToken(refreshToken));
        if(!BooleanUtil.isTrue(delete)){
            return null;
        }

        //生成新的token
        String accessToken = this.createAccessToken(map);
        String newRefreshToken = this.createRefreshToken(map);
        return UserLoginVO.builder()
                .accessToken(accessToken)
                .refreshToken(newRefreshToken)
                .build();
    }

}
