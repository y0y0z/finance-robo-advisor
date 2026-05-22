package org.example.finance.service;

import org.example.finance.model.Stock;
import org.example.finance.model.Warning;
import org.example.finance.repository.WarningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预警自动触发引擎
 * 每分钟扫描所有 ACTIVE 状态的预警，将实时价格与阈值对比，一旦触及：
 *   1. 更新预警状态和触发记录
 *   2. 发送 QQ 邮件通知到用户注册邮箱
 *   3. 将触发信息推入前端弹窗队列（按用户ID隔离）
 */
@Service
public class WarningCheckService {

    private static final Logger log = LoggerFactory.getLogger(WarningCheckService.class);

    private final WarningRepository warningRepository;
    private final StockService stockService;
    private final EmailService emailService;

    /**
     * 前端弹窗队列：key=userId, value=待弹出的预警列表
     * 前端轮询 /api/warnings/pending 后，服务端清空该用户的队列。
     */
    private final Map<Long, List<Map<String, String>>> pendingNotifications =
            new ConcurrentHashMap<>();

    public WarningCheckService(WarningRepository warningRepository,
                               StockService stockService,
                               EmailService emailService) {
        this.warningRepository = warningRepository;
        this.stockService = stockService;
        this.emailService = emailService;
    }

    /**
     * 每分钟执行一次预警检测（延迟30秒，确保价格已被 StockPriceUpdateService 更新完毕）
     */
    @Scheduled(fixedRateString = "${schedule.warning-check.fixed-rate}", initialDelayString = "${schedule.warning-check.initial-delay}")
    public void checkWarnings() {
        List<Warning> activeWarnings = warningRepository.findByStatus("ACTIVE");
        log.info("开始预警检测，当前监控中预警数量: {}", activeWarnings.size());

        for (Warning warning : activeWarnings) {
            checkSingleWarning(warning);
        }

        log.info("预警检测完成");
    }

    /** 对单条预警执行阈值检测 */
    private void checkSingleWarning(Warning warning) {
        Stock stock = stockService.getStockByCode(warning.getCode());
        if (stock == null) {
            log.debug("预警 [{}({})] 未找到对应股票，跳过", warning.getName(), warning.getCode());
            return;
        }

        BigDecimal currentPrice = stock.getPrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        String triggeredStatus = detectTrigger(warning, currentPrice);

        if (triggeredStatus != null) {
            warning.setStatus(triggeredStatus);
            warning.setTriggeredPrice(currentPrice);
            warning.setTriggeredTime(new Date());
            warning.setUpdateTime(new Date());
            warningRepository.save(warning);

            log.warn("预警触发！[{}({})]) 状态={} 当前价={} 触发阈值={}",
                    warning.getName(), warning.getCode(),
                    triggeredStatus, currentPrice,
                    getTriggerThreshold(warning, triggeredStatus));

            // 1. 发送邮件通知
            if (warning.getUser() != null && warning.getUser().getEmail() != null) {
                emailService.sendWarningEmail(warning.getUser().getEmail(), warning);
            }

            // 2. 推入前端弹窗队列
            pushNotification(warning, triggeredStatus, currentPrice);
        }
    }

    /**
     * 将触发的预警推入该用户的弹窗队列（供 SmartWarningService 复用）
     */
    public void pushNotification(Warning warning, String status, BigDecimal price) {
        if (warning.getUser() == null) return;

        Long userId = warning.getUser().getId();
        String statusLabel = switch (status) {
            case "LOSS"    -> "止损触发";
            case "PROFIT"  -> "止盈触发";
            case "WARNING" -> "价格预警";
            default        -> status;
        };

        Map<String, String> notification = new HashMap<>();
        notification.put("name",    warning.getName());
        notification.put("code",    warning.getCode());
        notification.put("status",  status);
        notification.put("label",   statusLabel);
        notification.put("price",   price.toPlainString());
        notification.put("meaning", warning.getMeaning() != null ? warning.getMeaning() : "");

        pendingNotifications
                .computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(notification);
    }

    /**
     * 前端轮询接口：取出该用户的所有待弹出通知，并清空队列
     */
    public List<Map<String, String>> pollNotifications(Long userId) {
        List<Map<String, String>> notifications = pendingNotifications.remove(userId);
        return notifications != null ? notifications : Collections.emptyList();
    }

    /**
     * 判断是否触发预警，返回触发类型，未触发返回 null
     * 优先级：止损 > 止盈 > 警告线
     */
    private String detectTrigger(Warning warning, BigDecimal currentPrice) {
        if (warning.getStopLossPoint() != null
                && warning.getStopLossPoint().compareTo(BigDecimal.ZERO) > 0
                && currentPrice.compareTo(warning.getStopLossPoint()) <= 0) {
            return "LOSS";
        }
        if (warning.getStopProfitPoint() != null
                && warning.getStopProfitPoint().compareTo(BigDecimal.ZERO) > 0
                && currentPrice.compareTo(warning.getStopProfitPoint()) >= 0) {
            return "PROFIT";
        }
        if (warning.getWarningPoint() != null
                && warning.getWarningPoint().compareTo(BigDecimal.ZERO) > 0
                && currentPrice.compareTo(warning.getWarningPoint()) <= 0) {
            return "WARNING";
        }
        return null;
    }

    private BigDecimal getTriggerThreshold(Warning warning, String status) {
        return switch (status) {
            case "LOSS"    -> warning.getStopLossPoint();
            case "PROFIT"  -> warning.getStopProfitPoint();
            case "WARNING" -> warning.getWarningPoint();
            default        -> BigDecimal.ZERO;
        };
    }
}
