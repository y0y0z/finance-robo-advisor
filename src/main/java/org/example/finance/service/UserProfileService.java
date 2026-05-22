package org.example.finance.service;

import org.example.finance.constant.RiskLevel;
import org.example.finance.model.User;
import org.example.finance.model.UserProfile;
import org.example.finance.repository.UserProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 用户投资画像服务
 * 负责画像的保存、查询与风险评分计算。
 */
@Service
public class UserProfileService {

    private final UserProfileRepository repo;

    public UserProfileService(UserProfileRepository repo) {
        this.repo = repo;
    }

    public Optional<UserProfile> findByUser(User user) {
        return repo.findByUser(user);
    }

    public boolean hasProfile(User user) {
        return repo.findByUser(user).isPresent();
    }

    /**
     * 保存画像，自动计算 riskScore 与 riskLevel。
     * 若已存在则更新，不存在则新建。
     */
    @Transactional
    public UserProfile saveWithScore(User user, UserProfile incoming) {
        int score = computeScore(incoming);
        String level = RiskLevel.fromScore(score);

        UserProfile target = repo.findByUser(user).orElseGet(UserProfile::new);
        target.setUser(user);
        target.setAge(incoming.getAge());
        target.setAnnualIncome(incoming.getAnnualIncome());
        target.setMonthlySavings(incoming.getMonthlySavings());
        target.setInvestmentExperience(incoming.getInvestmentExperience());
        target.setMaxLossPercent(incoming.getMaxLossPercent());
        target.setInvestmentGoal(incoming.getInvestmentGoal());
        target.setLiquidityNeed(incoming.getLiquidityNeed());
        target.setPreferredAssets(incoming.getPreferredAssets());
        target.setInvestmentHorizon(incoming.getInvestmentHorizon());
        target.setRiskScore(score);
        target.setRiskLevel(level);

        return repo.save(target);
    }

    /**
     * 风险评分算法（满分 100）：
     *   年龄        <30→20  30-50→15  >50→5
     *   投资经验    EXPERT→20  INTERMEDIATE→15  BEGINNER→10  NONE→5
     *   最大亏损    >20→20    10-20→15   5-10→10   <5→5
     *   投资期限    LONG→20   MEDIUM→15  SHORT→5
     *   投资目标    SPECULATION→20  WEALTH_GROWTH→15  INCOME→10  PRESERVATION→5
     */
    public int computeScore(UserProfile p) {
        int score = 0;

        // 年龄
        int age = p.getAge();
        if (age < 30) score += 20;
        else if (age <= 50) score += 15;
        else score += 5;

        // 投资经验
        score += switch (nullSafe(p.getInvestmentExperience())) {
            case "EXPERT"       -> 20;
            case "INTERMEDIATE" -> 15;
            case "BEGINNER"     -> 10;
            default             -> 5;
        };

        // 最大可接受亏损
        int loss = p.getMaxLossPercent();
        if (loss > 20)      score += 20;
        else if (loss >= 10) score += 15;
        else if (loss >= 5)  score += 10;
        else                 score += 5;

        // 投资期限
        score += switch (nullSafe(p.getInvestmentHorizon())) {
            case "LONG"   -> 20;
            case "MEDIUM" -> 15;
            default       -> 5;  // SHORT 或未填
        };

        // 投资目标
        score += switch (nullSafe(p.getInvestmentGoal())) {
            case "SPECULATION"   -> 20;
            case "WEALTH_GROWTH" -> 15;
            case "INCOME"        -> 10;
            default              -> 5;  // PRESERVATION 或未填
        };

        return score;
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
