package org.example.finance.constant;

/**
 * Model.addAttribute 的 key 常量
 * 与 Thymeleaf 模板中的变量名一一对应，避免字符串硬编码
 */
public final class ModelKeys {
    private ModelKeys() {}

    // ===== 通用 =====
    public static final String USER               = "user";
    public static final String ERROR              = "error";
    public static final String SUCCESS            = "success";

    // ===== 资产 =====
    public static final String ASSETS             = "assets";
    public static final String ASSET              = "asset";
    public static final String ALLOCATION         = "allocation";
    public static final String ASSET_ALLOCATION   = "assetAllocation";
    public static final String TOTAL_VALUE        = "totalValue";
    public static final String TOTAL_ASSET_VALUE  = "totalAssetValue";
    public static final String TOTAL_RETURN       = "totalReturn";
    public static final String UNREALIZED_RETURN  = "unrealizedReturn";
    public static final String REALIZED_RETURN    = "realizedReturn";
    public static final String TOTAL_COST         = "totalCost";
    public static final String RETURN_RATE        = "returnRate";
    public static final String HAS_ASSETS         = "hasAssets";

    // ===== 股票/基金 =====
    public static final String STOCKS             = "stocks";
    public static final String STOCK              = "stock";

    // ===== 预警 =====
    public static final String WARNINGS           = "warnings";
    public static final String WARNING            = "warning";
    public static final String TRIGGERED_WARNINGS = "triggeredWarnings";

    // ===== 新闻 =====
    public static final String NEWS               = "news";
    public static final String KEYWORDS           = "keywords";
    public static final String ACTIVE_KEYWORD     = "activeKeyword";

    // ===== 定投 =====
    public static final String DCA_PLANS          = "dcaPlans";
    public static final String DCA_DUE_PLANS      = "dcaDuePlans";
}
