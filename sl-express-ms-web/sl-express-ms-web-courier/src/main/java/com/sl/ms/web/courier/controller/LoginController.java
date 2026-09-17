package com.sl.ms.web.courier.controller;

import com.sl.ms.web.courier.vo.login.AccountLoginVO;
import com.sl.ms.web.courier.vo.login.LoginVO;
import com.sl.transport.common.vo.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "登录相关接口")
@RestController
@RequestMapping("/login")
public class LoginController {

    @ApiOperation(value = "账号登录", notes = "登录")
    @PostMapping(value = "/account")
    public R<LoginVO> accountLogin(@RequestBody AccountLoginVO accountLoginVO) {
        //TODO 待实现
        return null;
    }
}
