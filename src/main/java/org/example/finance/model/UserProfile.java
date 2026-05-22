package org.example.finance.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户投资画像
 * 1:1 关联 User，用于风险评估与目标仓位计算
 */
@Data
@Entity
@Table(name = "user_profiles", indexes = {
        @Index(name = "idx_user_profile_user", columnList = "user_id", unique = true)
})
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** 年龄 */
    private int age;

    /** 年收入（元） */
    @Column(precision = 18, scale = 2)
    private BigDecimal annualIncome;

    /** 月结余（元） */
    @Column(precision = 18, scale = 2)
    private BigDecimal monthlySavings;

    /** 投资经验：NONE / BEGINNER / INTERMEDIATE / EXPERT */
    private String investmentExperience;

    /** 最大可接受亏损百分比，如 15 表示 15% */
    private int maxLossPercent;

    /** 投资目标：WEALTH_GROWTH / INCOME / PRESERVATION / SPECULATION */
    private String investmentGoal;

    /** 流动性需求：HIGH / MEDIUM / LOW */
    private String liquidityNeed;

    /** 偏好资产（逗号分隔，如 "股票,基金"） */
    private String preferredAssets;

    /** 投资期限：SHORT(<1年) / MEDIUM(1-5年) / LONG(>5年) */
    private String investmentHorizon;

    /** 风险评分 0-100，后端根据问卷计算 */
    private int riskScore;

    /** 风险等级：CONSERVATIVE / BALANCED / AGGRESSIVE */
    private String riskLevel;

    /** 创建/更新时间 */
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
