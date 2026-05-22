package org.example.finance.model;

import lombok.Data;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 个人资产表
 */
@Data
@Entity
@Table(name = "assets", indexes = {
    @Index(name = "idx_asset_user", columnList = "user_id"),
    @Index(name = "idx_asset_code", columnList = "code")
})
public class Asset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * 资产类型
     */
    private String type;

    /**
     * 资产名
     */
    private String name;

    /**
     * 资产代码
     */
    private String code;

    /**
     * 持有数量（股票/基金单位：股/份；现金单位：元）
     * 注：现金类资产 purchasePrice 固定为 1.0，amount 即为现金金额
     */
    private BigDecimal amount;

    /**
     * 购买价格
     */
    private BigDecimal purchasePrice;

    /**
     * 购买日期
     */
    private Date purchaseDate;

    /**
     * 当前价格
     */
    private BigDecimal currentPrice;

    /**
     * 总价值
     */
    private BigDecimal totalValue;
}
