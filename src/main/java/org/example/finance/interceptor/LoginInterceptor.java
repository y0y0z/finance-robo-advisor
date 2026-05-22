package org.example.finance.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.finance.model.User;
import org.example.finance.constant.SessionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.finance.service.UserProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录鉴权拦截器
 * 未登录 → /login
 * 已登录但未填写风险画像 → /profile/setup（排除 /profile/setup 本身）
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    @Autowired
    private UserProfileService userProfileService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        User user = (User) request.getSession().getAttribute(SessionKeys.USER);
        String uri = request.getRequestURI();

        if (user == null) {
            log.debug("未登录访问 [{}]，重定向至登录页", uri);
            if (uri.startsWith("/api/")) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
            } else {
                response.sendRedirect(request.getContextPath() + "/login");
            }
            return false;
        }

        // 已登录但未填写风险画像 → 强制跳转到画像填写页
        if (!uri.startsWith("/profile") && !uri.startsWith("/goals")
                && !uri.startsWith("/api/") && !uri.startsWith("/logout")) {
            if (!userProfileService.hasProfile(user)) {
                response.sendRedirect(request.getContextPath() + "/profile/setup");
                return false;
            }
        }

        return true;
    }
}
