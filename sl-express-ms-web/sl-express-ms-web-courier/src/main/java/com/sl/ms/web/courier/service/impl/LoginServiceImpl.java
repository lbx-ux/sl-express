package com.sl.ms.web.courier.service.impl;

import com.itheima.auth.sdk.AuthTemplate;
import com.itheima.auth.sdk.common.Result;
import com.itheima.auth.sdk.dto.LoginDTO;
import com.sl.ms.web.courier.service.LoginService;
import com.sl.ms.web.courier.vo.login.AccountLoginVO;
import com.sl.ms.web.courier.vo.login.LoginVO;
import com.sl.transport.common.exception.SLWebException;
import com.sl.transport.common.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class  LoginServiceImpl implements LoginService {
    private final AuthTemplate authTemplate;

    /**
     * 根据用户名和密码进行登录
     *
     * @param accountLoginVO 登录信息
     */
    @Override
    public LoginVO accountLogin(AccountLoginVO accountLoginVO) {
        Result<LoginDTO> result = authTemplate.opsForLogin().token(accountLoginVO.getAccount(), accountLoginVO.getPassword());
        if(ObjectUtil.equal(result.getCode(),Result.success().getCode())){
            return new LoginVO(result.getData().getToken().getToken());
        }
        throw new SLWebException("登录失败");
    }
}
