package org.example.finance.vo;

/** 集中度风险警告 */
public record ConcentrationWarning(
        String name,      // 标的名称或资产类别
        String type,      // ASSET（单标的）或 TYPE（类别）
        double pct,       // 当前占比 %
        String severity   // WARNING / DANGER / OVERWEIGHT
) {}
