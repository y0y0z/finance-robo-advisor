package org.example.finance.service;

import org.example.finance.model.Asset;
import org.example.finance.model.User;
import org.example.finance.model.UserProfile;
import org.example.finance.vo.AdjustmentReason;
import org.example.finance.vo.AllocationItem;
import org.example.finance.vo.ConcentrationWarning;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标仓位引擎（动态调整版）
 * 根据用户风险等级计算基础目标仓位，叠加市场情绪、持仓集中度、用户画像三个维度动态微调。
 */
@Service
public class PortfolioAdviceService {

    private static final Map<String, Map<String, Integer>> BASE_TARGETS = Map.of(
        "CONSERVATIVE", Map.of("股票", 20, "基金", 60, "现金", 20),
        "BALANCED",     Map.of("股票", 40, "基金", 45, "现金", 15),
        "AGGRESSIVE",   Map.of("股票", 65, "基金", 30, "现金",  5)
    );

    private static final List<String> ASSET_TYPES = List.of("股票", "基金", "现金");
    private static final int ADJUSTMENT_CAP = 5;
    private static final int MIN_ALLOCATION = 0;
    private static final int MAX_ALLOCATION = 80;

    private final AssetService assetService;
    private final MarketSentimentService sentimentService;

    public PortfolioAdviceService(AssetService assetService, MarketSentimentService sentimentService) {
        this.assetService = assetService;
        this.sentimentService = sentimentService;
    }

    /** 获取基础目标仓位（静态模板） */
    public Map<String, Integer> getBaseTargetAllocation(String riskLevel) {
        return BASE_TARGETS.getOrDefault(riskLevel, BASE_TARGETS.get("BALANCED"));
    }

    /** 获取动态调整后的目标仓位 */
    public Map<String, Integer> getTargetAllocation(User user, UserProfile profile) {
        String riskLevel = profile.getRiskLevel();
        Map<String, Integer> base = getBaseTargetAllocation(riskLevel);
        Map<String, BigDecimal> adjustments = new LinkedHashMap<>();
        for (String type : ASSET_TYPES) {
            adjustments.put(type, BigDecimal.valueOf(base.getOrDefault(type, 0)));
        }

        applyMarketAdjustment(adjustments);
        applyConcentrationAdjustment(adjustments, user, riskLevel);
        applyProfileAdjustment(adjustments, profile);

        return normalizeAndClamp(adjustments);
    }

    /** 获取动态调整的原因说明，供前端展示 */
    public List<AdjustmentReason> getAdjustmentReasons(User user, UserProfile profile) {
        List<AdjustmentReason> reasons = new ArrayList<>();

        // 市场情绪
        MarketSentimentService.MarketSentiment sentiment = sentimentService.getLatest();
        if (sentiment != null && sentiment.score() >= 0) {
            int shift = (sentiment.score() - 50) / 10;
            if (Math.abs(shift) >= 1) {
                String direction = shift > 0 ? "偏贪婪" : "偏恐惧";
                String action = shift > 0 ? "降低股票占比、增加现金防御" : "适度增加股票配置、抓住低位机会";
                reasons.add(new AdjustmentReason("市场情绪",
                        String.format("当前情绪 %d（%s），%s，%s", sentiment.score(), sentiment.level(), direction, action)));
            } else {
                reasons.add(new AdjustmentReason("市场情绪",
                        String.format("当前情绪 %d（%s），市场中性，不作调整", sentiment.score(), sentiment.level())));
            }
        } else {
            reasons.add(new AdjustmentReason("市场情绪", "休市状态，不应用情绪偏移"));
        }

        // 持仓集中度
        Map<String, BigDecimal> currentAlloc = assetService.calculateAssetAllocation(user);
        Map<String, Integer> base = getBaseTargetAllocation(profile.getRiskLevel());
        int stockCur = currentAlloc.getOrDefault("股票", BigDecimal.ZERO).intValue();
        int stockBase = base.getOrDefault("股票", 0);
        int cashCur = currentAlloc.getOrDefault("现金", BigDecimal.ZERO).intValue();
        int cashBase = base.getOrDefault("现金", 0);

        if (stockCur > stockBase + 10) {
            reasons.add(new AdjustmentReason("持仓集中度",
                    String.format("股票占比 %d%% 超出目标过多，引导向基金分散", stockCur)));
        }
        if (cashBase > 0 && cashCur < cashBase / 2) {
            reasons.add(new AdjustmentReason("持仓集中度",
                    String.format("现金占比 %d%% 不足目标的一半，适当补充流动性", cashCur)));
        }

        // 用户画像
        if ("NONE".equals(profile.getInvestmentExperience()) || "BEGINNER".equals(profile.getInvestmentExperience())) {
            reasons.add(new AdjustmentReason("用户画像", "投资经验较浅，适当降低股票配置、增加基金"));
        }
        if ("HIGH".equals(profile.getLiquidityNeed())) {
            reasons.add(new AdjustmentReason("用户画像", "流动性需求高，增加现金配置"));
        }
        if ("SHORT".equals(profile.getInvestmentHorizon())) {
            reasons.add(new AdjustmentReason("用户画像", "投资期限较短，增加流动性"));
        }
        if (profile.getMaxLossPercent() < 10) {
            reasons.add(new AdjustmentReason("用户画像", "最大可接受亏损较低，降低权益仓位"));
        }

        if (reasons.isEmpty()) {
            reasons.add(new AdjustmentReason("综合", "各项指标正常，维持基础目标配置"));
        }
        return reasons;
    }

