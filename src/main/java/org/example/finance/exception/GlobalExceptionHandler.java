package org.example.finance.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * 全局异常处理器
 * <p>
 * 对 REST 接口（路径以 /api/ 开头）返回 JSON 错误体；
 * 对页面请求重定向到来源页面并通过 FlashAttribute 传递错误提示。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务规则异常（用户操作非法） */
    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleIllegalArgument(IllegalArgumentException e,
                                        HttpServletRequest request,
                                        RedirectAttributes ra) {
        log.warn("非法参数 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getMessage());

        if (isApiRequest(request)) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
        ra.addFlashAttribute("error", e.getMessage());
        return new ModelAndView("redirect:" + referer(request));
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public Object handleUnexpected(Exception e,
                                   HttpServletRequest request,
                                   RedirectAttributes ra) {
        log.error("未预期异常 [{} {}]", request.getMethod(), request.getRequestURI(), e);

        if (isApiRequest(request)) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "服务器内部错误，请稍后重试"));
        }
        ra.addFlashAttribute("error", "系统异常，请稍后重试");
        return new ModelAndView("redirect:" + referer(request));
    }

    private boolean isApiRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }

    private String referer(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        return (referer == null || referer.isBlank()) ? "/dashboard" : referer;
    }
}
