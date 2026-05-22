package org.example.finance.api;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.SessionKeys;
import org.example.finance.model.User;
import org.example.finance.model.UserProfile;
import org.example.finance.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
public class ProfileApiController {

    private final UserProfileService profileService;

    public ProfileApiController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ResponseEntity<?> get(HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        if (user == null) return ResponseEntity.status(401).build();
        return profileService.findByUser(user)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody ProfileRequest req, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        if (user == null) return ResponseEntity.status(401).build();

        UserProfile p = new UserProfile();
        p.setAge(req.age());
        p.setAnnualIncome(req.annualIncome());
        p.setMonthlySavings(req.monthlySavings());
        p.setInvestmentExperience(req.investmentExperience());
        p.setMaxLossPercent(req.maxLossPercent());
        p.setInvestmentGoal(req.investmentGoal());
        p.setLiquidityNeed(req.liquidityNeed());
        p.setPreferredAssets(req.preferredAssets());
        p.setInvestmentHorizon(req.investmentHorizon());

        UserProfile saved = profileService.saveWithScore(user, p);
        return ResponseEntity.ok(Map.of(
                "riskScore", saved.getRiskScore(),
                "riskLevel", saved.getRiskLevel(),
                "scoreBreakdown", profileService.computeScore(p)
        ));
    }

    public record ProfileRequest(
            int age,
            BigDecimal annualIncome,
            BigDecimal monthlySavings,
            String investmentExperience,
            int maxLossPercent,
            String investmentGoal,
            String liquidityNeed,
            String preferredAssets,
            String investmentHorizon
    ) {}
}
