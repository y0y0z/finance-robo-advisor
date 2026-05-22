package org.example.finance.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * 定投执行记录表
 * 每次实际执行定投时创建一条记录，同时联动生成 TradeRecord（BUY）
 */
@Data
@Entity
@Table(name = "dca_records", indexes = {
        @Index(name = "idx_dca_record_plan", columnList = "plan_id"),
        @Index(name = "idx_dca_record_user", columnList = "user_id")
})
public class DcaRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "plan_id", nullable = false)
    private DcaPlan plan;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 本次实际投入金额（元） */
    private BigDecimal amount;

    /** 本次购买净值/价格（元/份） */
    private BigDecimal price;

    /** 本次买入份额（= amount / price，ROUND_DOWN 保留2位） */
    private BigDecimal shares;

    /** 实际执行日期 */
    private LocalDate executeDate;

    /** 备注 */
    private String notes;

    /** 是否由系统自动执行（true=自动定投，false=手动录入） */
    private boolean auto;

    private Date createTime;
}
