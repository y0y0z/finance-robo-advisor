package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.ModelKeys;
import org.example.finance.constant.Routes;
import org.example.finance.constant.SessionKeys;
import org.example.finance.constant.Views;
import org.example.finance.model.NetValueSnapshot;
import org.example.finance.model.User;
import org.example.finance.service.AssetService;
import org.example.finance.service.DcaService;
import org.example.finance.service.NetValueSnapshotService;
import org.example.finance.service.StockService;
import org.example.finance.service.WarningService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class HomeController {

    private final AssetService assetService;
    private final StockService stockService;
    private final WarningService warningService;
    private final NetValueSnapshotService snapshotService;
    private final DcaService dcaService;

    public HomeController(AssetService assetService,
                          StockService stockService,
                          WarningService warningService,
                          NetValueSnapshotService snapshotService,
                          DcaService dcaService) {
        this.assetService = assetService;
        this.stockService = stockService;
        this.warningService = warningService;
        this.snapshotService = snapshotService;
        this.dcaService = dcaService;
    }

    /** 根路径重定向到仪表盘（拦截器已保证已登录） */
    @GetMapping("/")
    public String home() {
        return Routes.redirectTo(Routes.DASHBOARD);
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        boolean hasAssets = assetService.hasAssets(user);
        model.addAttribute(ModelKeys.HAS_ASSETS, hasAssets);

        if (hasAssets) {
            model.addAttribute(ModelKeys.ASSETS, assetService.getUserAssets(user));
            model.addAttribute(ModelKeys.ASSET_ALLOCATION, assetService.calculateAssetAllocation(user));
            model.addAttribute(ModelKeys.TOTAL_ASSET_VALUE, assetService.calculateTotalAssetValue(user));
            model.addAttribute(ModelKeys.TOTAL_COST, assetService.calculateTotalCost(user));
            model.addAttribute(ModelKeys.UNREALIZED_RETURN, assetService.calculateUnrealizedReturn(user));
            model.addAttribute(ModelKeys.REALIZED_RETURN, assetService.calculateRealizedReturn(user));
            model.addAttribute(ModelKeys.TOTAL_RETURN, assetService.calculateTotalReturn(user));
            model.addAttribute(ModelKeys.RETURN_RATE, assetService.calculateReturnRate(user));
        }

        model.addAttribute(ModelKeys.STOCKS, stockService.getStocksByUser(user));
        model.addAttribute(ModelKeys.WARNINGS, warningService.getWarningsByUser(user));
        model.addAttribute(ModelKeys.TRIGGERED_WARNINGS, warningService.getTriggeredWarningsByUser(user));
        model.addAttribute(ModelKeys.DCA_DUE_PLANS, dcaService.getDuePlans(user));

        return Views.DASHBOARD;
    }

    /** 净值历史数据接口（供前端图表使用） */
    @GetMapping("/api/net-value-history")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> netValueHistory(HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        if (user == null) return ResponseEntity.status(401).build();

        List<NetValueSnapshot> snapshots = snapshotService.getSnapshots(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates",  snapshots.stream().map(s -> s.getSnapshotDate().toString()).toList());
        result.put("values", snapshots.stream().map(NetValueSnapshot::getTotalValue).toList());
        result.put("costs",  snapshots.stream().map(NetValueSnapshot::getTotalCost).toList());
        return ResponseEntity.ok(result);
    }
}
