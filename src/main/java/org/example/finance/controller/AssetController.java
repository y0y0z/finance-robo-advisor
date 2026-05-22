package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.ModelKeys;
import org.example.finance.constant.Routes;
import org.example.finance.constant.SessionKeys;
import org.example.finance.constant.Views;
import org.example.finance.model.Asset;
import org.example.finance.model.TradeRecord;
import org.example.finance.model.User;
import org.example.finance.service.AssetService;
import org.example.finance.service.StockService;
import org.example.finance.service.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.Date;

@Controller
@RequestMapping("/assets")
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);

    private final AssetService assetService;
    private final StockService stockService;
    private final TradeService tradeService;

    public AssetController(AssetService assetService,
                           StockService stockService,
                           TradeService tradeService) {
        this.assetService = assetService;
        this.stockService = stockService;
        this.tradeService = tradeService;
    }

    @GetMapping
    public String assets(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        if (!assetService.hasAssets(user)) {
            return Routes.redirectTo(Routes.ASSETS_INIT);
        }

        model.addAttribute(ModelKeys.ASSETS, assetService.getUserAssets(user));
        model.addAttribute(ModelKeys.ALLOCATION, assetService.calculateAssetAllocation(user));
        model.addAttribute(ModelKeys.TOTAL_VALUE, assetService.calculateTotalAssetValue(user));
        model.addAttribute(ModelKeys.TOTAL_RETURN, assetService.calculateTotalReturn(user));
        return Views.ASSETS;
    }

    @GetMapping("/init")
    public String initAssets(HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        if (assetService.hasAssets(user)) {
            return Routes.redirectTo(Routes.ASSETS);
        }
        return Views.ASSETS_INIT;
    }

    @PostMapping("/init")
    public String submitInitAssets(@RequestParam String type,
                                   @RequestParam String name,
                                   @RequestParam String code,
                                   @RequestParam BigDecimal amount,
                                   @RequestParam BigDecimal purchasePrice,
                                   HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        if (!"现金".equals(type) && code != null && !code.isBlank()) {
            stockService.ensureStockExists(user, code, name, type);
        }

        Asset asset = buildAsset(user, type, name, code, amount, purchasePrice);
        assetService.saveAsset(asset);
        log.info("用户 [{}] 初始化资产: {} ({})", user.getName(), name, code);
        return Routes.redirectTo(Routes.ASSETS);
    }

    @GetMapping("/add")
    public String addAsset() {
        return Views.ASSETS_ADD;
    }

    @PostMapping("/add")
    public String submitAddAsset(@RequestParam String type,
                                 @RequestParam String name,
                                 @RequestParam String code,
                                 @RequestParam BigDecimal amount,
                                 @RequestParam BigDecimal purchasePrice,
                                 HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        if (!"现金".equals(type) && code != null && !code.isBlank()) {
            stockService.ensureStockExists(user, code, name, type);
        }

        Asset asset = buildAsset(user, type, name, code, amount, purchasePrice);
        assetService.saveAsset(asset);
        log.info("用户 [{}] 添加资产: {} ({})", user.getName(), name, code);
        return Routes.redirectTo(Routes.ASSETS);
    }

    @GetMapping("/edit/{id}")
    public String editAsset(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Asset asset = assetService.getAssetById(id);
        if (asset == null || !asset.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }

        model.addAttribute(ModelKeys.ASSET, asset);
        return Views.ASSETS_EDIT;
    }

    @PostMapping("/edit/{id}")
    public String submitEditAsset(@PathVariable Long id,
                                  @RequestParam String type,
                                  @RequestParam String name,
                                  @RequestParam String code,
                                  @RequestParam BigDecimal amount,
                                  @RequestParam BigDecimal purchasePrice,
                                  @RequestParam BigDecimal currentPrice,
                                  HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Asset asset = assetService.getAssetById(id);
        if (asset == null || !asset.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }

        asset.setType(type);
        asset.setName(name);
        asset.setCode(code);
        asset.setAmount(amount);
        asset.setPurchasePrice(purchasePrice);
        asset.setCurrentPrice(currentPrice);
        asset.setTotalValue(amount.multiply(currentPrice));

        assetService.saveAsset(asset);
        return Routes.redirectTo(Routes.ASSETS);
    }

    @GetMapping("/delete/{id}")
    public String deleteAsset(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        Asset asset = assetService.getAssetById(id);
        if (asset != null && asset.getUser().getId().equals(user.getId())) {
            assetService.deleteAsset(id);
        }
        return Routes.redirectTo(Routes.ASSETS);
    }

    /** 构建资产对象（消除 init 和 add 中的重复代码） */
    private Asset buildAsset(User user, String type, String name,
                              String code, BigDecimal amount, BigDecimal purchasePrice) {
        // 基金：用户填的是"投入金额"，需换算为份额
        BigDecimal actualAmount = toActualQuantity(type, amount, purchasePrice);

        Asset asset = new Asset();
        asset.setUser(user);
        asset.setType(type);
        asset.setName(name);
        asset.setCode(code);
        asset.setAmount(actualAmount);
        asset.setPurchasePrice(purchasePrice);
        asset.setPurchaseDate(new Date());
        asset.setCurrentPrice(purchasePrice);
        asset.setTotalValue(actualAmount.multiply(purchasePrice));
        return asset;
    }

    /**
     * 基金类型：将"投入金额"换算为"份额"（向下保留2位小数，符合基金份额规则）
     * 其他类型：直接返回原值
     */
    private BigDecimal toActualQuantity(String type, BigDecimal inputAmount, BigDecimal price) {
        if ("基金".equals(type) && price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            return inputAmount.divide(price, 2, java.math.RoundingMode.DOWN);
        }
        return inputAmount;
    }

    // ─────────────────────────────────────────────────────────────
    // 交易记录：查看 / 买入 / 卖出
    // ─────────────────────────────────────────────────────────────

    /** 某资产的交易记录列表 */
    @GetMapping("/{id}/trades")
    public String tradeRecords(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        Asset asset = assetService.getAssetById(id);
        if (asset == null || !asset.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }
        model.addAttribute(ModelKeys.USER, user);
        model.addAttribute(ModelKeys.ASSET, asset);
        model.addAttribute("trades", tradeService.getTradesByAsset(asset));
        model.addAttribute("realizedPnl", tradeService.calcRealizedPnl(asset));
        return Views.TRADE_RECORDS;
    }

    /** 买入表单页 */
    @GetMapping("/{id}/buy")
    public String buyForm(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        Asset asset = assetService.getAssetById(id);
        if (asset == null || !asset.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }
        model.addAttribute(ModelKeys.USER, user);
        model.addAttribute(ModelKeys.ASSET, asset);
        return Views.TRADE_BUY;
    }

    /** 提交买入 */
    @PostMapping("/{id}/buy")
    public String submitBuy(@PathVariable Long id,
                            @RequestParam BigDecimal quantity,
                            @RequestParam BigDecimal price,
                            @RequestParam(required = false, defaultValue = "0") BigDecimal fee,
                            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date tradeDate,
                            @RequestParam(required = false) String remark,
                            HttpSession session,
                            RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        Asset asset = assetService.getAssetById(id);
        if (asset == null || !asset.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }
        // 基金：quantity 字段实为"投入金额"，换算为实际份额
        BigDecimal actualQty = toActualQuantity(asset.getType(), quantity, price);
        tradeService.buy(asset, actualQty, price, fee, tradeDate, remark);

        if ("基金".equals(asset.getType())) {
            log.info("用户 [{}] 买入基金 {} — 投入 {} 元 @ 净值 {}, 获得 {} 份",
                    user.getName(), asset.getName(), quantity, price, actualQty);
            ra.addFlashAttribute("success",
                    "买入成功！投入 " + quantity + " 元，获得 " + actualQty + " 份，新均价（净值）" + asset.getPurchasePrice() + " 元");
        } else {
            log.info("用户 [{}] 买入 {} × {} 股 @ {}", user.getName(), asset.getName(), actualQty, price);
            ra.addFlashAttribute("success", "买入成功！均价已更新为 " + asset.getPurchasePrice() + " 元");
        }
        return Routes.redirectTo("/assets/" + id + "/trades");
    }

    /** 卖出表单页 */
    @GetMapping("/{id}/sell")
    public String sellForm(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        Asset asset = assetService.getAssetById(id);
        if (asset == null || !asset.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }
        model.addAttribute(ModelKeys.USER, user);
        model.addAttribute(ModelKeys.ASSET, asset);
        return Views.TRADE_SELL;
    }

    /** 删除交易记录 */
    @GetMapping("/trades/{tradeId}/delete")
    public String deleteTrade(@PathVariable Long tradeId, HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        TradeRecord record = tradeService.getById(tradeId);
        if (record == null || !record.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }
        Long assetId = record.getAsset().getId();
        tradeService.deleteRecord(record);
        ra.addFlashAttribute("success", "交易记录已删除");
        return Routes.redirectTo("/assets/" + assetId + "/trades");
    }

    /** 修改交易记录备注/日期 */
    @PostMapping("/trades/{tradeId}/edit")
    public String editTrade(@PathVariable Long tradeId,
                            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date tradeDate,
                            @RequestParam(required = false) String remark,
                            HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        TradeRecord record = tradeService.getById(tradeId);
        if (record == null || !record.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }
        Long assetId = record.getAsset().getId();
        tradeService.updateRemark(record, tradeDate, remark);
        ra.addFlashAttribute("success", "交易记录已更新");
        return Routes.redirectTo("/assets/" + assetId + "/trades");
    }

    /** 提交卖出 */
    @PostMapping("/{id}/sell")
    public String submitSell(@PathVariable Long id,
                             @RequestParam BigDecimal quantity,
                             @RequestParam BigDecimal price,
                             @RequestParam(required = false, defaultValue = "0") BigDecimal fee,
                             @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") Date tradeDate,
                             @RequestParam(required = false) String remark,
                             HttpSession session,
                             RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        Asset asset = assetService.getAssetById(id);
        if (asset == null || !asset.getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.ASSETS);
        }
        try {
            BigDecimal pnl = tradeService.sell(asset, quantity, price, fee, tradeDate, remark);
            log.info("用户 [{}] 卖出 {} × {} 股 @ {}, 盈亏: {}", user.getName(), asset.getName(), quantity, price, pnl);
            String sign = pnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
            ra.addFlashAttribute("success", "卖出成功！本次盈亏：" + sign + pnl + " 元");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return Routes.redirectTo("/assets/" + id + "/sell");
        }
        return Routes.redirectTo("/assets/" + id + "/trades");
    }
}
