package com.sl.ms.web.customer.service.impl;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sl.ms.web.customer.service.WechatService;
import com.sl.transport.common.exception.SLWebException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class WechatServiceImpl implements WechatService {
    @Value("${sl.wechat.appid}")
    private String appid;
    @Value("${sl.wechat.secret}")
    private String secret;

    private final StringRedisTemplate stringRedisTemplate;

    private static final String LOGIN_URL="https://api.weixin.qq.com/sns/jscode2session";
    private static final String TOKEN_URL="https://api.weixin.qq.com/cgi-bin/token";
    private static final String PHONE_URL="https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=";

    private static final String LOGIN_ERRMSG="调用微信登录接口异常";
    private static final String TOKEN_ERRMSG="调用获取接口调用凭据接口出错";
    private static final String PHONE_ERRMSG="调用获取手机号接口出错";

    private static final String REDIS_ACCESS_TOKEN="wx:access_token";

    private static final int TIMEOUT = 20000;

    // 获取用户的微信唯一标识 openid 及 session_key
    @Override
    public JSONObject getOpenid(String code) throws IOException {
        //1. 封装参数
        Map<String, Object> requestParam = MapUtil.<String, Object>builder()
                .put("appid", appid)
                .put("secret", secret)
                .put("js_code", code)
                .put("grant_type", "authorization_code")
                .build();
        //2. 发送Http请求
        HttpResponse response = HttpRequest.get(LOGIN_URL)
                .form(requestParam)
                .timeout(TIMEOUT)
                .execute();
        //3. 解析响应的结果，如果出现错误则抛出异常
        return checkResponse(response, LOGIN_ERRMSG);
    }

    @Override
    public String getPhone(String code) throws IOException {
        //1. 获取access_token
        String accessToken = this.getToken();
        //2. 封装请求参数
        Map<String, Object> request = MapUtil.<String, Object>builder()
                .put("code", code)
                .build();
        //3. 发送Http请求
        HttpResponse response = HttpRequest.post(PHONE_URL + accessToken)
                .body(JSONUtil.toJsonStr(request))
                .timeout(TIMEOUT)
                .execute();
        //4. 解析结果
        JSONObject jsonObject = checkResponse(response, PHONE_ERRMSG);
        return jsonObject.getByPath("phone_info.purePhoneNumber",String.class);
    }


    //获取access_token
    private String getToken(){
        //检测redis中是否有access_token
        String accessToken = stringRedisTemplate.opsForValue().get(REDIS_ACCESS_TOKEN);
        if(ObjectUtil.isNotEmpty(accessToken)){
            return accessToken;
        }
            //1. 拼接请求参数
            Map<String, Object> request = MapUtil.<String, Object>builder()
                    .put("appid", appid)
                    .put("secret", secret)
                    .put("grant_type", "client_credential")
                    .build();
            //2. 发送请求
            HttpResponse response = HttpRequest.get(TOKEN_URL)
                    .form(request)
                    .timeout(TIMEOUT)
                    .execute();
            //3. 解析结果
            JSONObject jsonObject = checkResponse(response, TOKEN_ERRMSG);
            String newAccessToken = jsonObject.getStr("access_token");
            //将获取的access_token保存到redis中
            Long expiresIn = jsonObject.getLong("expires_in");   // 7200 秒
            // 提前 5 分钟过期，避免"边界使用已失效的token"
            stringRedisTemplate.opsForValue().set(REDIS_ACCESS_TOKEN, newAccessToken, expiresIn - 300, TimeUnit.SECONDS);
            return newAccessToken;
    }

    private JSONObject checkResponse(HttpResponse response, String errMsg) {
        if (!response.isOk()) {
            throw new SLWebException(errMsg);
        }
        JSONObject jsonObject = JSONUtil.parseObj(response.body());
        Integer errcode = jsonObject.getInt("errcode");
        if (ObjectUtil.isNotEmpty(errcode) && ObjectUtil.notEqual(errcode, 0)) {
            log.error("{}出错, errcode = {}, errmsg = {}", errMsg, errcode, jsonObject.getStr("errmsg"));
            throw new SLWebException(errMsg);
        }
        return jsonObject;
    }
}
