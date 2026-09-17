package com.sl.ms.web.manager.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.generator.MathGenerator;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.google.common.net.HttpHeaders;
import com.itheima.auth.sdk.common.Result;
import com.itheima.auth.sdk.common.Token;
import com.itheima.auth.sdk.dto.*;
import com.sl.ms.base.api.common.WorkSchedulingFeign;
import com.sl.ms.base.domain.base.WorkSchedulingDTO;
import com.sl.ms.base.domain.enums.StatusEnum;
import com.sl.ms.base.domain.enums.WorkUserTypeEnum;
import com.sl.ms.web.manager.service.AuthService;
import com.sl.ms.web.manager.vo.agency.AgencySimpleVO;
import com.sl.ms.web.manager.vo.auth.CourierVO;
import com.sl.ms.web.manager.vo.auth.SysUserVO;
import com.sl.transport.common.exception.SLWebException;
import com.sl.transport.common.util.AuthTemplateThreadLocal;
import com.sl.transport.common.util.PageResponse;
import com.sl.transport.common.vo.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 鉴权服务
 * 登录 验证码 员工列表 快递员列表 角色
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    @Value("${role.courier}")
    private String roleId;

    private static final String CAPTCHA_KEY = "captcha_key:";

    private final WorkSchedulingFeign workSchedulingFeign;
    private final StringRedisTemplate stringRedisTemplate;



    /**
     * 登录
     *
     * @param login 用户登录信息
     * @return 登录结果
     */
    @Override
    public LoginDTO login(LoginParamDTO login) {
        //1. 对参数进行校验
        if(StrUtil.isBlank(login.getCode())){
            throw new SLWebException("验证码不能为空");
        }
        if(StrUtil.isBlank(login.getAccount())){
            throw new SLWebException("账户不能为空");
        }
        if(StrUtil.isBlank(login.getPassword())){
            throw new SLWebException("密码不能为空");
        }

        //2. 校验验证码
        String key = CAPTCHA_KEY + login.getKey();
        String value = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(value)){
            throw new SLWebException("验证码已过期");
        }
        // 验证码只能使用一次，所以在使用过验证码之后一定要删除验证码
        stringRedisTemplate.delete(key);
        boolean verify = new MathGenerator().verify(value, login.getCode());
        if(!verify){
            throw new SLWebException("验证码输入错误");
        }
        //3. 校验用户名和密码，校验通过生成token
        return login(login.getAccount(),login.getPassword());
    }

    /**
     * 登录获取token
     *
     * @param account  账号
     * @param password 密码
     * @return 登录信息
     */
    @Override
    public LoginDTO login(String account, String password) {
        //说明：由于后台系统的账号在后面会由【权限管家】系统中管理，由于【权限管家】目前还没学习，所以这里的登录先做【模拟实现】
        if (!(StrUtil.equals(account, "sl") && StrUtil.equals(password, "123"))) {
            throw new SLWebException("用户名或密码错误");
        }

        LoginDTO loginDTO = new LoginDTO();

        //设置token
        Token token = new Token();
        token.setToken("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMDI0NzA1NzA5MjU1NzczMzQ1IiwiYWNjb3VudCI6InNoZW5saW5nYWRtaW4iLCJuYW1lIjoi56We6aKG566h55CG5ZGYIiwib3JnaWQiOjEwMjQ3MDQ4NDQ0ODY3NTY2NDEsInN0YXRpb25pZCI6MTAyNDcwNTQ4OTQzNjQ5NDcyMSwiYWRtaW5pc3RyYXRvciI6ZmFsc2UsImV4cCI6MTY4MDc5NjE5OX0.W4RrB4p5YmjgEcdyGbbL4UrdWFirFbUu_e8Pgwxgr6vBVnj5z40JcFG4X3nIbrIXcSXUldi6oEuNfqAtZ9dUUw");
        token.setExpire(9999);
        loginDTO.setToken(token);

        //设置用户信息
        UserDTO userDTO = new UserDTO();
        userDTO.setAccount(account);
        userDTO.setName("神领管理员");
        //其它属性暂时不设置
        loginDTO.setUser(userDTO);

        return loginDTO;
    }

    @Override
    public void createCaptcha(String key, HttpServletResponse response) throws IOException {
        //生成验证码
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(115, 42,4,4);
        captcha.setGenerator(new MathGenerator(1));
        String code = captcha.getCode();

        //将验证码的值写入redis，有效期为1分钟
        String redisKey = CAPTCHA_KEY + key;
        stringRedisTemplate.opsForValue().set(redisKey,code, 1, TimeUnit.MINUTES);

        // 输出到页面，设置页面不缓存
        response.setHeader(HttpHeaders.PRAGMA, "No-cache");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "No-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0L);
        captcha.write(response.getOutputStream());
    }

    @Override
    public boolean check(String key, String value) {
        //TODO 待实现
        return false;
    }

    /**
     * 转换用户
     *
     * @param userDTO 用户DTO
     * @return 用户VO
     */
    @Override
    public SysUserVO parseUser2Vo(UserDTO userDTO) {
        SysUserVO vo = new SysUserVO();
        //填充基本信息
        vo.setUserId(userDTO.getId());
        vo.setAvatar(userDTO.getAvatar());
        vo.setEmail(userDTO.getEmail());
        vo.setMobile(userDTO.getMobile());
        vo.setAccount(userDTO.getAccount());
        vo.setName(userDTO.getName());
        vo.setStatus(userDTO.isStatus() ? StatusEnum.NORMAL.getCode() : StatusEnum.DISABLED.getCode());

        //处理所属机构信息
        AgencySimpleVO agency = new AgencySimpleVO();
        agency.setName(userDTO.getOrgName());
        vo.setAgency(agency);

        //处理岗位信息
        vo.setStationName(userDTO.getStationName());
        // 角色
        vo.setRoleNames(userDTO.getRoleNames());
        return vo;
    }

    /**
     * 获取用户信息
     *
     * @param id 用户id
     * @return 执行结果
     */
    @Override
    public SysUserVO user(Long id) {
        Result<UserDTO> result = AuthTemplateThreadLocal.get().opsForUser().getUserById(id);
        if (result.getCode() != 0) {
            return new SysUserVO();
        }
        return parseUser2Vo(result.getData());
    }

    /**
     * 批量获取用户信息
     *
     * @param ids 用户id
     * @return 执行结果
     */
    @Override
    public List<SysUserVO> users(List<Long> ids) {
        List<Long> longList = ids.stream().filter(Objects::nonNull).collect(Collectors.toList());
        Result<List<UserDTO>> result = AuthTemplateThreadLocal.get().opsForUser().list(longList);
        if (result.getCode() != 0) {
            return new ArrayList<>();
        }
        return result.getData().parallelStream().map(this::parseUser2Vo).collect(Collectors.toList());
    }

    /**
     * 员工分页
     *
     * @param page     页数
     * @param pageSize 页大小
     * @param agencyId 机构ID
     * @return 员工列表
     */
    @Override
    public PageResponse<SysUserVO> findUserByPage(Integer page, Integer pageSize, Long agencyId, String account, String name, String mobile) {
        Result<PageDTO<UserDTO>> result = AuthTemplateThreadLocal.get().opsForUser().getUserByPage(new UserPageDTO(page, pageSize, account, name, ObjectUtil.isNotEmpty(agencyId) ? agencyId : null, mobile));
        return getPageResponseR(page, pageSize, result);
    }

    /**
     * 快递员分页
     *
     * @param page     页数
     * @param pageSize 页大小
     * @param name     名称
     * @param mobile   手机号
     * @return 快递员列表
     */
    @Override
    public PageResponse<SysUserVO> findCourierByPage(Integer page, Integer pageSize, String name, String mobile, String account, Long orgId) {
        UserPageDTO userPageDTO = new UserPageDTO(page, pageSize, account, name, orgId, mobile);
        userPageDTO.setRoleId(roleId);
        Result<PageDTO<UserDTO>> result = AuthTemplateThreadLocal.get().opsForUser().getUserByPage(userPageDTO);

        // 转换vo
        PageResponse<SysUserVO> pageResponseR = getPageResponseR(page, pageSize, result);
        if (CollUtil.isEmpty(pageResponseR.getItems())) {
            return pageResponseR;
        }

        List<Long> userIds = pageResponseR.getItems().parallelStream().map(SysUserVO::getUserId).collect(Collectors.toList());
        if (CollUtil.isEmpty(userIds)) {
            return pageResponseR;
        }

        // 补充数据
        String bidStr = CollUtil.isEmpty(userIds) ? "" : CharSequenceUtil.join(",", userIds);
        List<WorkSchedulingDTO> workSchedulingDTOS = workSchedulingFeign.monthSchedule(bidStr, null, WorkUserTypeEnum.COURIER.getCode(), LocalDateTimeUtil.toEpochMilli(LocalDateTimeUtil.now()));
        if (CollUtil.isEmpty(workSchedulingDTOS)) {
            return pageResponseR;
        }
        Map<Long, Boolean> workMap = workSchedulingDTOS.parallelStream().filter(workSchedulingDTO -> ObjectUtil.isNotEmpty(workSchedulingDTO.getWorkSchedules())).collect(Collectors.toMap(WorkSchedulingDTO::getUserId, workSchedulingDTO -> workSchedulingDTO.getWorkSchedules().get(0)));

        pageResponseR.getItems().parallelStream().forEach(userDTO -> {
            // 上班状态
            try {
                Boolean aBoolean = workMap.get(userDTO.getUserId());
                if (ObjectUtil.isNotEmpty(aBoolean)) {
                    userDTO.setWorkStatus(aBoolean ? 1 : 0);
                }
            } catch (Exception ignored) {
                log.info("Exception:{}", ignored.getMessage());
            }
        });
        return pageResponseR;
    }

    /**
     * 转换用户返回结果
     *
     * @param page     页数
     * @param pageSize 页大小
     * @param result   用户信息
     * @return 用户信息
     */
    private PageResponse<SysUserVO> getPageResponseR(@RequestParam(name = "page") Integer page, @RequestParam(name = "pageSize") Integer pageSize, Result<PageDTO<UserDTO>> result) {
        if (result.getCode() == 0 && ObjectUtil.isNotEmpty(result.getData())) {
            PageDTO<UserDTO> userPage = result.getData();
            //处理对象转换
            List<SysUserVO> voList = userPage.getRecords().parallelStream().map(this::parseUser2Vo).collect(Collectors.toList());
            return PageResponse.of(voList, page, pageSize, userPage.getTotal() % userPage.getSize(), userPage.getTotal());
        }
        return PageResponse.getInstance();
    }

    /**
     * 根据机构查询快递员
     *
     * @param agencyId 机构id
     * @return 快递员列表
     */
    @Override
    public List<CourierVO> findByAgencyId(Long agencyId) {
        //构件查询条件
        UserPageDTO userPageDTO = new UserPageDTO(1, 999, null, null, agencyId, null);
        userPageDTO.setRoleId(roleId);

        //分页查询
        Result<PageDTO<UserDTO>> result = AuthTemplateThreadLocal.get().opsForUser().getUserByPage(userPageDTO);
        if (ObjectUtil.isEmpty(result.getData().getRecords())) {
            return Collections.emptyList();
        }

        //组装响应结果
        return result.getData().getRecords().stream().map(userDTO -> BeanUtil.toBean(userDTO, CourierVO.class)).collect(Collectors.toList());
    }


}
