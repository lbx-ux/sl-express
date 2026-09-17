package com.sl.ms.web.customer.config;

import com.sl.transport.common.interceptor.UserInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * 配置拦截器
 * 包含用户信息拦截器
 */
@Configuration
@Slf4j
public class CustomerWebConfig implements WebMvcConfigurer {

    //拦截的时候过滤掉swagger相关路径和登录相关接口
    private static final String[] EXCLUDE_PATH_PATTERNS = new String[]{"/swagger-ui.html",
            "/webjars/**",
            "/swagger-resources",
            "/v2/api-docs",
            "/user/login/**",
            "/user/refresh/**"};
    @Resource
    private UserInterceptor userInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 用户信息拦截器
        registry.addInterceptor(userInterceptor).excludePathPatterns(EXCLUDE_PATH_PATTERNS).addPathPatterns("/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") //对所有的请求有效
                .allowedOriginPatterns("*") //对所有的域名都放行
                .allowedHeaders("*") //放行所有的请求头信息
                .allowCredentials(true) //是否发送cookie
                .allowedMethods("*"); //放行所有的请求方法
    }
}