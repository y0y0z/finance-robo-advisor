package org.example.finance.constant;

/**
 * 应用内所有页面路由路径常量
 * 供 Controller 中 redirect 使用，避免字符串硬编码
 */
public final class Routes {
    private Routes() {}

    // ===== 认证 =====
    public static final String LOGIN           = "/login";
    public static final String LOGIN_REGISTERED = "/login?registered=true";
    public static final String LOGOUT          = "/logout";
    public static final String REGISTER        = "/register";
    public static final String DASHBOARD       = "/dashboard";

    // ===== 资产 =====
    public static final String ASSETS          = "/assets";
    public static final String ASSETS_INIT     = "/assets/init";
    public static final String ASSETS_ADD      = "/assets/add";
    public static final String TRADE_RECORDS   = "/assets/{id}/trades";

    // ===== 股票/基金 =====
    public static final String STOCKS          = "/stocks";
    public static final String STOCKS_ADD      = "/stocks/add";
    public static final String STOCKS_DUPLICATE = "/stocks?duplicate=true";

    // ===== 预警 =====
    public static final String WARNINGS        = "/warnings";
    public static final String WARNINGS_ADD    = "/warnings/add";

    // ===== 新闻 =====
    public static final String NEWS            = "/news";

    // ===== AI =====
    public static final String AI_ADVICE         = "/ai-advice";
    public static final String AI_ADVICE_HISTORY = "/ai-advice/history";

    // ===== redirect 前缀工具方法 =====
    public static String redirectTo(String path) {
        return "redirect:" + path;
    }
}
