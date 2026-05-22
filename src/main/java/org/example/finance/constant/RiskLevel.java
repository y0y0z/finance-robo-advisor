package org.example.finance.constant;

/**
 * 风险等级常量
 */
public final class RiskLevel {
    private RiskLevel() {}

    public static final String CONSERVATIVE = "CONSERVATIVE";
    public static final String BALANCED     = "BALANCED";
    public static final String AGGRESSIVE   = "AGGRESSIVE";

    /** 按评分计算风险等级 */
    public static String fromScore(int score) {
        if (score <= 30) return CONSERVATIVE;
        if (score <= 70) return BALANCED;
        return AGGRESSIVE;
    }

    /** 中文标签 */
    public static String label(String level) {
        return switch (level) {
            case CONSERVATIVE -> "保守型";
            case BALANCED     -> "稳健型";
            case AGGRESSIVE   -> "激进型";
            default           -> "未评估";
        };
    }
}
