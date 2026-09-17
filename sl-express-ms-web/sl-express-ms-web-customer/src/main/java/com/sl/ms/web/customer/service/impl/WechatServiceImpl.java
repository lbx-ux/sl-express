package com.sl.ms.web.customer.service.impl;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sl.ms.web.customer.service.WechatService;
import com.sl.transport.common.exception.SLWebException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Map;

@Service
@Slf4j
public class WechatServiceImpl implements WechatService {
    @Value("${sl.wechat.appid}")
    private String appid;
    @Value("${sl.wechat.secret}")
    private String secret;

    private static final String LOGIN_URL="https://api.weixin.qq.com/sns/jscode2session";
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
        if(!response.isOk()){
            throw new SLWebException("调用微信登录接口异常");
        }
        JSONObject jsonObject = JSONUtil.parseObj(response.body());
        Integer errcode = jsonObject.getInt("errcode");
        // 必须要验证errcode是否为空 和 是否不是0  因为在成功时，errcode可能为0，也可能不传值，所以必须要校验一下
        if(ObjectUtil.isNotEmpty(errcode) && ObjectUtil.notEqual(errcode, 0)){
            log.error("调用微信登录接口异常, errcode = {}, errmsg = {}", errcode, jsonObject.getStr("errmsg"));
            throw new SLWebException("调用微信登录接口异常");
        }
        return jsonObject;
    }

    @Override
    public String getPhone(String code) throws IOException {
        return null;
    }
}
