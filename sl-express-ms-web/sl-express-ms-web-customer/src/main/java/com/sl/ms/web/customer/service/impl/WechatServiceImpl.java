package com.sl.ms.web.customer.service.impl;

import cn.hutool.json.JSONObject;
import com.sl.ms.web.customer.service.WechatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
public class WechatServiceImpl implements WechatService {

    @Override
    public JSONObject getOpenid(String code) throws IOException {
        return null;
    }

    @Override
    public String getPhone(String code) throws IOException {
        return null;
    }
}
