package org.example.finance.constant;

/**
 * Thymeleaf 视图模板名称常量
 * 供 Controller 中 return 视图名使用，避免字符串硬编码
 */
public final class Views {
    private Views() {}

    // ===== 认证 =====
    public static final String REGISTER        = "auth/register";
    public static final String LOGIN           = "auth/login";

    // ===== 主要页面 =====
    public static final String DASHBOARD              = "dashboard";
    public static final String AI_ADVICE              = "ai-advice";
    public static final String AI_ADVICE_HISTORY      = "ai-advice-history";
    public static final String AI_ADVICE_HISTORY_DETAIL = "ai-advice-history-detail";

    // ===== 资产 =====
    public static final String ASSETS          = "assets";
    public static final String ASSETS_INIT     = "assets-init";
    public static final String ASSETS_ADD      = "assets-add";
    public static final String ASSETS_EDIT     = "assets-edit";
    public static final String TRADE_RECORDS   = "trade-records";
    public static final String TRADE_BUY       = "trade-buy";
    public static final String TRADE_SELL      = "trade-sell";

    // ===== 股票/基金 =====
    public static final String STOCKS          = "stocks";
    public static final String STOCKS_ADD      = "stocks-add";
    public static final String STOCKS_EDIT     = "stocks-edit";

    // ===== 预警 =====
    public static final String WARNINGS        = "warnings";
    public static final String WARNINGS_ADD    = "warnings-add";
    public static final String WARNINGS_EDIT   = "warnings-edit";

    // ===== 新闻 =====
    public static final String NEWS            = "news";
}