    // ── 维度1：市场情绪偏移 ──
    private void applyMarketAdjustment(Map<String, BigDecimal> adj) {
        MarketSentimentService.MarketSentiment sentiment = sentimentService.getLatest();
        if (sentiment == null || sentiment.score() < 0) return;

        int shift = (sentiment.score() - 50) / 10;
        shift = Math.max(-ADJUSTMENT_CAP, Math.min(ADJUSTMENT_CAP, shift));

        adj.put("股票", adj.get("股票").subtract(BigDecimal.valueOf(shift)));
        adj.put("现金", adj.get("现金").add(BigDecimal.valueOf(shift)));
    }

    // ── 维度2：持仓集中度偏移 ──
    private void applyConcentrationAdjustment(Map<String, BigDecimal> adj, User user, String riskLevel) {
        Map<String, BigDecimal> currentAlloc = assetService.calculateAssetAllocation(user);
        Map<String, Integer> base = getBaseTargetAllocation(riskLevel);

        int stockCur = currentAlloc.getOrDefault("股票", BigDecimal.ZERO).intValue();
        int stockBase = base.getOrDefault("股票", 0);
        if (stockCur > stockBase + 10) {
            adj.put("股票", adj.get("股票").subtract(BigDecimal.valueOf(3)));
            adj.put("基金", adj.get("基金").add(BigDecimal.valueOf(3)));
        }

        int cashCur = currentAlloc.getOrDefault("现金", BigDecimal.ZERO).intValue();
        int cashBase = base.getOrDefault("现金", 0);
        if (cashBase > 0 && cashCur < cashBase / 2) {
            adj.put("现金", adj.get("现金").add(BigDecimal.valueOf(2)));
            adj.put("股票", adj.get("股票").subtract(BigDecimal.valueOf(2)));
        }

        List<Asset> assets = assetService.getUserAssets(user);
        BigDecimal total = assetService.calculateTotalAssetValue(user);
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            boolean hasConcentration = assets.stream().anyMatch(a ->
                    a.getTotalValue().multiply(BigDecimal.valueOf(100))
                     .divide(total, 0, RoundingMode.HALF_UP).intValue() > 40);
            if (hasConcentration) {
                adj.put("基金", adj.get("基金").add(BigDecimal.valueOf(2)));
                adj.put("股票", adj.get("股票").subtract(BigDecimal.valueOf(2)));
            }
        }
    }

    // ── 维度3：用户画像偏移 ──
    private void applyProfileAdjustment(Map<String, BigDecimal> adj, UserProfile p) {
        if ("NONE".equals(p.getInvestmentExperience()) || "BEGINNER".equals(p.getInvestmentExperience())) {
            adj.put("股票", adj.get("股票").subtract(BigDecimal.valueOf(2)));
            adj.put("基金", adj.get("基金").add(BigDecimal.valueOf(2)));
        }
        if ("HIGH".equals(p.getLiquidityNeed())) {
            adj.put("现金", adj.get("现金").add(BigDecimal.valueOf(3)));
            adj.put("股票", adj.get("股票").subtract(BigDecimal.valueOf(3)));
        }
        if ("SHORT".equals(p.getInvestmentHorizon())) {
            adj.put("现金", adj.get("现金").add(BigDecimal.valueOf(3)));
            adj.put("股票", adj.get("股票").subtract(BigDecimal.valueOf(3)));
        }
        if (p.getMaxLossPercent() < 10) {
            adj.put("股票", adj.get("股票").subtract(BigDecimal.valueOf(2)));
            adj.put("现金", adj.get("现金").add(BigDecimal.valueOf(2)));
        }
    }

    // ── 归一化到 100% 并钳制 ──
    private Map<String, Integer> normalizeAndClamp(Map<String, BigDecimal> adj) {
        for (String type : ASSET_TYPES) {
            BigDecimal v = adj.getOrDefault(type, BigDecimal.ZERO);
            adj.put(type, v.max(BigDecimal.valueOf(MIN_ALLOCATION))
                           .min(BigDecimal.valueOf(MAX_ALLOCATION)));
        }

        BigDecimal sum = adj.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ZERO) == 0) return getBaseTargetAllocation("BALANCED");

        Map<String, Integer> result = new LinkedHashMap<>();
        for (String type : ASSET_TYPES) {
            BigDecimal normalized = adj.getOrDefault(type, BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(sum, 0, RoundingMode.HALF_UP);
            result.put(type, normalized.intValue());
        }

        int total = result.values().stream().mapToInt(Integer::intValue).sum();
        if (total != 100) {
            int deficit = 100 - total;
            for (String type : ASSET_TYPES) {
                if (deficit == 0) break;
                int cur = result.get(type);
                int canAdd = deficit > 0
                        ? Math.min(deficit, MAX_ALLOCATION - cur)
                        : Math.max(deficit, -cur);
                result.put(type, cur + canAdd);
                deficit -= canAdd;
            }
        }

        return result;
    }

    /** 目标 vs 当前仓位对比列表（使用动态调整后的目标） */
    public List<AllocationItem> getAllocationComparison(User user, UserProfile profile) {
        Map<String, BigDecimal> current = assetService.calculateAssetAllocation(user);
        Map<String, Integer> target = getTargetAllocation(user, profile);
        List<AllocationItem> result = new ArrayList<>();

        for (String type : ASSET_TYPES) {
            BigDecimal cur = current.getOrDefault(type, BigDecimal.ZERO);
            int tgt = target.getOrDefault(type, 0);
            BigDecimal delta = BigDecimal.valueOf(tgt).subtract(cur).setScale(1, RoundingMode.HALF_UP);
            result.add(new AllocationItem(type, cur.setScale(1, RoundingMode.HALF_UP), tgt, delta));
        }
        return result;
    }

    /**
     * 集中度风险检测
     */
    public List<ConcentrationWarning> getConcentrationWarnings(User user, UserProfile profile) {
        List<Asset> assets = assetService.getUserAssets(user);
        BigDecimal total = assetService.calculateTotalAssetValue(user);
        List<ConcentrationWarning> warnings = new ArrayList<>();

        if (total.compareTo(BigDecimal.ZERO) == 0) return warnings;

        for (Asset a : assets) {
            double pct = a.getTotalValue()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(total, 1, RoundingMode.HALF_UP)
                    .doubleValue();
            if (pct > 50)
                warnings.add(new ConcentrationWarning(a.getName(), "ASSET", pct, "DANGER"));
            else if (pct > 30)
                warnings.add(new ConcentrationWarning(a.getName(), "ASSET", pct, "WARNING"));
        }

        Map<String, BigDecimal> typeAlloc = assetService.calculateAssetAllocation(user);
        Map<String, Integer> target = getTargetAllocation(user, profile);
        for (String type : ASSET_TYPES) {
            double cur = typeAlloc.getOrDefault(type, BigDecimal.ZERO).doubleValue();
            int tgt = target.getOrDefault(type, 0);
            if (cur > tgt + 20)
                warnings.add(new ConcentrationWarning(type, "TYPE", cur, "OVERWEIGHT"));
        }
        return warnings;
    }
}
