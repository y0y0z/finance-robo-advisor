package org.example.finance.vo;

import org.example.finance.model.Stock;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 股票列表视图对象
 * 将所有涨跌计算在 Controller 层完成，模板只负责展示，
 * 彻底规避在 Thymeleaf 表达式里做复杂数值运算。
 */
public class StockVO {

    // ===== 透传自 Stock 实体 =====
    private final Stock stock;

    // ===== 今日涨跌（较昨收，来自行情 API）=====
    private final String dailyChange;        // 如 "+1.23" / "-0.56" / "-"
    private final String dailyChangePct;     // 如 "+1.50%" / "-0.80%" / "-"
    private final String dailyDir;           // "up" / "down" / "flat"

    // ===== 持有涨跌（较加入关注时的价格）=====
    private final String holdChange;         // 如 "+2.50" / "-1.20" / "-"
    private final String holdChangePct;      // 如 "+3.25%" / "-1.50%" / "-"
    private final String holdDir;            // "up" / "down" / "flat"

    // ===== 当前价 =====
    private final String priceDisplay;       // 价格字符串，0 时显示 "待更新"

    public StockVO(Stock stock) {
        this.stock = stock;

        BigDecimal price    = stock.getPrice();
        BigDecimal change   = stock.getPriceChange();
        BigDecimal changePct = stock.getChangePercent();
        BigDecimal watchPr  = stock.getWatchPrice();

        // 当前价格
        boolean hasPrice = price != null && price.compareTo(BigDecimal.ZERO) > 0;
        this.priceDisplay = hasPrice ? price.toPlainString() : "待更新";

        // 今日涨跌
        if (change != null && hasPrice) {
            int cmp = change.compareTo(BigDecimal.ZERO);
            this.dailyDir       = cmp > 0 ? "up" : (cmp < 0 ? "down" : "flat");
            this.dailyChange    = cmp > 0 ? "+" + change.toPlainString() : change.toPlainString();
            String pctStr       = changePct != null ? changePct.toPlainString() : "0";
            this.dailyChangePct = cmp > 0 ? "+" + pctStr + "%" : pctStr + "%";
        } else {
            this.dailyDir       = "flat";
            this.dailyChange    = "-";
            this.dailyChangePct = "-";
        }

        // 持有涨跌（price 在此处已经过 hasPrice 保证非 null 且 > 0）
        if (hasPrice && watchPr != null && watchPr.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal safePrice = price;                        // 局部变量，消除 null 警告
            BigDecimal diff = safePrice.subtract(watchPr);
            BigDecimal pct  = diff.multiply(BigDecimal.valueOf(100))
                                  .divide(watchPr, 2, RoundingMode.HALF_UP);
            int cmp = diff.compareTo(BigDecimal.ZERO);
            this.holdDir       = cmp > 0 ? "up" : (cmp < 0 ? "down" : "flat");
            this.holdChange    = cmp > 0
                    ? "+" + diff.setScale(3, RoundingMode.HALF_UP).toPlainString()
                    :        diff.setScale(3, RoundingMode.HALF_UP).toPlainString();
            this.holdChangePct = cmp > 0 ? "+" + pct + "%" : pct + "%";
        } else {
            this.holdDir       = "flat";
            this.holdChange    = "-";
            this.holdChangePct = "-";
        }
    }

    // ===== Getters =====

    public Stock getStock()          { return stock; }
    public String getPriceDisplay()  { return priceDisplay; }

    public String getDailyChange()   { return dailyChange; }
    public String getDailyChangePct(){ return dailyChangePct; }
    public String getDailyDir()      { return dailyDir; }

    public String getHoldChange()    { return holdChange; }
    public String getHoldChangePct() { return holdChangePct; }
    public String getHoldDir()       { return holdDir; }
}
