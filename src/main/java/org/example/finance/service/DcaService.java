package org.example.finance.service;

import org.example.finance.model.*;
import org.example.finance.repository.AssetRepository;
import org.example.finance.repository.DcaPlanRepository;
import org.example.finance.repository.DcaRecordRepository;
import org.example.finance.vo.DcaPlanView;
import org.example.finance.constant.DcaFrequency;
import org.example.finance.constant.DcaStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@Service
public class DcaService {

    private static final Logger log = LoggerFactory.getLogger(DcaService.class);

    @Autowired private DcaPlanRepository planRepo;
    @Autowired private DcaRecordRepository recordRepo;
    @Autowired private AssetRepository assetRepo;
    @Autowired private TradeService tradeService;

    public List<DcaPlan> getPlansByUser(User user) {
        return planRepo.findByUserOrderByCreateTimeDesc(user);
    }

    /**
     * 获得用户的所有定投计划
     * @param user
     * @return
     */
    public List<DcaPlanView> getPlanViewsByUser(User user) {
        return getPlansByUser(user).stream()
                .map(this::toView)
                .toList();
    }

    private DcaPlanView toView(DcaPlan plan) {
        return new DcaPlanView(
                plan,
                isDue(plan),
                nextDueDate(plan),
                totalInvested(plan),
                totalPeriods(plan),
                avgCost(plan)
        );
    }

    public DcaPlan getPlanById(Long id) {
        return planRepo.findById(id).orElse(null);
    }

    public List<DcaPlan> getPlansByGoal(InvestmentGoal goal) {
        return planRepo.findByGoal(goal);
    }

    @Transactional
    public void linkGoal(Long planId, InvestmentGoal goal) {
        planRepo.findById(planId).ifPresent(p -> { p.setGoal(goal); planRepo.save(p); });
    }

    /**
     * 保存定投计划
     * @param plan
     * @return
     */
    public DcaPlan savePlan(DcaPlan plan) {
        if (plan.getId() == null) {
            plan.setCreateTime(new Date());
            plan.setStatus(DcaStatus.ACTIVE);
        }
        plan.setUpdateTime(new Date());
        return planRepo.save(plan);
    }

    @Transactional
    public void deletePlan(Long id) {
        DcaPlan plan = planRepo.findById(id).orElse(null);
        if (plan == null) return;
        // 先删子记录，再删计划（避免外键约束冲突）
        recordRepo.deleteByPlan(plan);
        planRepo.deleteById(id);
    }

    public void toggleStatus(DcaPlan plan) {
        plan.setStatus(DcaStatus.ACTIVE.equals(plan.getStatus()) ? DcaStatus.PAUSED : DcaStatus.ACTIVE);
        plan.setUpdateTime(new Date());
        planRepo.save(plan);
    }

    /**
     * 获得用户定投计划的执行记录
     * @param plan
     * @return
     */
    public List<DcaRecord> getRecordsByPlan(DcaPlan plan) {
        return recordRepo.findByPlanOrderByExecuteDateDesc(plan);
    }

    /**
     * 执行一次定投：
     * 1. 创建 DcaRecord
     * 2. 找到或创建对应 Asset，调用 TradeService.buy() 更新持仓
     * 3. 更新计划的 lastExecuteDate
     */
    @Transactional
    public void execute(DcaPlan plan, BigDecimal actualAmount, BigDecimal nav,
                        LocalDate executeDate, String notes, User user) {
        execute(plan, actualAmount, nav, executeDate, notes, user, false);
    }

