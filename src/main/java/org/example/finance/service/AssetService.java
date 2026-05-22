package org.example.finance.service;

import org.example.finance.model.Asset;
import org.example.finance.model.User;
import org.example.finance.repository.AssetRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class AssetService {
    private final AssetRepository assetRepository;
    private final StockService stockService;
    private final TradeService tradeService;

    public AssetService(AssetRepository assetRepository,
                        StockService stockService,
                        @Lazy TradeService tradeService) {
        this.assetRepository = assetRepository;
        this.stockService = stockService;
        this.tradeService = tradeService;
    }

    // 获取用户资产列表
    public List<Asset> getUserAssets(User user) {
        // 从数据库查询
        return assetRepository.findByUser(user);
    }

    // 获取所有资产列表
    public List<Asset> getAllAssets() {
        return assetRepository.findAll();
    }

    // 计算资产配置比例
    public Map<String, BigDecimal> calculateAssetAllocation(User user) {
        List<Asset> assets = getUserAssets(user);
        Map<String, BigDecimal> allocation = new HashMap<>();
        BigDecimal totalValue = BigDecimal.ZERO;

        // 计算总价值
        for (Asset asset : assets) {
            totalValue = totalValue.add(asset.getTotalValue());
        }

        // 计算各类型资产的比例
        if (totalValue.compareTo(BigDecimal.ZERO) > 0) {
            for (Asset asset : assets) {
                String type = asset.getType();
                BigDecimal value = asset.getTotalValue();
        BigDecimal percentage = value.divide(totalValue, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
                if (allocation.containsKey(type)) {
                    allocation.put(type, allocation.get(type).add(percentage));
                } else {
                    allocation.put(type, percentage);
                }
            }
        }

        return allocation;
    }

    // 计算资产总价值
    public BigDecimal calculateTotalAssetValue(User user) {
        List<Asset> assets = getUserAssets(user);
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Asset asset : assets) {
            totalValue = totalValue.add(asset.getTotalValue());
        }
        return totalValue;
    }

    // 浮动盈亏（当前持仓的未实现收益）
    public BigDecimal calculateUnrealizedReturn(User user) {
        List<Asset> assets = getUserAssets(user);
        BigDecimal unrealized = BigDecimal.ZERO;
        for (Asset asset : assets) {
            BigDecimal cost = asset.getAmount().multiply(asset.getPurchasePrice());
            unrealized = unrealized.add(asset.getTotalValue().subtract(cost));
        }
        return unrealized.setScale(2, RoundingMode.HALF_UP);
    }

    // 已实现盈亏（历史卖出汇总）
    public BigDecimal calculateRealizedReturn(User user) {
        return tradeService.calcTotalRealizedPnlByUser(user);
    }

    // 总收益 = 浮动盈亏 + 已实现盈亏
    public BigDecimal calculateTotalReturn(User user) {
        return calculateUnrealizedReturn(user).add(calculateRealizedReturn(user));
    }

    // 计算总投入成本（当前持仓部分）
    public BigDecimal calculateTotalCost(User user) {
        List<Asset> assets = getUserAssets(user);
        BigDecimal totalCost = BigDecimal.ZERO;
        for (Asset asset : assets) {
            totalCost = totalCost.add(asset.getAmount().multiply(asset.getPurchasePrice()));
        }
        return totalCost;
    }

    // 总收益率 = 总收益 / 当前持仓成本
    // 注：分母用当前持仓成本，直观反映现有仓位的综合回报
    public BigDecimal calculateReturnRate(User user) {
        BigDecimal cost = calculateTotalCost(user);
        if (cost.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return calculateTotalReturn(user)
                .divide(cost, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    // 检查用户是否有资产配置
    public boolean hasAssets(User user) {
        List<Asset> assets = assetRepository.findByUser(user);
        return !assets.isEmpty();
    }

    // 保存资产
    public void saveAsset(Asset asset) {
        assetRepository.save(asset);
    }

    // 根据ID获取资产
    public Asset getAssetById(Long id) {
        return assetRepository.findById(id).orElse(null);
    }

    // 删除资产
    public void deleteAsset(Long id) {
        assetRepository.deleteById(id);
    }
}
