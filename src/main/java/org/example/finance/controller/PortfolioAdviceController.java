package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.RiskLevel;
import org.example.finance.constant.SessionKeys;
import org.example.finance.model.User;
import org.example.finance.model.UserProfile;
import org.example.finance.service.AssetService;
import org.example.finance.service.PortfolioAdviceService;
import org.example.finance.service.UserProfileService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

@Controller
public class PortfolioAdviceController {

    private final UserProfileService profileService;
    private final AssetService assetService;
    private final PortfolioAdviceService adviceService;

    public PortfolioAdviceController(UserProfileService profileService,
                                     AssetService assetService,
                                     PortfolioAdviceService adviceService) {
        this.profileService = profileService;
        this.assetService = assetService;
        this.adviceService = adviceService;
    }

    @GetMapping("/portfolio-advice")
    public String page(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        Optional<UserProfile> opt = profileService.findByUser(user);

        if (opt.isEmpty()) return "redirect:/profile/setup";

        UserProfile profile = opt.get();
        String level = profile.getRiskLevel();

        model.addAttribute("profile", profile);
        model.addAttribute("riskLevelLabel", RiskLevel.label(level));
        model.addAttribute("allocationComparison", adviceService.getAllocationComparison(user, profile));
        model.addAttribute("baseTarget", adviceService.getBaseTargetAllocation(level));
        model.addAttribute("dynamicTarget", adviceService.getTargetAllocation(user, profile));
        model.addAttribute("concentrationWarnings", adviceService.getConcentrationWarnings(user, profile));
        model.addAttribute("adjustmentReasons", adviceService.getAdjustmentReasons(user, profile));
        model.addAttribute("hasAssets", assetService.hasAssets(user));
        return "portfolio-advice";
    }
}
