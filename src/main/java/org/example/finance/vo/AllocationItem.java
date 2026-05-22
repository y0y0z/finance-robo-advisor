package org.example.finance.vo;

import java.math.BigDecimal;

/** 单个资产类别的仓位对比数据 */
public record AllocationItem(
        String type,          // 股票 / 基金 / 现金
        BigDecimal current,   // 当前占比 %
        int target,           // 目标占比 %
        BigDecimal delta      // target - current，正=需增加，负=需减少
) {}
