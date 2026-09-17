package com.sl.ms.web.courier.service.impl;

import com.sl.ms.web.courier.service.LoginService;
import com.sl.ms.web.courier.vo.login.AccountLoginVO;
import com.sl.ms.web.courier.vo.login.LoginVO;
import com.sl.transport.common.vo.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LoginServiceImpl implements LoginService {

    /**
     * 根据用户名和密码进行登录
     *
     * @param accountLoginVO 登录信息
     * @return token
     */
    @Override
    public R<LoginVO> accountLogin(AccountLoginVO accountLoginVO) {
        //TODO 待实现
        return null;
    }
}
