package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.ModelKeys;
import org.example.finance.constant.Routes;
import org.example.finance.constant.SessionKeys;
import org.example.finance.constant.Views;
import org.example.finance.model.User;
import org.example.finance.model.Warning;
import org.example.finance.service.StockService;
import org.example.finance.service.WarningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Controller
@RequestMapping("/warnings")
public class WarningController {

    private static final Logger log = LoggerFactory.getLogger(WarningController.class);

    private final WarningService warningService;
    private final StockService stockService;

    public WarningController(WarningService warningService, StockService stockService) {
        this.warningService = warningService;
        this.stockService = stockService;
    }

    @GetMapping
    public String warnings(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        model.addAttribute(ModelKeys.WARNINGS, warningService.getWarningsByUser(user));
        return Views.WARNINGS;
    }

    @GetMapping("/add")
    public String addWarning() {
        return Views.WARNINGS_ADD;
    }

    @PostMapping("/add")
    public String submitAddWarning(@RequestParam String type,
                                   @RequestParam String name,
                                   @RequestParam String code,
                                   @RequestParam(required = false) BigDecimal warningPoint,
                                   @RequestParam String meaning,
                                   @RequestParam(required = false) BigDecimal stopProfitPoint,
                                   @RequestParam(required = false) BigDecimal stopLossPoint,
                                   HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        stockService.ensureStockExists(user, code, name, type);
        log.info("用户 [{}] 新增预警: {} ({})", user.getName(), name, code);

        Warning warning = new Warning();
        warning.setUser(user);
        warning.setType(type);
        warning.setName(name);
        warning.setCode(code);
        warning.setWarningPoint(warningPoint);
        warning.setMeaning(meaning);
        warning.setStopProfitPoint(stopProfitPoint);
        warning.setStopLossPoint(stopLossPoint);

        warningService.saveWarning(warning);
        return Routes.redirectTo(Routes.WARNINGS);
    }

    @GetMapping("/edit/{id}")
    public String editWarning(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Warning warning = warningService.getWarningById(id);
        if (warning == null || !warning.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.WARNINGS);
        }

        model.addAttribute(ModelKeys.WARNING, warning);
        return Views.WARNINGS_EDIT;
    }

    @PostMapping("/edit/{id}")
    public String submitEditWarning(@PathVariable Long id,
                                    @RequestParam String type,
                                    @RequestParam String name,
                                    @RequestParam String code,
                                    @RequestParam(required = false) BigDecimal warningPoint,
                                    @RequestParam String meaning,
                                    @RequestParam(required = false) BigDecimal stopProfitPoint,
                                    @RequestParam(required = false) BigDecimal stopLossPoint,
                                    HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Warning warning = warningService.getWarningById(id);
        if (warning == null || !warning.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.WARNINGS);
        }

        warning.setType(type);
        warning.setName(name);
        warning.setCode(code);
        warning.setWarningPoint(warningPoint);
        warning.setMeaning(meaning);
        warning.setStopProfitPoint(stopProfitPoint);
        warning.setStopLossPoint(stopLossPoint);

        warningService.saveWarning(warning);
        return Routes.redirectTo(Routes.WARNINGS);
    }

    @GetMapping("/delete/{id}")
    public String deleteWarning(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Warning warning = warningService.getWarningById(id);
        if (warning != null && warning.getUser().getId().equals(user.getId())) {
            warningService.deleteWarning(id);
        }
        return Routes.redirectTo(Routes.WARNINGS);
    }

    @GetMapping("/resolve/{id}")
    public String resolveWarning(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Warning warning = warningService.getWarningById(id);
        if (warning != null && warning.getUser().getId().equals(user.getId())) {
            warningService.resolveWarning(id);
        }
        return Routes.redirectTo(Routes.WARNINGS);
    }

    @GetMapping("/reactivate/{id}")
    public String reactivateWarning(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Warning warning = warningService.getWarningById(id);
        if (warning != null && warning.getUser().getId().equals(user.getId())) {
            warningService.reactivateWarning(id);
        }
        return Routes.redirectTo(Routes.WARNINGS);
    }
}
