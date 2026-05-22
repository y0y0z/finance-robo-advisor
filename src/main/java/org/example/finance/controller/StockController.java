package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.ModelKeys;
import org.example.finance.constant.Routes;
import org.example.finance.constant.SessionKeys;
import org.example.finance.constant.Views;
import org.example.finance.model.Stock;
import org.example.finance.model.User;
import org.example.finance.service.StockService;
import org.example.finance.vo.StockVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Controller
@RequestMapping("/stocks")
public class StockController {

    private static final Logger log = LoggerFactory.getLogger(StockController.class);

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping
    public String stocks(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        List<StockVO> vos = stockService.getStocksByUser(user)
                .stream()
                .map(StockVO::new)
                .toList();
        model.addAttribute(ModelKeys.STOCKS, vos);
        return Views.STOCKS;
    }

    @GetMapping("/add")
    public String addStock() {
        return Views.STOCKS_ADD;
    }

    @PostMapping("/add")
    public String submitAddStock(@RequestParam String code,
                                 @RequestParam String name,
                                 @RequestParam String type,
                                 HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        if (stockService.getStockByUserAndCode(user, code) != null) {
            log.warn("用户 [{}] 重复添加代码: {}", user.getName(), code);
            return Routes.redirectTo(Routes.STOCKS_DUPLICATE);
        }

        stockService.ensureStockExists(user, code, name, type);
        log.info("用户 [{}] 添加关注: {} ({})", user.getName(), name, code);
        return Routes.redirectTo(Routes.STOCKS);
    }

    @GetMapping("/edit/{id}")
    public String editStock(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Stock stock = stockService.getStockById(id);
        if (stock == null || !ownsStock(stock, user)) {
            return Routes.redirectTo(Routes.STOCKS);
        }

        model.addAttribute(ModelKeys.STOCK, stock);
        return Views.STOCKS_EDIT;
    }

    @PostMapping("/edit/{id}")
    public String submitEditStock(@PathVariable Long id,
                                  @RequestParam String code,
                                  @RequestParam String name,
                                  @RequestParam String type,
                                  @RequestParam(required = false) BigDecimal pe,
                                  @RequestParam(required = false) BigDecimal pb,
                                  @RequestParam(required = false) BigDecimal nav,
                                  HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Stock stock = stockService.getStockById(id);
        if (stock == null || !ownsStock(stock, user)) {
            log.warn("用户 [{}] 尝试修改他人股票记录 id={}", user.getName(), id);
            return Routes.redirectTo(Routes.STOCKS);
        }

        // 只允许修改基础信息，price/changePercent 由定时任务维护
        stock.setCode(code);
        stock.setName(name);
        stock.setType(type);
        stock.setPe(pe);
        stock.setPb(pb);
        stock.setNav(nav);

        stockService.saveStock(stock);
        log.info("用户 [{}] 编辑关注: {} ({})", user.getName(), name, code);
        return Routes.redirectTo(Routes.STOCKS);
    }

    @GetMapping("/delete/{id}")
    public String deleteStock(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Stock stock = stockService.getStockById(id);
        if (stock == null || !ownsStock(stock, user)) {
            log.warn("用户 [{}] 尝试删除他人股票记录 id={}", user.getName(), id);
            return Routes.redirectTo(Routes.STOCKS);
        }

        stockService.deleteStock(id);
        log.info("用户 [{}] 移除关注: {} ({})", user.getName(), stock.getName(), stock.getCode());
        return Routes.redirectTo(Routes.STOCKS);
    }

    /** 所有权校验：stock.user 为空（系统级记录）或属于当前用户 */
    private boolean ownsStock(Stock stock, User user) {
        return stock.getUser() == null || stock.getUser().getId().equals(user.getId());
    }
}
