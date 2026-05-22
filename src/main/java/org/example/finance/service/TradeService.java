package org.example.finance.service;

import org.example.finance.model.Asset;
import org.example.finance.model.TradeRecord;
import org.example.finance.model.User;
import org.example.finance.repository.AssetRepository;
import org.example.finance.repository.TradeRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * 交易记录服务
 *
 * 买入：创建 BUY 记录 → 累加到 Asset.amount，重新计算加权均价（成本价）
 * 卖出：创建 SELL 记录 → 从 Asset.amount 中扣减（不足则拒绝）
 *
 * Asset.purchasePrice 始终保持为加权平均买入成本价（均价法）
 */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    private final TradeRecordRepository tradeRepo;
    private final AssetRepository assetRepo;

    public TradeService(TradeRecordRepository tradeRepo, AssetRepository assetRepo) {
        this.tradeRepo = tradeRepo;
        this.assetRepo = assetRepo;
    }

    // ─────────────────────────────────────────────────────────────
    // 查询
    // ─────────────────────────────────────────────────────────────

    /** 查某资产的所有交易记录，按交易日期倒序 */
    public List<TradeRecord> getTradesByAsset(Asset asset) {
        return tradeRepo.findByAssetOrderByTradeDateDesc(asset);
    }

    /** 查某用户的所有交易记录，按日期倒序（用于全局流水页） */
    public List<TradeRecord> getTradesByUser(User user) {
        return tradeRepo.findByUserOrderByTradeDateDesc(user);
    }

    // ─────────────────────────────────────────────────────────────
    // 买入
    // ─────────────────────────────────────────────────────────────

    /**
     * 执行买入操作
     *
     * @param asset     目标资产
     * @param quantity  买入数量
     * @param price     成交价格
     * @param fee       手续费（可为0）
     * @param tradeDate 成交日期
     * @param remark    备注（可为空）
     */
    @Transactional
    public void buy(Asset asset, BigDecimal quantity, BigDecimal price,
                    BigDecimal fee, Date tradeDate, String remark) {

        BigDecimal tradeAmount = quantity.multiply(price);

        // 1. 建交易记录
        TradeRecord record = new TradeRecord();
        record.setAsset(asset);
        record.setUser(asset.getUser());
        record.setDirection("BUY");
        record.setQuantity(quantity);
        record.setPrice(price);
        record.setAmount(tradeAmount);
        record.setFee(fee != null ? fee : BigDecimal.ZERO);
        record.setTradeDate(tradeDate);
        record.setRemark(remark);
        record.setCreatedAt(new Date());
        tradeRepo.save(record);

        // 2. 更新 Asset：加权均价法重新计算成本价
        //    新均价 = (旧持仓金额 + 本次买入金额) / (旧数量 + 本次数量)
        BigDecimal oldQuantity = asset.getAmount();
        BigDecimal oldCost     = oldQuantity.multiply(asset.getPurchasePrice());
        BigDecimal newQuantity = oldQuantity.add(quantity);
        BigDecimal newCost     = oldCost.add(tradeAmount);
        BigDecimal newAvgPrice = newQuantity.compareTo(BigDecimal.ZERO) > 0
                ? newCost.divide(newQuantity, 4, RoundingMode.HALF_UP)
                : price;

        asset.setAmount(newQuantity);
        asset.setPurchasePrice(newAvgPrice);
        asset.setTotalValue(newQuantity.multiply(asset.getCurrentPrice()));
        assetRepo.save(asset);

        log.info("买入: {} ({}) × {} 股 @ {} 元, 新均价: {}",
                asset.getName(), asset.getCode(), quantity, price, newAvgPrice);
    }

    // ─────────────────────────────────────────────────────────────
    // 卖出
    // ─────────────────────────────────────────────────────────────

    /**
     * 执行卖出操作
     *
     * @return 本次卖出盈亏（正=盈利，负=亏损）
     * @throws IllegalArgumentException 如果卖出数量超过持仓
     */
    @Transactional
    public BigDecimal sell(Asset asset, BigDecimal quantity, BigDecimal price,
                           BigDecimal fee, Date tradeDate, String remark) {

        BigDecimal currentHolding = asset.getAmount();
        if (quantity.compareTo(currentHolding) > 0) {
            throw new IllegalArgumentException(
                    "卖出数量（" + quantity + "）超过当前持仓（" + currentHolding + "）");
        }

        BigDecimal tradeAmount = quantity.multiply(price);
        BigDecimal feeVal = fee != null ? fee : BigDecimal.ZERO;

        // 盈亏 = (卖出价 - 成本均价) × 数量 - 手续费
        BigDecimal profitLoss = price.subtract(asset.getPurchasePrice())
                .multiply(quantity)
                .subtract(feeVal)
                .setScale(4, RoundingMode.HALF_UP);

        // 1. 建交易记录
        TradeRecord record = new TradeRecord();
        record.setAsset(asset);
        record.setUser(asset.getUser());
        record.setDirection("SELL");
        record.setQuantity(quantity);
        record.setPrice(price);
        record.setAmount(tradeAmount);
        record.setFee(feeVal);
        record.setTradeDate(tradeDate);
        record.setRemark(remark);
        record.setCreatedAt(new Date());
        tradeRepo.save(record);

        // 2. 更新 Asset：扣减数量（成本均价不变，持仓法）
        BigDecimal newQuantity = currentHolding.subtract(quantity);
        asset.setAmount(newQuantity);
        asset.setTotalValue(newQuantity.multiply(asset.getCurrentPrice()));
        assetRepo.save(asset);

        log.info("卖出: {} ({}) × {} 股 @ {} 元, 盈亏: {}",
                asset.getName(), asset.getCode(), quantity, price, profitLoss);

        return profitLoss;
    }

    // ─────────────────────────────────────────────────────────────
    // 删除交易记录（回滚 Asset 状态）
    // ─────────────────────────────────────────────────────────────

    /**
     * 删除一条交易记录，并回滚 Asset 的持仓数量和均价
     */
    @Transactional
    public void deleteRecord(TradeRecord record) {
        Asset asset = record.getAsset();

        if ("BUY".equals(record.getDirection())) {
            // 回滚买入：减少持仓，重新计算均价
            BigDecimal newQty = asset.getAmount().subtract(record.getQuantity());
            if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
                asset.setAmount(BigDecimal.ZERO);
                asset.setPurchasePrice(BigDecimal.ZERO);
            } else {
                BigDecimal oldTotal = asset.getAmount().multiply(asset.getPurchasePrice());
                BigDecimal removedTotal = record.getQuantity().multiply(record.getPrice());
                BigDecimal newAvg = oldTotal.subtract(removedTotal)
                        .divide(newQty, 4, RoundingMode.HALF_UP);
                asset.setAmount(newQty);
                asset.setPurchasePrice(newAvg.max(BigDecimal.ZERO));
            }
        } else {
            // 回滚卖出：增加持仓（均价不变）
            asset.setAmount(asset.getAmount().add(record.getQuantity()));
        }

        asset.setTotalValue(asset.getAmount().multiply(asset.getCurrentPrice()));
        assetRepo.save(asset);
        tradeRepo.delete(record);
        log.info("删除交易记录 id={}, 方向={}, 数量={}", record.getId(), record.getDirection(), record.getQuantity());
    }

    /** 根据ID查交易记录 */
    public TradeRecord getById(Long id) {
        return tradeRepo.findById(id).orElse(null);
    }

    /** 更新交易记录的备注和日期（仅允许修改非核心字段，避免均价混乱） */
    @Transactional
    public void updateRemark(TradeRecord record, Date tradeDate, String remark) {
        record.setTradeDate(tradeDate);
        record.setRemark(remark);
        tradeRepo.save(record);
    }

    /** 计算某资产已实现的累计盈亏（所有历史卖出记录汇总） */
    public BigDecimal calcRealizedPnl(Asset asset) {
        List<TradeRecord> records = tradeRepo.findByAssetOrderByTradeDateDesc(asset);
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal avgCost = asset.getPurchasePrice();
        for (TradeRecord r : records) {
            if ("SELL".equals(r.getDirection())) {
                BigDecimal gain = r.getPrice().subtract(avgCost)
                        .multiply(r.getQuantity())
                        .subtract(r.getFee());
                pnl = pnl.add(gain);
            }
        }
        return pnl.setScale(2, RoundingMode.HALF_UP);
    }

    /** 计算某用户所有资产的已实现盈亏总和 */
    public BigDecimal calcTotalRealizedPnlByUser(User user) {
        List<TradeRecord> allSells = tradeRepo.findByUserOrderByTradeDateDesc(user)
                .stream()
                .filter(r -> "SELL".equals(r.getDirection()))
                .toList();

        // 按资产分组，用各资产当前均价计算实现盈亏
        BigDecimal total = BigDecimal.ZERO;
        for (TradeRecord r : allSells) {
            BigDecimal avgCost = r.getAsset().getPurchasePrice();
            BigDecimal gain = r.getPrice().subtract(avgCost)
                    .multiply(r.getQuantity())
                    .subtract(r.getFee());
            total = total.add(gain);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
