package org.example.finance.service;

import org.example.finance.model.Asset;
import org.example.finance.model.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AssetPriceUpdateService {

    private static final Logger log = LoggerFactory.getLogger(AssetPriceUpdateService.class);

    private final AssetService assetService;
    private final StockService stockService;

    public AssetPriceUpdateService(AssetService assetService, StockService stockService) {
        this.assetService = assetService;
        this.stockService = stockService;
    }

    /**
     * 每5分钟同步一次资产当前价格（延迟10秒，等 StockPriceUpdateService 先跑完）
     */
    @Scheduled(fixedRateString = "${schedule.asset-price.fixed-rate}", initialDelayString = "${schedule.asset-price.initial-delay}")
    public void updateAssetPrices() {
        List<Asset> assets = assetService.getAllAssets();
        log.info("开始同步资产当前价格，共 {} 条", assets.size());

        for (Asset asset : assets) {
            updateAssetPrice(asset);
        }

        log.info("资产价格同步完成");
    }

    private void updateAssetPrice(Asset asset) {
        // 现金不参与价格更新
        if ("现金".equals(asset.getType())) {
            asset.setCurrentPrice(BigDecimal.ONE);
            asset.setTotalValue(asset.getAmount());
            assetService.saveAsset(asset);
            return;
        }

        String code = asset.getCode();
        if (code == null || code.isBlank()) {
            log.warn("资产 [{}] 无股票代码，跳过价格同步", asset.getName());
            return;
        }

        // 从 stocks 表读最新价格（StockPriceUpdateService 已更新完毕）
        Stock stock = stockService.getStockByCode(code);
        if (stock == null) {
            log.warn("资产 [{}({})] 未在关注列表中找到对应股票，跳过价格同步", asset.getName(), code);
            return;
        }

        BigDecimal currentPrice = stock.getPrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("资产 [{}({})] 股价尚未初始化，跳过本次同步", asset.getName(), code);
            return;
        }

        asset.setCurrentPrice(currentPrice);
        BigDecimal totalValue = asset.getAmount().multiply(currentPrice);
        asset.setTotalValue(totalValue);
        assetService.saveAsset(asset);

        BigDecimal profit = totalValue.subtract(asset.getAmount().multiply(asset.getPurchasePrice()));
        log.debug("资产 [{}({})]：当前价={} 总市值={} 浮动收益={}",
                asset.getName(), code, currentPrice, totalValue, profit);
    }
}
