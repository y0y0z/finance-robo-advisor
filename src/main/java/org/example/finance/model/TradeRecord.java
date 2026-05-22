package org.example.finance.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 交易记录表
 * 记录每一笔买入/卖出操作，Asset.amount 和 purchasePrice 由此动态汇总
 */
@Data
@Entity
@Table(name = "trade_records", indexes = {
    @Index(name = "idx_trade_asset", columnList = "asset_id"),
    @Index(name = "idx_trade_user_date", columnList = "user_id, trade_date")
})
public class TradeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属资产（多条交易对应同一持仓） */
    @ManyToOne
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    /** 归属用户（冗余存储，方便直接按用户查询） */
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 交易方向：BUY（买入）/ SELL（卖出）
     */
    @Column(nullable = false, length = 4)
    private String direction;

    /** 本次交易数量（股/份） */
    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    /** 本次成交价格（元） */
    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal price;

    /** 本次交易金额 = quantity × price */
    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal amount;

    /** 交易手续费（元，默认0） */
    @Column(precision = 10, scale = 4)
    private BigDecimal fee = BigDecimal.ZERO;

    /** 交易日期（用户填写的实际成交日期） */
    @Column(nullable = false)
    private Date tradeDate;

    /** 备注（可选） */
    @Column(length = 200)
    private String remark;

    /** 记录创建时间 */
    private Date createdAt;
}
