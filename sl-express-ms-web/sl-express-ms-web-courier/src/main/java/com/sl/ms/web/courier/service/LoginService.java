package com.sl.ms.web.courier.service;


import com.sl.ms.web.courier.vo.login.AccountLoginVO;
import com.sl.ms.web.courier.vo.login.LoginVO;

public interface LoginService {

    /**
     * 根据用户名和密码进行登录
     *
     * @param accountLoginVO 登录信息
     */
    LoginVO accountLogin(AccountLoginVO accountLoginVO);
}
