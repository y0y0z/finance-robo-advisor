package org.example.finance.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日净值快照（每天定时记录一次总市值和总成本）
 */
@Data
@Entity
@Table(name = "net_value_snapshots",
       indexes = @Index(name = "idx_snapshot_user_date", columnList = "user_id, snapshot_date"))
public class NetValueSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** 当日总市值 */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalValue;

    /** 当日总成本 */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalCost;
}
