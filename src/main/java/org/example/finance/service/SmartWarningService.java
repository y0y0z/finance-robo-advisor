package org.example.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.example.finance.model.*;
import org.example.finance.repository.UserRepository;
import org.example.finance.repository.WarningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.example.finance.config.DeepSeekProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 智能预警引擎
 * 每30分钟运行一次，对所有用户执行三类智能预警检测：
 *   1. 持仓风险预警   — 浮亏超过 -5% / -10% / -15% 时告警
 *   2. 异常波动预警   — 单日涨跌幅超过 ±5% / ±8% / ±10% 时告警
 *   3. AI新闻情绪预警 — DeepSeek 评分新闻情绪，持仓标的情绪得分 < -0.5 时告警
 *
 * 触发后：写入 Warning 表（aiGenerated=true）+ 推弹窗 + 发邮件
 */
@Service
public class SmartWarningService {

    private static final Logger log = LoggerFactory.getLogger(SmartWarningService.class);

    // 未处理状态列表（用于防重查询）
    private static final List<String> ACTIVE_STATUSES = List.of("ACTIVE", "WARNING", "LOSS", "PROFIT");

    @Autowired private UserRepository userRepository;
    @Autowired private WarningRepository warningRepository;
    @Autowired private AssetService assetService;
    @Autowired private StockService stockService;
    @Autowired private NewsService newsService;
    @Autowired private EmailService emailService;
    @Autowired private WarningCheckService warningCheckService;

    @Autowired private DeepSeekProperties deepSeekProps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 内存冷却记录：防止同一用户+代码+类型在短时间内重复触发
     * key = "userId:code:type"，value = 上次触发时间戳
     */
    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    // ========== 定时任务入口 ==========

    /**
     * 每30分钟执行，初始延迟5分钟（等价格数据就绪）
     */
    @Scheduled(fixedRateString = "${schedule.smart-warning.fixed-rate}", initialDelayString = "${schedule.smart-warning.initial-delay}")
    public void runSmartWarningCheck() {
        List<User> users = userRepository.findAll();
        log.info("[智能预警] 开始扫描，用户数: {}", users.size());
        for (User user : users) {
            try {
                checkPositionRisk(user);
                checkAbnormalVolatility(user);
                checkAiNewsSentiment(user);
            } catch (Exception e) {
                log.error("[智能预警] 用户 [{}] 扫描失败", user.getName(), e);
            }
        }
        log.info("[智能预警] 扫描完成");
    }

    // ========== 1. 持仓风险预警 ==========

