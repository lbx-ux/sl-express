package com.sl.ms.web.driver.service.impl;

import com.itheima.auth.sdk.AuthTemplate;
import com.itheima.auth.sdk.common.Result;
import com.itheima.auth.sdk.dto.LoginDTO;
import com.sl.ms.web.driver.service.LoginService;
import com.sl.ms.web.driver.vo.request.AccountLoginVO;
import com.sl.transport.common.exception.SLWebException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class LoginServiceImpl implements LoginService {
    @Resource
    private AuthTemplate authTemplate;

    @Override
    public String accountLogin(AccountLoginVO accountLoginVO) {
        //TODO 待完成
        return null;
    }
}
