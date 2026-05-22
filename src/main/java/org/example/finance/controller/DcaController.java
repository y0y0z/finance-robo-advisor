package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.DcaConstants;
import org.example.finance.constant.DcaFrequency;
import org.example.finance.constant.SessionKeys;
import org.example.finance.model.DcaPlan;
import org.example.finance.model.DcaRecord;
import org.example.finance.model.User;
import org.example.finance.service.DcaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.example.finance.service.DcaSchedulerService;
import org.example.finance.service.NavFetchService;
import org.example.finance.vo.DcaPlanView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/dca")
public class DcaController {

    private static final Logger log = LoggerFactory.getLogger(DcaController.class);

    @Autowired private DcaService dcaService;
    @Autowired private NavFetchService navFetchService;
    @Autowired private DcaSchedulerService dcaSchedulerService;

    // ── 计划列表 ──────────────────────────────────
    @GetMapping
    public String index(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        List<DcaPlanView> plans = dcaService.getPlanViewsByUser(user);
        model.addAttribute("plans", plans);
        return "dca";
    }

    // ── 添加计划 ──────────────────────────────────
    @GetMapping("/add")
    public String addForm() {
        return "dca-add";
    }

    @PostMapping("/add")
    public String submitAdd(@RequestParam String code,
                            @RequestParam String name,
                            @RequestParam BigDecimal amount,
                            @RequestParam String frequency,
                            @RequestParam(required = false) Integer dayOfMonth,
                            @RequestParam LocalDate startDate,
                            @RequestParam(required = false) String notes,
                            HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        DcaPlan plan = new DcaPlan();
        plan.setUser(user);
        plan.setCode(code.trim());
        plan.setName(name.trim());
        plan.setAmount(amount);
        plan.setFrequency(frequency);
        plan.setDayOfMonth(DcaFrequency.MONTHLY.equals(frequency) ? dayOfMonth : null);
        plan.setStartDate(startDate);
        plan.setNotes(notes);
        dcaService.savePlan(plan);

        log.info("用户 [{}] 新增定投计划: {} ({})", user.getName(), name, code);
        ra.addFlashAttribute("success", "定投计划「" + name + "」已创建");
        return "redirect:/dca";
    }

    // ── 执行定投 ──────────────────────────────────
    @GetMapping("/{id}/execute")
    public String executeForm(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        DcaPlan plan = dcaService.getPlanById(id);
        if (plan == null || !plan.getUser().getId().equals(user.getId())) {
            return "redirect:/dca";
        }
        model.addAttribute("plan", plan);
        model.addAttribute("today", LocalDate.now());
        return "dca-execute";
    }

    @PostMapping("/{id}/execute")
    public String submitExecute(@PathVariable Long id,
                                @RequestParam BigDecimal actualAmount,
                                @RequestParam BigDecimal nav,
                                @RequestParam LocalDate executeDate,
                                @RequestParam(required = false) String notes,
                                HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        DcaPlan plan = dcaService.getPlanById(id);
        if (plan == null || !plan.getUser().getId().equals(user.getId())) {
            return "redirect:/dca";
        }

        dcaService.execute(plan, actualAmount, nav, executeDate, notes, user);
        ra.addFlashAttribute("success",
                "已记录本期定投：" + plan.getName() + " " + actualAmount + " 元");
        return "redirect:/dca";
    }

