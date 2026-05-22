package org.example.finance.model;

import lombok.Data;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Entity
@Table(name = "stocks")
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联用户：每条关注记录属于某个用户，实现用户隔离 */
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private String code;
    private String name;
    private String type;          // 股票 / 基金
    private BigDecimal price;

    /** 较前一日的涨跌额（由行情 API 提供） */
    private BigDecimal priceChange;
    /** 较前一日的涨跌幅 %（由行情 API 提供） */
    private BigDecimal changePercent;

    /** 加入关注时的价格（计算持有期收益用） */
    private BigDecimal watchPrice;
    /** 加入关注的时间 */
    private Date watchTime;

    private Date updateTime;
    private String market;        // 沪市 / 深市 / 科创板 / 基金
    private BigDecimal pe;        // 市盈率
    private BigDecimal pb;        // 市净率
    private BigDecimal nav;       // 基金净值
}

