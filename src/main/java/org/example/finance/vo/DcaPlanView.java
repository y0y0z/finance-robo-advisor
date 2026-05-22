package org.example.finance.vo;

import org.example.finance.model.DcaPlan;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 定投计划列表的展示 VO，预先在 Service 层计算好统计字段，
 * 避免模板直接调用 Service 方法造成分层污染。
 */
public record DcaPlanView(
        DcaPlan plan,
        boolean due,
        LocalDate nextDueDate,
        BigDecimal totalInvested,
        int totalPeriods,
        BigDecimal avgCost
) {}
