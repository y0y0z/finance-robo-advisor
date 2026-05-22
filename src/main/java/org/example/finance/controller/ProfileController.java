package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.SessionKeys;
import org.example.finance.model.User;
import org.example.finance.model.UserProfile;
import org.example.finance.service.UserProfileService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
public class ProfileController {

    private final UserProfileService profileService;

    public ProfileController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profile/setup")
    public String setupPage(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        profileService.findByUser(user).ifPresent(p -> model.addAttribute("profile", p));
        return "profile-setup";
    }

    @PostMapping("/profile/setup")
    public String save(@RequestParam int age,
                       @RequestParam BigDecimal annualIncome,
                       @RequestParam BigDecimal monthlySavings,
                       @RequestParam String investmentExperience,
                       @RequestParam int maxLossPercent,
                       @RequestParam String investmentGoal,
                       @RequestParam String liquidityNeed,
                       @RequestParam(required = false, defaultValue = "") String preferredAssets,
                       @RequestParam String investmentHorizon,
                       HttpSession session,
                       RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        UserProfile p = new UserProfile();
        p.setAge(age);
        p.setAnnualIncome(annualIncome);
        p.setMonthlySavings(monthlySavings);
        p.setInvestmentExperience(investmentExperience);
        p.setMaxLossPercent(maxLossPercent);
        p.setInvestmentGoal(investmentGoal);
        p.setLiquidityNeed(liquidityNeed);
        p.setPreferredAssets(preferredAssets);
        p.setInvestmentHorizon(investmentHorizon);

        UserProfile saved = profileService.saveWithScore(user, p);
        ra.addFlashAttribute("riskScore", saved.getRiskScore());
        ra.addFlashAttribute("riskLevel", saved.getRiskLevel());
        return "redirect:/portfolio-advice";
    }
}
