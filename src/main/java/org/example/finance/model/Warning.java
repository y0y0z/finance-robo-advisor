package org.example.finance.model;

import lombok.Data;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 股票/基金预警表
 * 支持自动触发：定时任务会将股票实时价格与各阈值对比，自动更新状态
 */
@Data
@Entity
@Table(name = "warnings")
public class Warning {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user; // 关联用户，各用户管理自己的预警

    private String type;               // 股票、基金
    private String name;
    private String code;               // 股票/基金代码
    private BigDecimal warningPoint;   // 警告价格线
    private String meaning;            // 含义说明
    private BigDecimal stopProfitPoint; // 止盈价格线
    private BigDecimal stopLossPoint;   // 止损价格线

    /**
     * 预警状态：
     *   ACTIVE    - 监控中，未触发
     *   WARNING   - 价格触及警告线
     *   PROFIT    - 价格触及止盈线
     *   LOSS      - 价格触及止损线
     *   RESOLVED  - 用户已确认处理
     */
    @Column(nullable = false)
    private String status = "ACTIVE";

    private BigDecimal triggeredPrice; // 触发时的实际价格
    private Date triggeredTime;        // 触发时间

    /** 是否由 AI 智能引擎自动生成（false = 用户手动设置） */
    private Boolean aiGenerated = false;

    /** AI 生成预警时的分析理由（手动预警为 null） */
    @Column(columnDefinition = "TEXT")
    private String aiReason;

    private Date createTime;
    private Date updateTime;
}