    // ── 历史记录（含收益计算） ──────────────────────
    @GetMapping("/{id}/records")
    public String records(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        DcaPlan plan = dcaService.getPlanById(id);
        if (plan == null || !plan.getUser().getId().equals(user.getId())) {
            return "redirect:/dca";
        }

        List<DcaRecord> records  = dcaService.getRecordsByPlan(plan);
        BigDecimal totalInvested = dcaService.totalInvested(plan);
        BigDecimal totalShares   = dcaService.totalShares(plan);
        BigDecimal avgCost       = dcaService.avgCost(plan);

        // 当前净值（抓取失败则为 null，页面显示 "--"）
        NavFetchService.FundNav fundNav = navFetchService.fetchNav(plan.getCode());
        BigDecimal currentNav  = fundNav != null ? fundNav.nav()  : null;
        LocalDate  navDate     = fundNav != null ? fundNav.navDate() : null;

        BigDecimal currentValue = null;
        BigDecimal profitLoss   = null;
        BigDecimal returnRate   = null;
        if (currentNav != null && totalShares.compareTo(BigDecimal.ZERO) > 0) {
            currentValue = totalShares.multiply(currentNav).setScale(DcaConstants.AMOUNT_SCALE, RoundingMode.HALF_UP);
            profitLoss   = currentValue.subtract(totalInvested).setScale(DcaConstants.AMOUNT_SCALE, RoundingMode.HALF_UP);
            returnRate   = profitLoss.divide(totalInvested, DcaConstants.RATE_CALC_SCALE, RoundingMode.HALF_UP)
                                     .multiply(DcaConstants.PERCENT_MULTIPLIER)
                                     .setScale(DcaConstants.AMOUNT_SCALE, RoundingMode.HALF_UP);
        }

        model.addAttribute("plan",          plan);
        model.addAttribute("records",       records);
        model.addAttribute("totalInvested", totalInvested);
        model.addAttribute("totalPeriods",  dcaService.totalPeriods(plan));
        model.addAttribute("totalShares",   totalShares);
        model.addAttribute("avgCost",       avgCost);
        model.addAttribute("currentNav",    currentNav);
        model.addAttribute("navDate",       navDate);
        model.addAttribute("currentValue",  currentValue);
        model.addAttribute("profitLoss",    profitLoss);
        model.addAttribute("returnRate",    returnRate);
        return "dca-records";
    }

    // ── 编辑计划 ──────────────────────────────────
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        DcaPlan plan = dcaService.getPlanById(id);
        if (plan == null || !plan.getUser().getId().equals(user.getId())) {
            return "redirect:/dca";
        }
        model.addAttribute("plan", plan);
        return "dca-edit";
    }

    @PostMapping("/{id}/edit")
    public String submitEdit(@PathVariable Long id,
                             @RequestParam String name,
                             @RequestParam BigDecimal amount,
                             @RequestParam String frequency,
                             @RequestParam(required = false) Integer dayOfMonth,
                             @RequestParam(required = false) String notes,
                             HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        DcaPlan plan = dcaService.getPlanById(id);
        if (plan == null || !plan.getUser().getId().equals(user.getId())) {
            return "redirect:/dca";
        }
        plan.setName(name.trim());
        plan.setAmount(amount);
        plan.setFrequency(frequency);
        plan.setDayOfMonth(DcaFrequency.MONTHLY.equals(frequency) ? dayOfMonth : null);
        plan.setNotes(notes);
        dcaService.savePlan(plan);

        log.info("用户 [{}] 编辑定投计划: {} ({})", user.getName(), plan.getName(), plan.getCode());
        ra.addFlashAttribute("success", "定投计划「" + plan.getName() + "」已更新");
        return "redirect:/dca";
    }

    // ── 暂停/恢复 ─────────────────────────────────
    @GetMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        DcaPlan plan = dcaService.getPlanById(id);
        if (plan != null && plan.getUser().getId().equals(user.getId())) {
            dcaService.toggleStatus(plan);
        }
        return "redirect:/dca";
    }

    // ── 删除计划 ──────────────────────────────────
    @GetMapping("/{id}/delete")
    public String delete(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        DcaPlan plan = dcaService.getPlanById(id);
        if (plan != null && plan.getUser().getId().equals(user.getId())) {
            dcaService.deletePlan(id);
            ra.addFlashAttribute("success", "定投计划已删除");
        }
        return "redirect:/dca";
    }

    // ── 手动触发：立即执行所有到期计划（补执行入口）──
    @GetMapping("/run-now")
    public String runNow(HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        log.info("用户 [{}] 手动触发定投调度", user.getName());
        dcaSchedulerService.runNow();
        ra.addFlashAttribute("success", "已触发定投执行，请稍后刷新查看结果");
        return "redirect:/dca";
    }
}
