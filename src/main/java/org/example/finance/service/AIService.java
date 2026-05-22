package org.example.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.example.finance.model.AiAnalysisRecord;
import org.example.finance.model.Asset;
import org.example.finance.model.News;
import org.example.finance.model.Stock;
import org.example.finance.model.User;
import org.example.finance.model.Warning;
import org.example.finance.repository.AiAnalysisRecordRepository;
import org.example.finance.config.DeepSeekProperties;
import org.example.finance.exception.AiServiceUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * AI 投资分析编排服务。
 *
 * Prompt 构建由 AiPromptService 负责；本服务负责模型调用、多模型候选生成、
 * 匿名评审、评分解析、最终结果选择和分析记录保存。
 */
@Service
public class AIService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AIService.class);

    @Autowired
    private StockService stockService;
    @Autowired
    private NewsService newsService;
    @Autowired
    private AssetService assetService;
    @Autowired
    private WarningService warningService;
    @Autowired
    private AiAnalysisRecordRepository recordRepository;
    @Autowired
    private AiPromptService aiPromptService;

    @Autowired
    private DeepSeekProperties deepSeekProps;

    @Autowired
    private OkHttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${qwen.api.key:}")
    private String qwenKey;
    @Value("${qwen.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String qwenUrl;
    @Value("${qwen.api.model:qwen-plus}")
    private String qwenModel;

    @Value("${kimi.api.key:}")
    private String kimiKey;
    @Value("${kimi.api.url:https://api.moonshot.cn/v1/chat/completions}")
    private String kimiUrl;
    @Value("${kimi.api.model:kimi-k2-0711-preview}")
    private String kimiModel;

    @Value("${gemini.api.key:}")
    private String geminiKey;
    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}")
    private String geminiUrl;
    @Value("${gemini.api.model:gemini-2.5-flash}")
    private String geminiModel;

    /**
     * 保存 AI 分析记录到数据库，失败时仅打日志不影响主流程
     */
    private void saveRecord(User user, String type, String subject, String content, long durationMs) {
        try {
            AiAnalysisRecord record = new AiAnalysisRecord();
            record.setUser(user);
            record.setType(type);
            record.setSubject(subject);
            record.setContent(content);
            record.setDurationMs(durationMs);
            record.setCreatedAt(new Date());
            recordRepository.save(record);
        } catch (Exception e) {
            log.warn("AI记录保存失败: {}", e.getMessage());
        }
    }

    /**
     * 为指定用户生成个性化 AI 投资建议
     * @param user 当前登录用户（用于获取其持仓和预警）
     * @return Markdown 格式的投资建议文本
     */
    public String generateInvestmentAdvice(User user) {
        long start = System.currentTimeMillis();
        try {
            String prompt = aiPromptService.buildPortfolioPrompt(user);
            String result = generateReviewedPortfolioAdvice(prompt);
            saveRecord(user, "PORTFOLIO", "整体持仓分析", result, System.currentTimeMillis() - start);
            return result;
        } catch (AiServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI 分析调用失败: {}", e.getMessage());
            return buildFallbackAdvice(user);
        }
    }

    /**
     * 针对单只股票/基金生成专项 AI 分析报告
     * 结合：该标的的行情数据、相关关键词新闻、用户持仓情况、预警状态
     *
     * @param user 当前用户
     * @param code 股票/基金代码
     * @param name 股票/基金名称
     * @return Markdown 格式的专项分析报告
     */
    public String generateStockAdvice(User user, String code, String name) {
        long start = System.currentTimeMillis();
        try {
            String prompt = aiPromptService.buildStockPrompt(user, code, name);
            String result = callDeepSeekApi(prompt);
            saveRecord(user, "STOCK", name + "（" + code + "）", result, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("股票AI分析失败 [{}]: {}", code, e.getMessage());
            return "## " + name + "（" + code + "）分析\n\n> ⚠️ AI 服务暂时不可用：" + e.getMessage()
                    + "\n\n请稍后重试，或前往 [AI投资建议](/ai-advice) 查看整体分析。";
        }
    }

    private String generateReviewedPortfolioAdvice(String prompt) throws Exception {
        List<CompletableFuture<AdviceCandidate>> tasks = List.of(
                generateCandidateAsync("DeepSeek", deepSeekProps.getUrl(), deepSeekProps.getKey(), deepSeekProps.getModel(), prompt),
                generateCandidateAsync("Qwen", qwenUrl, qwenKey, qwenModel, prompt),
                generateCandidateAsync("Kimi", kimiUrl, kimiKey, kimiModel, prompt)
        );
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        List<AdviceCandidate> candidates = tasks.stream()
                .map(CompletableFuture::join)
                .filter(c -> c != null && !isBlank(c.content()))
                .toList();

        if (candidates.isEmpty()) {
            throw new AiServiceUnavailableException("当前没有可用的 AI 候选模型，请检查 API Key 配置或稍后重试");
        }
        if (candidates.size() == 1 || isBlank(geminiKey)) {
            AdviceCandidate only = candidates.get(0);
            return only.content() + buildSingleCandidateNote(only);
        }

        Collections.shuffle(candidates);
        for (int i = 0; i < candidates.size(); i++) {
            candidates.set(i, candidates.get(i).withCode(String.valueOf((char) ('A' + i))));
        }

        ReviewResult review;
        try {
            review = reviewCandidates(prompt, candidates);
        } catch (Exception e) {
            log.warn("Gemini review failed, use first candidate: {}", e.getMessage());
            AdviceCandidate first = candidates.stream()
                    .filter(c -> "DeepSeek".equals(c.provider()))
                    .findFirst()
                    .orElse(candidates.get(0));
            return first.content() + buildReviewFailureNote(first, e.getMessage());
        }

        AdviceCandidate selected = candidates.stream()
                .max(Comparator.comparingInt(c -> review.totalScore(c.code())))
                .orElse(candidates.get(0));

        return selected.content() + buildReviewSummary(selected, review);
    }

    private CompletableFuture<AdviceCandidate> generateCandidateAsync(String provider, String url, String key,
                                                                      String model, String prompt) {
        return CompletableFuture.supplyAsync(() -> generateCandidate(provider, url, key, model, prompt));
    }

    private AdviceCandidate generateCandidate(String provider, String url, String key, String model, String prompt) {
        if (isBlank(key) || isBlank(url) || isBlank(model)) {
            log.info("Skip {} candidate because API config is incomplete", provider);
            return null;
        }
        try {
            String content = callOpenAiCompatibleApi(url, key, model, prompt,
                    aiPromptService.buildPortfolioCandidateSystemPrompt(),
                    0.7, 4000);
            return new AdviceCandidate("", provider, content);
        } catch (Exception e) {
            log.warn("{} candidate generation failed: {}", provider, e.getMessage());
            return null;
        }
    }

    private ReviewResult reviewCandidates(String originalPrompt, List<AdviceCandidate> candidates) throws Exception {
        String reviewPrompt = aiPromptService.buildReviewPrompt(
                originalPrompt,
                candidates.stream()
                        .map(c -> new AiPromptService.ReviewCandidateContext(c.code(), c.content()))
                        .toList());

        String json = callOpenAiCompatibleApi(geminiUrl, geminiKey, geminiModel, reviewPrompt,
                aiPromptService.buildReviewSystemPrompt(),
                0.2, 2000);
        return parseReviewResult(json);
    }

    private ReviewResult parseReviewResult(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(raw));
        List<CandidateScore> scores = new ArrayList<>();
        JsonNode scoreNodes = root.path("scores");
        if (scoreNodes.isArray()) {
            for (JsonNode n : scoreNodes) {
                scores.add(new CandidateScore(
                        n.path("candidate").asText(""),
                        clampScore(n.path("profileConsistency").asInt(0)),
                        clampScore(n.path("holdingSpecificity").asInt(0)),
                        clampScore(n.path("marketSentimentUse").asInt(0)),
                        clampScore(n.path("newsUse").asInt(0)),
                        clampScore(n.path("goalConsistency").asInt(0)),
                        clampScore(n.path("riskWarning").asInt(0)),
                        clampScore(n.path("actionability").asInt(0)),
                        clampScore(n.path("safety").asInt(0)),
                        n.path("reason").asText("")
                ));
            }
        }
        if (scores.isEmpty()) {
            throw new IllegalArgumentException("review scores are empty");
        }
        return new ReviewResult(scores);
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(5, score));
    }

    private String buildReviewSummary(AdviceCandidate selected, ReviewResult review) {
        StringBuilder md = new StringBuilder();
        md.append("\n\n---\n\n## AI 建议质量评估\n\n");
        md.append("- 最终采纳候选：候选 ").append(selected.code()).append("\n");
        md.append("- 评审方式：候选建议已匿名处理，由 Gemini Flash 按统一评分表评审。\n\n");
        md.append("| 候选 | 画像一致性 | 持仓针对性 | 市场情绪利用 | 新闻利用 | 目标约束 | 风险提示 | 可执行性 | 安全合规 | 总分 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (CandidateScore s : review.scores()) {
            md.append("| ").append(s.candidate())
                    .append(" | ").append(s.profileConsistency())
                    .append(" | ").append(s.holdingSpecificity())
                    .append(" | ").append(s.marketSentimentUse())
                    .append(" | ").append(s.newsUse())
                    .append(" | ").append(s.goalConsistency())
                    .append(" | ").append(s.riskWarning())
                    .append(" | ").append(s.actionability())
                    .append(" | ").append(s.safety())
                    .append(" | ").append(s.computedTotal())
                    .append(" |\n");
        }
        review.scores().stream()
                .filter(s -> s.candidate().equalsIgnoreCase(selected.code()))
                .findFirst()
                .ifPresent(s -> md.append("\n评审摘要：").append(s.reason()).append("\n"));
        return md.toString();
    }

    private String buildSingleCandidateNote(AdviceCandidate candidate) {
        return "\n\n---\n\n## AI 建议质量评估\n\n"
                + "- 当前仅成功生成 1 份候选建议，或未配置 Gemini 评审模型，系统采用 "
                + candidate.provider() + " 的输出作为最终建议。\n";
    }

    private String buildReviewFailureNote(AdviceCandidate candidate, String reason) {
        return "\n\n---\n\n## AI 建议质量评估\n\n"
                + "- 多模型评审暂未完成，原因：" + reason + "\n"
                + "- 系统采用候选 " + candidate.code() + " 作为本次最终建议。\n";
    }

    private String callOpenAiCompatibleApi(String url, String key, String model, String userPrompt,
                                           String systemPrompt, double temperature, int maxTokens) throws Exception {
        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(deepSeekProps.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(deepSeekProps.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(deepSeekProps.getTimeout(), TimeUnit.SECONDS)
                .build();

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        requestBody.set("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);

        RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(requestBody),
                MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + key)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("LLM API error: HTTP " + response.code() + " " + responseBody);
            }
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            return jsonNode.path("choices").get(0).path("message").path("content").asText();
        }
    }

    private String extractJson(String text) {
        String trimmed = text == null ? "" : text.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private String limitText(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record AdviceCandidate(String code, String provider, String content) {
        AdviceCandidate withCode(String newCode) {
            return new AdviceCandidate(newCode, provider, content);
        }
    }

    private record CandidateScore(String candidate, int profileConsistency, int holdingSpecificity,
                                  int marketSentimentUse, int newsUse, int goalConsistency,
                                  int riskWarning, int actionability, int safety,
                                  String reason) {
        int computedTotal() {
            return profileConsistency + holdingSpecificity + marketSentimentUse
                    + newsUse + goalConsistency + riskWarning + actionability + safety;
        }
    }

    private record ReviewResult(List<CandidateScore> scores) {
        int totalScore(String candidate) {
            return scores.stream()
                    .filter(s -> s.candidate().equalsIgnoreCase(candidate))
                    .findFirst()
                    .map(CandidateScore::computedTotal)
                    .orElse(0);
        }
    }

    /**
     * 单条新闻 AI 解读
     * 分析该新闻的利好/利空性质，并结合用户持仓给出操作参考
     */
    public String generateNewsAnalysis(User user, String title, String summary, String source) {
        long start = System.currentTimeMillis();
        try {
            String prompt = aiPromptService.buildNewsAnalysisPrompt(user, title, summary, source);
            String result = callDeepSeekApi(prompt);
            saveRecord(user, "NEWS", title, result, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("新闻AI解读失败: {}", e.getMessage());
            return "> ⚠️ AI 解读暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 关键词新闻情绪汇总
     * 汇总某关键词下所有新闻，给出整体情绪评分和操作建议
     */
    public String generateKeywordSentiment(User user, String keyword) {
        long start = System.currentTimeMillis();
        try {
            List<News> newsList = newsService.getNewsByKeyword(user, keyword);
            if (newsList.isEmpty()) {
                return "> 暂无关于「" + keyword + "」的新闻数据，请先抓取相关新闻。";
            }

            String prompt = aiPromptService.buildKeywordSentimentPrompt(user, keyword, newsList);
            String result = callDeepSeekApi(prompt);
            saveRecord(user, "NEWS_KEYWORD", "关键词：" + keyword, result, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("关键词情绪AI分析失败 [{}]: {}", keyword, e.getMessage());
            return "> ⚠️ AI 情绪分析暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 针对单个投资目标的 AI 分析
     */
    public String generateGoalAdvice(User user, org.example.finance.model.InvestmentGoal goal,
                                     java.math.BigDecimal completionRate, int achieveProbability,
                                     java.math.BigDecimal monthlyGap) {
        long start = System.currentTimeMillis();
        try {
            String prompt = aiPromptService.buildGoalAdvicePrompt(
                    user, goal, completionRate, achieveProbability, monthlyGap);
            String result = callDeepSeekApi(prompt);
            saveRecord(user, "GOAL", goal.getGoalName(), result, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("目标AI分析失败 [{}]: {}", goal.getGoalName(), e.getMessage());
            return "> ⚠️ AI 分析暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 调用 DeepSeek Chat API
     */
    private String callDeepSeekApi(String userPrompt) throws Exception {
        return callOpenAiCompatibleApi(
                deepSeekProps.getUrl(),
                deepSeekProps.getKey(),
                deepSeekProps.getModel(),
                userPrompt,
                aiPromptService.buildDefaultAdviceSystemPrompt(),
                0.7,
                4000);
    }

    /**
     * 降级方案：当 API 调用失败时，使用动态模板生成（比原来更智能）
     */
    private String buildFallbackAdvice(User user) {
        List<Stock> stocks = stockService.getAllStocks();
        List<Asset> assets = assetService.getUserAssets(user);
        List<Warning> triggered = warningService.getTriggeredWarningsByUser(user);

        StringBuilder advice = new StringBuilder();
        advice.append("# AI 投资分析报告\n\n");
        advice.append("> ⚠️ 当前 AI 服务暂时不可用，以下为基于规则的自动分析报告。\n\n");

        // 持仓分析
        if (!assets.isEmpty()) {
            advice.append("## 📊 持仓分析\n");
            BigDecimal totalProfit = BigDecimal.ZERO;
            for (Asset asset : assets) {
                BigDecimal profit = asset.getTotalValue()
                        .subtract(asset.getAmount().multiply(asset.getPurchasePrice()));
                totalProfit = totalProfit.add(profit);
                String sign = profit.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "";
                advice.append(String.format("- **%s**（%s）：当前价 %s 元，收益 %s%s 元\n",
                        asset.getName(), asset.getCode(),
                        asset.getCurrentPrice(), sign, profit));
            }
            advice.append(String.format("\n**总收益：%s 元**\n\n",
                    totalProfit.compareTo(BigDecimal.ZERO) >= 0
                            ? "+" + totalProfit : totalProfit.toString()));
        }

        // 预警提示
        if (!triggered.isEmpty()) {
            advice.append("## 🔔 预警提示\n");
            for (Warning w : triggered) {
                advice.append(String.format("- **%s**（%s）已触发 **%s**，触发价 %s 元。建议：%s\n",
                        w.getName(), w.getCode(),
                        w.getStatus().equals("LOSS") ? "止损线" :
                                w.getStatus().equals("PROFIT") ? "止盈线" : "警告线",
                        w.getTriggeredPrice(), w.getMeaning()));
            }
            advice.append("\n");
        }

        // 行情摘要
        if (!stocks.isEmpty()) {
            long upCount = stocks.stream()
                    .filter(s -> s.getChangePercent() != null
                            && s.getChangePercent().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            advice.append("## 📈 行情摘要\n");
            advice.append(String.format("关注的 %d 只标的中，%d 只上涨，%d 只下跌。\n\n",
                    stocks.size(), upCount, stocks.size() - upCount));
        }

        advice.append("## 💡 通用建议\n");
        advice.append("1. **风险控制优先**：严格执行止盈止损纪律，避免情绪化操作。\n");
        advice.append("2. **分散配置**：单一标的仓位建议不超过总资产的 30%。\n");
        advice.append("3. **关注政策**：密切跟踪货币政策和行业监管动态。\n");
        advice.append("4. **定期复盘**：每周对持仓进行收益复盘，及时调整策略。\n");

        return advice.toString();
    }
}