    @Transactional
    public void execute(DcaPlan plan, BigDecimal actualAmount, BigDecimal nav,
                        LocalDate executeDate, String notes, User user, boolean auto) {

        // 份额 = 金额 / 净值，向下取整保留2位（符合基金规则）
        BigDecimal shares = actualAmount.divide(nav, 2, RoundingMode.DOWN);

        // 1. 保存定投执行记录
        DcaRecord record = new DcaRecord();
        record.setPlan(plan);
        record.setUser(user);
        record.setAmount(actualAmount);
        record.setPrice(nav);
        record.setShares(shares);
        record.setExecuteDate(executeDate);
        record.setNotes(notes);
        record.setAuto(auto);
        record.setCreateTime(new Date());
        recordRepo.save(record);

        // 2. 找或创建资产持仓
        Asset asset = assetRepo.findByUserAndCode(user, plan.getCode());
        // 如果定投的资产之前没有投资过 则新建一类资产
        if (asset == null) {
            asset = new Asset();
            asset.setUser(user);
            asset.setCode(plan.getCode());
            asset.setName(plan.getName());
            asset.setType("基金");
            asset.setAmount(BigDecimal.ZERO);
            asset.setPurchasePrice(nav);
            asset.setCurrentPrice(nav);
            asset.setTotalValue(BigDecimal.ZERO);
            // purchaseDate 用执行日期
            asset.setPurchaseDate(java.sql.Date.valueOf(executeDate));
            assetRepo.save(asset);
        }

        // 3. 调用 TradeService.buy() 写交易记录 + 更新均价
        String remark = "定投执行" + (notes != null && !notes.isBlank() ? "：" + notes : "");
        tradeService.buy(asset, shares, nav, BigDecimal.ZERO,
                java.sql.Date.valueOf(executeDate), remark);

        // 4. 更新计划最近执行日期
        plan.setLastExecuteDate(executeDate);
        plan.setUpdateTime(new Date());
        planRepo.save(plan);

        log.info("定投执行: 计划[{}] {} {} 元 / 净值 {} = {} 份",
                plan.getId(), plan.getName(), actualAmount, nav, shares);
    }

    // ──────────────────────────────────────────────
    // 统计
    // ──────────────────────────────────────────────

    /** 某计划的累计投入金额 */
    public BigDecimal totalInvested(DcaPlan plan) {
        return recordRepo.findByPlanOrderByExecuteDateDesc(plan)
                .stream()
                .map(DcaRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 某计划的执行期数 */
    public int totalPeriods(DcaPlan plan) {
        return recordRepo.countByPlan(plan);
    }

    /** 某计划的累计买入份额 */
    public BigDecimal totalShares(DcaPlan plan) {
        return recordRepo.findByPlanOrderByExecuteDateDesc(plan)
                .stream()
                .map(DcaRecord::getShares)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 加权平均成本 = 累计投入 / 累计份额 */
    public BigDecimal avgCost(DcaPlan plan) {
        BigDecimal shares = totalShares(plan);
        if (shares.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return totalInvested(plan).divide(shares, 4, RoundingMode.HALF_UP);
    }

    /**
     * 计算下次应执行日期（可能是过去的日期，表示逾期未执行）。
     * 调度器依赖此方法逐期补执行：每次 execute 更新 lastExecuteDate 后，
     * 本方法返回下一个到期日，直到追上今天。
     */
    public LocalDate nextDueDate(DcaPlan plan) {
        if (plan.getLastExecuteDate() == null) {
            LocalDate start = plan.getStartDate();
            return start != null ? start : LocalDate.now();
        }
        return computeNext(plan, plan.getLastExecuteDate());
    }

    /** 从给定 base 向后推一个周期，若落在周末则顺延到周一 */
    private LocalDate computeNext(DcaPlan plan, LocalDate base) {
        LocalDate next = switch (plan.getFrequency()) {
            case DcaFrequency.DAILY    -> nextTradingDay(base.plusDays(1));
            case DcaFrequency.WEEKLY   -> base.plusWeeks(1);
            case DcaFrequency.BIWEEKLY -> base.plusWeeks(2);
            default -> { // MONTHLY
                int dom = plan.getDayOfMonth() != null ? plan.getDayOfMonth() : base.getDayOfMonth();
                LocalDate m = base.plusMonths(1).withDayOfMonth(1);
                int maxDay = m.lengthOfMonth();
                yield m.withDayOfMonth(Math.min(dom, maxDay));
            }
        };
        return nextTradingDay(next);
    }

    /** 若日期落在周末，顺延到下周一 */
    private LocalDate nextTradingDay(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> date.plusDays(2);
            case SUNDAY   -> date.plusDays(1);
            default       -> date;
        };
    }

    /**
     * 是否今日到期或已逾期
     * 即是否需要执行
     * 当且仅当计划在执行且下次执行时间是今天或今天之前，返回true
     */
    public boolean isDue(DcaPlan plan) {
        return DcaStatus.ACTIVE.equals(plan.getStatus())
                && !nextDueDate(plan).isAfter(LocalDate.now());
    }

    /** 获取当前用户到期（含逾期）的定投计划列表，供仪表盘提示 */
    public List<DcaPlan> getDuePlans(User user) {
        return planRepo.findByUserAndStatus(user, DcaStatus.ACTIVE)
                .stream()
                .filter(this::isDue)
                .toList();
    }
}
