package org.example.finance.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * 定投计划表
 * 记录用户设定的周期性定投计划（如每月固定日投入固定金额买某基金）
 */
@Data
@Entity
@Table(name = "dca_plans", indexes = {
        @Index(name = "idx_dca_plan_user", columnList = "user_id")
})
public class DcaPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String code;   // 基金/股票代码
    private String name;   // 名称

    /** 每期定投金额（元） */
    private BigDecimal amount;

    /**
     * 定投频率：MONTHLY / BIWEEKLY / WEEKLY
     */
    private String frequency;

    /**
     * 每月几号执行（frequency=MONTHLY 时有效，1-28）
     */
    private Integer dayOfMonth;

    /** 备注说明 */
    private String notes;

    /** 计划状态：ACTIVE / PAUSED */
    @Column(nullable = false)
    private String status = "ACTIVE";

    /** 计划开始日期 */
    private LocalDate startDate;

    /** 最近一次执行日期（用于计算下次到期） */
    private LocalDate lastExecuteDate;

    /** 关联的投资目标（可选） */
    @ManyToOne
    @JoinColumn(name = "goal_id")
    private InvestmentGoal goal;

    private Date createTime;
    private Date updateTime;
}
