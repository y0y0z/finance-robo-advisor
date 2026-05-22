package org.example.finance.vo;

/** 动态调整目标仓位时的原因说明 */
public record AdjustmentReason(
        String category,  // 市场情绪 / 持仓集中度 / 用户画像 / 综合
        String detail     // 具体说明
) {}
