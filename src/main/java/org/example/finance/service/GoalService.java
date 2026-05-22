package org.example.finance.service;

import org.example.finance.model.DcaPlan;
import org.example.finance.model.InvestmentGoal;
import org.example.finance.model.User;
import org.example.finance.repository.DcaPlanRepository;
import org.example.finance.repository.InvestmentGoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

@Service
public class GoalService {

    private final InvestmentGoalRepository goalRepo;
    private final DcaPlanRepository dcaPlanRepo;

    public GoalService(InvestmentGoalRepository goalRepo, DcaPlanRepository dcaPlanRepo) {
        this.goalRepo = goalRepo;
        this.dcaPlanRepo = dcaPlanRepo;
    }

    public List<InvestmentGoal> getGoals(User user) {
        return goalRepo.findByUserOrderByPriorityAscCreateTimeDesc(user);
    }

    public InvestmentGoal getById(Long id) {
        return goalRepo.findById(id).orElse(null);
    }

    @Transactional
    public InvestmentGoal save(User user, InvestmentGoal goal) {
        goal.setUser(user);
        if (goal.getCurrentAmount() == null) goal.setCurrentAmount(BigDecimal.ZERO);
        if (goal.getCreateTime() == null) goal.setCreateTime(new Date());
        return goalRepo.save(goal);
    }

    @Transactional
    public void delete(Long id) {
        // 解除关联的定投计划
        goalRepo.findById(id).ifPresent(g -> {
            dcaPlanRepo.findByGoal(g).forEach(p -> { p.setGoal(null); dcaPlanRepo.save(p); });
            goalRepo.deleteById(id);
        });
    }

    // ── 进度引擎 ──────────────────────────────────────────────────────────

    /** 完成率 0-100，保留一位小数 */
    public BigDecimal completionRate(InvestmentGoal g) {
        if (g.getTargetAmount().compareTo(BigDecimal.ZERO) == 0) return BigDecimal.valueOf(100);
        return g.getCurrentAmount()
                .multiply(BigDecimal.valueOf(100))
                .divide(g.getTargetAmount(), 1, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100));
    }

    /**
     * 达成概率（简化模型）：
     * 基于当前积累速度（月均投入）线性外推，判断在 targetDate 前能否达到目标。
     * 关联的定投计划月均金额 + 当前已积累 → 预计到期金额 → 与目标比较。
     * 返回 0-100 的概率估计。
     */
    public int achieveProbability(InvestmentGoal g) {
        if (g.getCurrentAmount().compareTo(g.getTargetAmount()) >= 0) return 100;

        LocalDate today = LocalDate.now();
        if (g.getTargetDate() == null || !g.getTargetDate().isAfter(today)) return 0;

        BigDecimal monthlyDca = dcaPlanRepo.findByGoal(g).stream()
                .filter(p -> "ACTIVE".equals(p.getStatus()))
                .map(this::toMonthlyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long daysLeft = ChronoUnit.DAYS.between(today, g.getTargetDate());
        BigDecimal monthsLeft = BigDecimal.valueOf(daysLeft)
                .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);

        BigDecimal projected = g.getCurrentAmount()
                .add(monthlyDca.multiply(monthsLeft));

        if (projected.compareTo(g.getTargetAmount()) >= 0) return 95;

        return projected.multiply(BigDecimal.valueOf(95))
                .divide(g.getTargetAmount(), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * 计算投资目标的资金缺口。
     * 剩余时间 >= 1个月时，返回月度缺口（还需每月额外投入多少）。
     * 剩余时间 < 1个月时，返回总缺口（还需一次性投入多少）。
     */
    public BigDecimal monthlyGap(InvestmentGoal g) {
        LocalDate today = LocalDate.now();

        if (g.getTargetDate() == null || !g.getTargetDate().isAfter(today)) {
            return BigDecimal.ZERO;
        }

        BigDecimal remaining = g.getTargetAmount().subtract(g.getCurrentAmount());
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long daysLeft = ChronoUnit.DAYS.between(today, g.getTargetDate());

        // 统计该目标下所有 ACTIVE 定投计划的月投入总额
        BigDecimal monthlyDca = dcaPlanRepo.findByGoal(g).stream()
                .filter(p -> "ACTIVE".equals(p.getStatus()))
                .map(this::toMonthlyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (daysLeft < 30) {
            // 不足一个月，返回总缺口（扣除本月已有的定投计划可投入部分）
            BigDecimal totalDcaInPeriod = monthlyDca
                    .multiply(BigDecimal.valueOf(daysLeft))
                    .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
            BigDecimal gap = remaining.subtract(totalDcaInPeriod);
            return gap.max(BigDecimal.ZERO);
        }

        // 满一个月，返回月度缺口
        BigDecimal monthsLeft = BigDecimal.valueOf(daysLeft)
                .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
        BigDecimal needed = remaining.divide(monthsLeft, 2, RoundingMode.CEILING);
        BigDecimal gap = needed.subtract(monthlyDca);
        return gap.max(BigDecimal.ZERO);
    }

    /** 剩余天数 */
    public long daysLeft(InvestmentGoal g) {
        if (g.getTargetDate() == null) return 0;
        long days = ChronoUnit.DAYS.between(LocalDate.now(), g.getTargetDate());
        return Math.max(days, 0);
    }

    /** 将定投计划金额换算为月均金额 */
    private BigDecimal toMonthlyAmount(DcaPlan p) {
        return switch (p.getFrequency()) {
            case "DAILY"     -> p.getAmount().multiply(BigDecimal.valueOf(22));
            case "WEEKLY"    -> p.getAmount().multiply(BigDecimal.valueOf(4));
            case "BIWEEKLY"  -> p.getAmount().multiply(BigDecimal.valueOf(2));
            default          -> p.getAmount(); // MONTHLY
        };
    }

    /** 同步更新目标的 currentAmount（从关联定投记录汇总） */
    @Transactional
    public void syncCurrentAmount(InvestmentGoal g) {
        BigDecimal total = dcaPlanRepo.findByGoal(g).stream()
                .map(p -> dcaPlanRepo.sumExecutedAmount(p))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        g.setCurrentAmount(total);
        goalRepo.save(g);
    }
}
