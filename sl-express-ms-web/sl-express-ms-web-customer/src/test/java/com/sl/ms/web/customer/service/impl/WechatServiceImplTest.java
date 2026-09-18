package com.sl.ms.web.customer.service.impl;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.io.IOException;

@SpringBootTest
class WechatServiceImplTest {

    @Resource
    WechatServiceImpl wechatService;

    @Test
    void getOpenid() {
        try {
            JSONObject openid = wechatService.getOpenid("0b3zva200fUW6X1gZk300CyAbB4zva2l");
            System.out.println("------------------------------------");
            System.out.println(openid);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getPhone() {
        try {
            String phone = wechatService.getPhone("92ddeee8e7833e495bd962009b1017448eb5ada21b43545a184fd3d2efebc324");
            System.out.println("---------------------------------");
            System.out.println(phone);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}