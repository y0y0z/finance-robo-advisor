package org.example.finance.config;

import org.example.finance.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置
 *
 * 将 LoginInterceptor 注册到所有路由，
 * 并明确排除不需要鉴权的公开路径（登录/注册页、静态资源）。
 */
@Configuration
public class    WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")          // 拦截所有路径
                .excludePathPatterns(
                        "/login",                // 登录页面（GET + POST）
                        "/register",             // 注册页面（GET + POST）
                        "/css/**",               // 静态资源
                        "/js/**",
                        "/images/**",
                        "/favicon.ico",
                        "/error",                // Spring Boot 错误页
                        "/api/**"                // REST API（由 CorsConfig 处理跨域，Session 鉴权在 Controller 内做）
                );
    }
}
