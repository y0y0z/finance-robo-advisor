package org.example.finance.service;

import org.example.finance.model.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * 股票/基金价格定时更新服务
 *
 * 执行策略：
 *   1. 优先调用 Futu OpenD 更新股票/基金价格
 *   2. 若 API 调用失败，默认保留旧价格；仅在显式配置开启时使用随机模拟
 *   3. 每条记录之间休眠 300ms，避免请求频率过高被限流
 *
 * 更新完成后，AssetPriceUpdateService 和 WarningCheckService 将自动读取最新价格联动处理。
 */
@Service
public class StockPriceUpdateService {

    private static final Logger log = LoggerFactory.getLogger(StockPriceUpdateService.class);

    private final StockService stockService;
    private final MarketDataService marketDataService;

    @Value("${marketdata.random-fallback-enabled:false}")
    private boolean randomFallbackEnabled;

    private final Random random = new Random();

    public StockPriceUpdateService(StockService stockService, MarketDataService marketDataService) {
        this.stockService = stockService;
        this.marketDataService = marketDataService;
    }

    /** 每5分钟执行一次行情拉取与更新 */
    @Scheduled(fixedRateString = "${schedule.stock-price.fixed-rate}")
    public void updateStockPrices() {
        List<Stock> stocks = stockService.getAllStocks();
        if (stocks.isEmpty()) {
            log.debug("当前无任何股票/基金记录，跳过行情更新");
            return;
        }

        log.info("开始拉取行情，共 {} 条记录", stocks.size());
        int realSuccess = 0, fallback = 0;

        for (Stock stock : stocks) {
            boolean fromApi = fetchAndUpdate(stock);
            if (fromApi) realSuccess++; else fallback++;

            // 请求间隔 300ms，避免外部行情接口限流
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("行情更新完成：真实行情={} 条，降级模拟={} 条", realSuccess, fallback);
    }

    /**
     * 对单条 Stock 记录拉取行情并保存。
     * @return true=成功从 API 获取，false=保留旧价格或按配置降级模拟
     */
    private boolean fetchAndUpdate(Stock stock) {
        boolean apiSuccess;

        if ("基金".equals(stock.getType())) {
            // ── 基金：天天基金实时估值 API ──────────────────────────────
            apiSuccess = marketDataService.fetchFundPrice(stock);
        } else {
            // ── 股票：Futu OpenD 行情 ───────────────────────────────────
            apiSuccess = marketDataService.fetchStockPrice(stock);
        }

        if (!apiSuccess) {
            if (randomFallbackEnabled) {
                fallbackRandomUpdate(stock);
            } else {
                log.debug("{}({}) 行情获取失败，保留原价格", stock.getName(), stock.getCode());
            }
        }

        stock.setUpdateTime(new Date());
        stockService.saveStock(stock);

        return apiSuccess;
    }

    /**
     * 降级处理：在现有价格基础上随机 ±2% 波动
     * （仅用于 API 不可用的非交易时段或网络异常时，保持数据活跃）
     */
    private void fallbackRandomUpdate(Stock stock) {
        BigDecimal currentPrice = stock.getPrice();

        // 历史上从未有过价格：赋初值 10.00 等待下次真实行情
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            stock.setPrice(new BigDecimal("10.00"));
            stock.setPriceChange(BigDecimal.ZERO);
            stock.setChangePercent(BigDecimal.ZERO);
            log.debug("{}({}) 首次创建，设置初始价格 10.00，等待下次真实行情", stock.getName(), stock.getCode());
            return;
        }

        // 随机 ±2%
        double pct = (random.nextDouble() - 0.5) * 4;
        BigDecimal changePct = new BigDecimal(pct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal priceChange = currentPrice
                .multiply(changePct)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal newPrice = currentPrice.add(priceChange);
        if (newPrice.compareTo(BigDecimal.ZERO) <= 0) newPrice = new BigDecimal("0.01");

        stock.setPrice(newPrice);
        stock.setPriceChange(priceChange);
        stock.setChangePercent(changePct);
        log.debug("{}({}) API 获取失败，使用随机模拟：价格={}", stock.getName(), stock.getCode(), newPrice);
    }

}
