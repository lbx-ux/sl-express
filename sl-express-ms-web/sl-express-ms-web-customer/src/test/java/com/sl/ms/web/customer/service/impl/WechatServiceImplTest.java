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
            JSONObject openid = wechatService.getOpenid("0e3TNGkl2BtMri4mfDll2rLLGF4TNGkK");
            System.out.println("------------------------------------");
            System.out.println(openid);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getPhone() {
        try {
            wechatService.getPhone("98b024c7236c108003a5ebc2c7ea59be30636059395899389f82960d07e85fcc");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}