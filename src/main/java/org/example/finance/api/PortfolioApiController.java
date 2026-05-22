package org.example.finance.api;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.RiskLevel;
import org.example.finance.constant.SessionKeys;
import org.example.finance.model.User;
import org.example.finance.model.UserProfile;
import org.example.finance.service.AssetService;
import org.example.finance.service.PortfolioAdviceService;
import org.example.finance.service.UserProfileService;
import org.example.finance.vo.AllocationItem;
import org.example.finance.vo.ConcentrationWarning;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/portfolio-advice")
public class PortfolioApiController {

    private final UserProfileService profileService;
    private final AssetService assetService;
    private final PortfolioAdviceService adviceService;

    public PortfolioApiController(UserProfileService profileService,
                                  AssetService assetService,
                                  PortfolioAdviceService adviceService) {
        this.profileService = profileService;
        this.assetService = assetService;
        this.adviceService = adviceService;
    }

    @GetMapping
    public ResponseEntity<?> get(HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        if (user == null) return ResponseEntity.status(401).build();

        Optional<UserProfile> profileOpt = profileService.findByUser(user);
        if (profileOpt.isEmpty()) return ResponseEntity.notFound().build();

        UserProfile profile = profileOpt.get();
        String riskLevel = profile.getRiskLevel();

        List<AllocationItem> comparison = adviceService.getAllocationComparison(user, profile);
        List<ConcentrationWarning> warnings = adviceService.getConcentrationWarnings(user, profile);
        Map<String, Integer> target = adviceService.getTargetAllocation(user, profile);

        return ResponseEntity.ok(Map.of(
                "profile", Map.of(
                        "riskScore",           profile.getRiskScore(),
                        "riskLevel",           riskLevel,
                        "riskLevelLabel",      RiskLevel.label(riskLevel),
                        "investmentGoal",      profile.getInvestmentGoal(),
                        "investmentHorizon",   profile.getInvestmentHorizon(),
                        "maxLossPercent",      profile.getMaxLossPercent(),
                        "preferredAssets",     profile.getPreferredAssets(),
                        "liquidityNeed",       profile.getLiquidityNeed(),
                        "age",                 profile.getAge()
                ),
                "targetAllocation",  target,
                "allocationComparison", comparison,
                "concentrationWarnings", warnings
        ));
    }
}