    /**
     * 对用户每个持仓计算当前浮亏比例，超过阈值则触发预警
     * 阈值：-5%(轻度) / -10%(中度) / -15%(重度)
     */
    private void checkPositionRisk(User user) {
        List<Asset> assets = assetService.getUserAssets(user);
        for (Asset asset : assets) {
            if (asset.getCode() == null || asset.getPurchasePrice() == null
                    || asset.getCurrentPrice() == null) continue;
            if (asset.getPurchasePrice().compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal pct = asset.getCurrentPrice()
                    .subtract(asset.getPurchasePrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(asset.getPurchasePrice(), 2, RoundingMode.HALF_UP);

            String riskLevel = null;
            String meaning   = null;
            if (pct.compareTo(BigDecimal.valueOf(-15)) <= 0) {
                riskLevel = "POSITION_RISK_HIGH";
                meaning   = "⚠️ 重度风险：持仓浮亏已超 15%，建议评估是否止损";
            } else if (pct.compareTo(BigDecimal.valueOf(-10)) <= 0) {
                riskLevel = "POSITION_RISK_MID";
                meaning   = "⚠️ 中度风险：持仓浮亏已超 10%，请关注仓位风险";
            } else if (pct.compareTo(BigDecimal.valueOf(-5)) <= 0) {
                riskLevel = "POSITION_RISK_LOW";
                meaning   = "持仓浮亏已超 5%，建议关注";
            }

            if (riskLevel == null) continue;

            // 防重：同一用户+代码+含义 已有未处理记录则跳过
            if (hasPendingAiWarning(user, asset.getCode(), meaning)) continue;
            // 冷却：同级别 4 小时内只推一次
            if (!canTrigger(user.getId(), asset.getCode(), riskLevel, 4 * 3600 * 1000L)) continue;

            String reason = String.format(
                    "买入价 %s 元，当前价 %s 元，浮亏 %s%%。AI 智能引擎检测到持仓风险。",
                    asset.getPurchasePrice(), asset.getCurrentPrice(), pct);

            Warning w = buildAiWarning(user, asset.getType(), asset.getName(),
                    asset.getCode(), meaning, reason, asset.getCurrentPrice());
            warningRepository.save(w);

            log.warn("[智能预警-持仓风险] 用户={} 代码={} 浮亏={}%", user.getName(), asset.getCode(), pct);
            notifyUser(user, w);
        }
    }

    // ========== 2. 异常波动预警 ==========

    /**
     * 对用户关注列表中每只标的，检查单日涨跌幅绝对值
     * 阈值：±5%(异常) / ±8%(强烈) / ±10%(极端)
     */
    private void checkAbnormalVolatility(User user) {
        List<Stock> stocks = stockService.getStocksByUser(user);
        for (Stock stock : stocks) {
            if (stock.getChangePercent() == null) continue;

            BigDecimal absPct = stock.getChangePercent().abs();
            String level   = null;
            String meaning = null;

            if (absPct.compareTo(BigDecimal.valueOf(10)) >= 0) {
                level   = "VOLATILITY_EXTREME";
                meaning = "🚨 极端波动：今日涨跌幅超 ±10%，市场情绪剧烈，需高度警惕";
            } else if (absPct.compareTo(BigDecimal.valueOf(8)) >= 0) {
                level   = "VOLATILITY_STRONG";
                meaning = "⚡ 强烈波动：今日涨跌幅超 ±8%，可能有重大消息面影响";
            } else if (absPct.compareTo(BigDecimal.valueOf(5)) >= 0) {
                level   = "VOLATILITY_ABNORMAL";
                meaning = "今日涨跌幅超 ±5%，属于异常波动，请留意相关公告";
            }

            if (level == null) continue;
            if (hasPendingAiWarning(user, stock.getCode(), meaning)) continue;
            // 冷却：同日内只推一次（8小时）
            if (!canTrigger(user.getId(), stock.getCode(), level, 8 * 3600 * 1000L)) continue;

            String direction = stock.getChangePercent().compareTo(BigDecimal.ZERO) > 0 ? "上涨" : "下跌";
            String reason = String.format(
                    "今日%s %.2f%%，当前价 %s 元。AI 智能引擎检测到异常波动。",
                    direction, absPct, stock.getPrice());

            Warning w = buildAiWarning(user, stock.getType(), stock.getName(),
                    stock.getCode(), meaning, reason, stock.getPrice());
            warningRepository.save(w);

            log.warn("[智能预警-异常波动] 用户={} 代码={} 涨跌幅={}%",
                    user.getName(), stock.getCode(), stock.getChangePercent());
            notifyUser(user, w);
        }
    }

    // ========== 3. AI 新闻情绪预警 ==========

    /**
     * 对用户实际持仓的标的（Asset 表），抓取相关新闻，
     * 调用 DeepSeek 做情绪评分，评分 < -0.5 时触发预警。
     * 每个标的每 2 小时最多分析一次，避免刷爆 API。
     */
    private void checkAiNewsSentiment(User user) {
        List<Asset> assets = assetService.getUserAssets(user);
        for (Asset asset : assets) {
            if (asset.getCode() == null) continue;
            // 冷却：2 小时
            if (!canTrigger(user.getId(), asset.getCode(), "SENTIMENT", 2 * 3600 * 1000L)) continue;

            List<News> allNews = newsService.getAllNews(user);
            String name = asset.getName();
            String code = asset.getCode();
            List<News> related = allNews.stream()
                    .filter(n -> {
                        String title = n.getTitle() != null ? n.getTitle() : "";
                        String kw    = n.getKeyword() != null ? n.getKeyword() : "";
                        return title.contains(name) || title.contains(code)
                                || kw.contains(name) || kw.contains(code);
                    })
                    .limit(10)
                    .toList();

            if (related.isEmpty()) {
                log.debug("[智能预警-情绪] 用户={} 代码={} 无相关新闻，跳过", user.getName(), code);
                continue;
            }

            try {
                SentimentResult result = callSentimentApi(name, code, related);
                log.info("[智能预警-情绪] 用户={} 代码={} 情绪评分={} 理由={}",
                        user.getName(), code, result.score, result.reason);

                if (result.score < -0.5) {
                    String meaning = "🔴 AI情绪预警：近期相关新闻情绪偏负面，持仓风险升高";
                    if (hasPendingAiWarning(user, code, meaning)) continue;

                    String reason = String.format(
                            "情绪得分 %.2f（-1为极度负面）。AI分析：%s", result.score, result.reason);

                    Warning w = buildAiWarning(user, asset.getType(), name,
                            code, meaning, reason, asset.getCurrentPrice());
                    warningRepository.save(w);

                    log.warn("[智能预警-情绪] 触发！用户={} 代码={} 得分={}", user.getName(), code, result.score);
                    notifyUser(user, w);
                }
            } catch (Exception e) {
                log.error("[智能预警-情绪] 用户={} 代码={} AI调用失败: {}", user.getName(), code, e.getMessage());
            }
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 构建 AI 生成的 Warning 实体（status 直接置为 WARNING，无需二次检测）
     */
    private Warning buildAiWarning(User user, String type, String name, String code,
                                   String meaning, String aiReason, BigDecimal currentPrice) {
        Warning w = new Warning();
        w.setUser(user);
        w.setType(type != null ? type : "股票");
        w.setName(name);
        w.setCode(code);
        w.setMeaning(meaning);
        w.setAiGenerated(true);
        w.setAiReason(aiReason);
        w.setStatus("WARNING");
        w.setTriggeredPrice(currentPrice);
        w.setTriggeredTime(new Date());
        w.setCreateTime(new Date());
        w.setUpdateTime(new Date());
        return w;
    }

    /**
     * 推弹窗 + 发邮件
     */
    private void notifyUser(User user, Warning w) {
        // 前端弹窗
        warningCheckService.pushNotification(w, "WARNING", w.getTriggeredPrice());
        // 邮件通知
        if (user.getEmail() != null) {
            emailService.sendWarningEmail(user.getEmail(), w);
        }
    }

    /**
     * 查数据库：该用户+代码+含义 是否已有未处理的 AI 预警
     */
    private boolean hasPendingAiWarning(User user, String code, String meaning) {
        return warningRepository.existsByUserAndCodeAndMeaningAndStatusIn(user, code, meaning, ACTIVE_STATUSES);
    }

    /**
     * 内存冷却判断：同一 key 在 cooldownMs 内只允许触发一次
     */
    private boolean canTrigger(Long userId, String code, String type, long cooldownMs) {
        String key = userId + ":" + code + ":" + type;
        long now   = System.currentTimeMillis();
        Long last  = cooldownMap.get(key);
        if (last != null && now - last < cooldownMs) return false;
        cooldownMap.put(key, now);
        return true;
    }

    // ========== DeepSeek 情绪分析 ==========

    private record SentimentResult(double score, String reason) {}

    /**
     * 调用 DeepSeek 对新闻标题列表做情绪评分
     * 返回 score（-1~1，-1极度负面）和 reason（50字以内）
     */
    private SentimentResult callSentimentApi(String name, String code, List<News> news) throws Exception {
        if (isBlank(deepSeekProps.getKey()) || isBlank(deepSeekProps.getUrl()) || isBlank(deepSeekProps.getModel())) {
            throw new IllegalStateException("DeepSeek API 未配置，请设置 DEEPSEEK_API_KEY");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个金融情绪分析模型。以下是关于【").append(name).append("（").append(code).append("）】的近期新闻标题：\n");
        news.forEach(n -> sb.append("- ").append(n.getTitle()).append("\n"));
        sb.append("\n请输出一个 JSON 对象（不要输出任何其他文字）：\n");
        sb.append("{\"score\": <-1.0到1.0的浮点数，-1极度负面，0中性，1极度正面>, \"reason\": \"<50字以内的中文分析理由>\"}");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(deepSeekProps.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(deepSeekProps.getTimeout(), TimeUnit.SECONDS)
                .build();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", deepSeekProps.getModel());
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", sb.toString());
        messages.add(msg);
        body.set("messages", messages);
        body.put("temperature", 0.1);  // 低温度保证输出稳定
        body.put("max_tokens", 100);

        RequestBody reqBody = RequestBody.create(
                objectMapper.writeValueAsString(body),
                MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(deepSeekProps.getUrl())
                .addHeader("Authorization", "Bearer " + deepSeekProps.getKey())
                .post(reqBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throw new RuntimeException("HTTP " + response.code() + " " + raw);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new RuntimeException("DeepSeek 返回空 choices: " + raw);
            }
            String content = choices.get(0).path("message").path("content").asText();

            // 提取 JSON（有时模型会在 JSON 前后加换行或反引号）
            int start = content.indexOf('{');
            int end   = content.lastIndexOf('}');
            if (start < 0 || end < 0) throw new RuntimeException("无法解析情绪 JSON: " + content);
            JsonNode result = objectMapper.readTree(content.substring(start, end + 1));
            double score  = result.path("score").asDouble(0.0);
            String reason = result.path("reason").asText("无分析理由");
            return new SentimentResult(score, reason);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
