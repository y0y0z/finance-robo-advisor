package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.SessionKeys;
import org.example.finance.model.InvestmentGoal;
import org.example.finance.model.User;
import org.example.finance.service.AIService;
import org.example.finance.service.DcaService;
import org.example.finance.service.GoalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/goals")
public class GoalController {

    private final GoalService goalService;
    private final DcaService dcaService;
    private final AIService aiService;

    public GoalController(GoalService goalService, DcaService dcaService, AIService aiService) {
        this.goalService = goalService;
        this.dcaService = dcaService;
        this.aiService = aiService;
    }

    @GetMapping
    public String list(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        List<InvestmentGoal> goals = goalService.getGoals(user);
        model.addAttribute("goals", goals);
        model.addAttribute("goalService", goalService);
        return "goals";
    }

    @GetMapping("/add")
    public String addForm() {
        return "goal-form";
    }

    @PostMapping("/add")
    public String add(@RequestParam String goalName,
                      @RequestParam BigDecimal targetAmount,
                      @RequestParam LocalDate targetDate,
                      @RequestParam String riskLevel,
                      @RequestParam int priority,
                      @RequestParam(required = false) String notes,
                      HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        InvestmentGoal g = new InvestmentGoal();
        g.setGoalName(goalName);
        g.setTargetAmount(targetAmount);
        g.setTargetDate(targetDate);
        g.setRiskLevel(riskLevel);
        g.setPriority(priority);
        g.setNotes(notes);
        goalService.save(user, g);
        ra.addFlashAttribute("success", "目标「" + goalName + "」已创建");
        return "redirect:/goals";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        InvestmentGoal g = goalService.getById(id);
        if (g == null || !g.getUser().getId().equals(user.getId())) return "redirect:/goals";

        goalService.syncCurrentAmount(g);

        model.addAttribute("goal", g);
        model.addAttribute("completionRate", goalService.completionRate(g).toPlainString());
        model.addAttribute("achieveProbability", goalService.achieveProbability(g));
        model.addAttribute("monthlyGap", goalService.monthlyGap(g));
        model.addAttribute("daysLeft", goalService.daysLeft(g));
        model.addAttribute("linkedPlans", dcaService.getPlansByGoal(g));
        model.addAttribute("allPlans", dcaService.getPlansByUser(user));
        return "goal-detail";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        InvestmentGoal g = goalService.getById(id);
        if (g == null || !g.getUser().getId().equals(user.getId())) return "redirect:/goals";
        model.addAttribute("goal", g);
        return "goal-edit";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id,
                       @RequestParam String goalName,
                       @RequestParam BigDecimal targetAmount,
                       @RequestParam LocalDate targetDate,
                       @RequestParam String riskLevel,
                       @RequestParam int priority,
                       @RequestParam(required = false) String notes,
                       HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        InvestmentGoal g = goalService.getById(id);
        if (g == null || !g.getUser().getId().equals(user.getId())) return "redirect:/goals";
        g.setGoalName(goalName);
        g.setTargetAmount(targetAmount);
        g.setTargetDate(targetDate);
        g.setRiskLevel(riskLevel);
        g.setPriority(priority);
        g.setNotes(notes);
        goalService.save(user, g);
        ra.addFlashAttribute("success", "目标「" + goalName + "」已更新");
        return "redirect:/goals/" + id;
    }

    @PostMapping("/{id}/link-plan")
    public String linkPlan(@PathVariable Long id, @RequestParam Long planId,
                           HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        InvestmentGoal g = goalService.getById(id);
        if (g == null || !g.getUser().getId().equals(user.getId())) return "redirect:/goals";
        dcaService.linkGoal(planId, g);
        ra.addFlashAttribute("success", "定投计划已关联到目标");
        return "redirect:/goals/" + id;
    }

    @PostMapping("/{id}/unlink-plan")
    public String unlinkPlan(@PathVariable Long id, @RequestParam Long planId,
                             HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        InvestmentGoal g = goalService.getById(id);
        if (g == null || !g.getUser().getId().equals(user.getId())) return "redirect:/goals";
        dcaService.linkGoal(planId, null);
        ra.addFlashAttribute("success", "已解除关联");
        return "redirect:/goals/" + id;
    }

    @GetMapping("/{id}/delete")
    public String delete(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        InvestmentGoal g = goalService.getById(id);
        if (g != null && g.getUser().getId().equals(user.getId())) {
            goalService.delete(id);
            ra.addFlashAttribute("success", "目标已删除");
        }
        return "redirect:/goals";
    }

    /** AI 分析接口（异步，前端 fetch 调用） */
    @GetMapping("/api/{id}/ai-analysis")
    @ResponseBody
    public String aiAnalysis(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        InvestmentGoal g = goalService.getById(id);
        if (g == null || !g.getUser().getId().equals(user.getId()))
            return "> 无权访问";
        return aiService.generateGoalAdvice(user, g,
                goalService.completionRate(g),
                goalService.achieveProbability(g),
                goalService.monthlyGap(g));
    }
}
