package com.sl.ms.web.driver.service.impl;

import com.itheima.auth.sdk.AuthTemplate;
import com.itheima.auth.sdk.common.Result;
import com.itheima.auth.sdk.common.Token;
import com.itheima.auth.sdk.dto.LoginDTO;
import com.sl.ms.web.driver.service.LoginService;
import com.sl.ms.web.driver.vo.request.AccountLoginVO;
import com.sl.transport.common.exception.SLWebException;
import com.sl.transport.common.util.ObjectUtil;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class LoginServiceImpl implements LoginService {
    @Resource
    private AuthTemplate authTemplate;

    @Override
    public String accountLogin(AccountLoginVO accountLoginVO) {
        Result<LoginDTO> result = authTemplate.opsForLogin().token(accountLoginVO.getAccount(), accountLoginVO.getPassword());
        if(ObjectUtil.equal(result.getCode(),Result.success().getCode())){
            return result.getData().getToken().getToken();
        }
        throw new SLWebException("登录失败");
    }
}
