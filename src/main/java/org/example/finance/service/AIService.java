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
import org.example.finance.model.UserProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI 投资分析服务
 * 将用户持仓、股票行情、新闻、预警状态整合成结构化 Prompt，
 * 调用 DeepSeek API 生成个性化投资建议。
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
    private DeepSeekProperties deepSeekProps;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private MarketSentimentService marketSentimentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            String prompt = buildPrompt(user);
            String result = callDeepSeekApi(prompt);
            saveRecord(user, "PORTFOLIO", "整体持仓分析", result, System.currentTimeMillis() - start);
            return result;
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
            String prompt = buildStockPrompt(user, code, name);
            String result = callDeepSeekApi(prompt);
            saveRecord(user, "STOCK", name + "（" + code + "）", result, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("股票AI分析失败 [{}]: {}", code, e.getMessage());
            return "## " + name + "（" + code + "）分析\n\n> ⚠️ AI 服务暂时不可用：" + e.getMessage()
                    + "\n\n请稍后重试，或前往 [AI投资建议](/ai-advice) 查看整体分析。";
        }
    }

    /**
     * 构建针对单只标的的 Prompt
     */
    private String buildStockPrompt(User user, String code, String name) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请对以下股票/基金进行深度投资分析：\n\n");

        // 该标的基本行情
        List<Stock> allStocks = stockService.getAllStocks();
        Stock target = allStocks.stream()
                .filter(s -> code.equals(s.getCode()))
                .findFirst().orElse(null);

        if (target != null) {
            prompt.append("【").append(name).append("（").append(code).append("）基本信息】\n");
            prompt.append(String.format("- 当前价格：%s 元\n", target.getPrice()));
            if (target.getPriceChange() != null)
                prompt.append(String.format("- 今日涨跌：%s 元（%s%%）\n",
                        target.getPriceChange(), target.getChangePercent()));
            if (target.getPe() != null)
                prompt.append(String.format("- 市盈率（PE）：%s\n", target.getPe()));
            if (target.getPb() != null)
                prompt.append(String.format("- 市净率（PB）：%s\n", target.getPb()));
            if (target.getNav() != null)
                prompt.append(String.format("- 基金净值：%s\n", target.getNav()));
            if (target.getMarket() != null)
                prompt.append(String.format("- 所属市场：%s\n", target.getMarket()));
            prompt.append("\n");
        }

        // 用户持仓中该标的的情况
        List<Asset> assets = assetService.getUserAssets(user);
        assets.stream()
                .filter(a -> code.equals(a.getCode()))
                .findFirst()
                .ifPresent(a -> {
                    BigDecimal cost = a.getAmount().multiply(a.getPurchasePrice());
                    BigDecimal profit = a.getTotalValue().subtract(cost);
                    prompt.append("【用户持仓情况】\n");
                    prompt.append(String.format("- 持有 %s 股，买入价 %s 元，当前价 %s 元\n",
                            a.getAmount(), a.getPurchasePrice(), a.getCurrentPrice()));
                    prompt.append(String.format("- 市值 %s 元，浮动收益 %s%s 元\n\n",
                            a.getTotalValue(),
                            profit.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "",
                            profit));
                });

        // 该标的相关预警
        List<Warning> warnings = warningService.getTriggeredWarningsByUser(user).stream()
                .filter(w -> code.equals(w.getCode())).toList();
        if (!warnings.isEmpty()) {
            prompt.append("【已触发预警】\n");
            for (Warning w : warnings) {
                prompt.append(String.format("- %s 触发（触发价 %s 元）：%s\n",
                        w.getStatus(), w.getTriggeredPrice(), w.getMeaning()));
            }
            prompt.append("\n");
        }

        // 与该标的相关的新闻（按代码和名称搜索）
        List<News> allNews = newsService.getAllNews(user);
        List<News> relatedNews = allNews.stream()
                .filter(n -> {
                    String kw = n.getKeyword();
                    String title = n.getTitle() != null ? n.getTitle() : "";
                    return (kw != null && (kw.contains(name) || kw.contains(code)))
                            || title.contains(name) || title.contains(code);
                })
                .limit(8)
                .toList();

        // 若没有精确匹配，取最新5条通用财经新闻
        List<News> newsToShow = relatedNews.isEmpty()
                ? allNews.stream().limit(5).toList()
                : relatedNews;

        if (!newsToShow.isEmpty()) {
            prompt.append(relatedNews.isEmpty()
                    ? "【最新财经新闻（未找到专项新闻，以下为近期市场动态）】\n"
                    : "【" + name + " 相关新闻（最多8条）】\n");
            newsToShow.forEach(n -> prompt.append(String.format("- 【%s】%s（来源：%s）\n",
                    n.getCategory() != null ? n.getCategory() : "财经",
                    n.getTitle(), n.getSource())));
            prompt.append("\n");
        }

        prompt.append("请基于以上数据，生成一份针对 **").append(name)
                .append("（").append(code).append("）** 的专项投资分析报告，要求：\n");
        prompt.append("1. 从技术面和基本面分析当前价格位置和趋势\n");
        prompt.append("2. 结合相关新闻分析近期利好/利空因素\n");
        prompt.append("3. 给出明确的操作建议：买入/持有/减仓/卖出，并说明理由\n");
        prompt.append("4. 给出合理的止盈止损参考价位\n");
        prompt.append("5. 分析主要投资风险\n");
        prompt.append("6. 输出格式使用 Markdown，语言专业简洁，结论明确可操作\n");

        return prompt.toString();
    }

    /**
     * 构建发送给 DeepSeek 的完整 Prompt
     * 包含：系统角色设定 + 用户持仓 + 股票行情 + 新闻摘要 + 触发预警
     */
    private String buildPrompt(User user) {
        StringBuilder prompt = new StringBuilder();

        // 用户风险画像（如有）
        userProfileService.findByUser(user).ifPresent(p -> {
            String horizonLabel = switch (p.getInvestmentHorizon() == null ? "" : p.getInvestmentHorizon()) {
                case "LONG"   -> "长期（>5年）";
                case "MEDIUM" -> "中期（1-5年）";
                default       -> "短期（<1年）";
            };
            String goalLabel = switch (p.getInvestmentGoal() == null ? "" : p.getInvestmentGoal()) {
                case "WEALTH_GROWTH" -> "财富增值";
                case "INCOME"        -> "稳定收益";
                case "PRESERVATION"  -> "资产保值";
                case "SPECULATION"   -> "高风险投机";
                default              -> p.getInvestmentGoal();
            };
            prompt.append("【用户风险画像】\n");
            prompt.append(String.format("- 风险等级：%s（评分 %d/100）\n",
                    org.example.finance.constant.RiskLevel.label(p.getRiskLevel()), p.getRiskScore()));
            prompt.append(String.format("- 投资目标：%s，期限：%s\n", goalLabel, horizonLabel));
            prompt.append(String.format("- 最大可接受亏损：%d%%，偏好资产：%s\n",
                    p.getMaxLossPercent(), p.getPreferredAssets()));
            prompt.append(String.format("- 月结余：%s元，流动性需求：%s\n\n",
                    p.getMonthlySavings(), p.getLiquidityNeed()));
        });

        // 用户持仓信息
        List<Asset> assets = assetService.getUserAssets(user);
        if (!assets.isEmpty()) {
            prompt.append("【用户当前持仓】\n");
            for (Asset asset : assets) {
                BigDecimal cost = asset.getAmount().multiply(asset.getPurchasePrice());
                BigDecimal profit = asset.getTotalValue().subtract(cost);
                String profitStr = profit.compareTo(BigDecimal.ZERO) >= 0
                        ? "盈利 " + profit + " 元"
                        : "亏损 " + profit.abs() + " 元";
                prompt.append(String.format("- %s（%s）%s，持有 %s 股，买入价 %s 元，当前价 %s 元，市值 %s 元，%s\n",
                        asset.getName(), asset.getCode(), asset.getType(),
                        asset.getAmount(), asset.getPurchasePrice(),
                        asset.getCurrentPrice(), asset.getTotalValue(), profitStr));
            }
            prompt.append("\n");
        }

        // 股票/基金行情
        List<Stock> stocks = stockService.getAllStocks();
        if (!stocks.isEmpty()) {
            prompt.append("【关注的股票/基金行情】\n");
            for (Stock stock : stocks) {
                String trend = stock.getChangePercent() != null &&
                        stock.getChangePercent().compareTo(BigDecimal.ZERO) >= 0 ? "↑" : "↓";
                prompt.append(String.format("- %s（%s）%s，价格 %s 元，涨跌幅 %s%% %s，市场：%s",
                        stock.getName(), stock.getCode(), stock.getType(),
                        stock.getPrice(), stock.getChangePercent(), trend, stock.getMarket()));
                if ("股票".equals(stock.getType()) && stock.getPe() != null) {
                    prompt.append(String.format("，市盈率 %s，市净率 %s", stock.getPe(), stock.getPb()));
                }
                if ("基金".equals(stock.getType()) && stock.getNav() != null) {
                    prompt.append(String.format("，净值 %s", stock.getNav()));
                }
                prompt.append("\n");
            }
            prompt.append("\n");
        }

        // 触发的预警
        List<Warning> triggeredWarnings = warningService.getTriggeredWarningsByUser(user);
        if (!triggeredWarnings.isEmpty()) {
            prompt.append("【已触发的预警（需要重点关注）】\n");
            for (Warning w : triggeredWarnings) {
                String statusLabel = switch (w.getStatus()) {
                    case "WARNING" -> "价格预警";
                    case "PROFIT"  -> "止盈触发";
                    case "LOSS"    -> "止损触发";
                    default        -> w.getStatus();
                };
                prompt.append(String.format("- %s（%s）：%s，触发价格 %s 元，预设含义：%s\n",
                        w.getName(), w.getCode(), statusLabel,
                        w.getTriggeredPrice(), w.getMeaning()));
            }
            prompt.append("\n");
        }

        // 最新财经新闻
        List<News> newsList = newsService.getAllNews(user);
        if (!newsList.isEmpty()) {
            prompt.append("【最新财经新闻（最多5条）】\n");
            newsList.stream().limit(5).forEach(news ->
                    prompt.append(String.format("- 【%s】%s（来源：%s）\n",
                            news.getCategory(), news.getTitle(), news.getSource())));
            prompt.append("\n");
        }

        // 仓位量化：计算各标的占比
        if (!assets.isEmpty()) {
            BigDecimal totalValue = assets.stream()
                    .map(Asset::getTotalValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalValue.compareTo(BigDecimal.ZERO) > 0) {
                prompt.append("【当前仓位占比】\n");
                for (Asset a : assets) {
                    BigDecimal pct = a.getTotalValue()
                            .multiply(BigDecimal.valueOf(100))
                            .divide(totalValue, 1, java.math.RoundingMode.HALF_UP);
                    prompt.append(String.format("- %s（%s）：占比 %s%%，市值 %s 元\n",
                            a.getName(), a.getCode(), pct, a.getTotalValue()));
                }
                prompt.append("\n");
            }
        }

        prompt.append("请基于以上数据，为该用户生成个性化投资分析报告，要求：\n");
        prompt.append("1. 结合用户实际持仓分析盈亏原因和风险\n");
        prompt.append("2. 对已触发的预警给出明确的操作建议（是否止盈/止损）\n");
        prompt.append("3. 结合新闻分析宏观面对持仓的影响\n");
        prompt.append("4. **仓位调整建议**（表格形式）：\n");
        prompt.append("   | 标的 | 当前占比 | 建议占比 | 操作方向 | 核心理由 |\n");
        prompt.append("   |------|---------|---------|---------|--------|\n");
        prompt.append("   （每个持仓标的各一行）\n");
        prompt.append("5. **买卖点参考**（表格形式）：\n");
        prompt.append("   | 标的 | 当前价 | 关键支撑位 | 目标压力位 | 操作建议 |\n");
        prompt.append("   |------|--------|-----------|-----------|--------|\n");
        prompt.append("6. 输出格式使用 Markdown，分段清晰，语言专业但易懂\n");
        prompt.append("7. 在报告最末尾（Markdown正文结束后），额外输出一个JSON代码块，格式严格如下（不要解释，直接输出）：\n");
        prompt.append("```json\n");
        prompt.append("{\"riskScore\":65,\"riskLevel\":\"中等\",\"positionRisk\":\"中\",\"concentrationRisk\":\"高\",\"marketRisk\":\"低\",\"topRisk\":\"集中度过高\"}\n");
        prompt.append("```\n");
        prompt.append("riskScore范围0~100（100为最高风险），riskLevel为极低/低/中等/高/极高，positionRisk/concentrationRisk/marketRisk为低/中/高。\n");

        return prompt.toString();
    }

    /**
     * 单条新闻 AI 解读
     * 分析该新闻的利好/利空性质，并结合用户持仓给出操作参考
     */
    public String generateNewsAnalysis(User user, String title, String summary, String source) {
        long start = System.currentTimeMillis();
        try {
            List<Asset> assets = assetService.getUserAssets(user);
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一位专业的A股投资顾问。\n\n");

            if (!assets.isEmpty()) {
                prompt.append("【用户当前持仓】\n");
                assets.forEach(a -> prompt.append(String.format("- %s（%s）持有 %s 股，买入价 %s 元，当前价 %s 元\n",
                        a.getName(), a.getCode(), a.getAmount(), a.getPurchasePrice(), a.getCurrentPrice())));
                prompt.append("\n");
            }

            prompt.append("【待分析新闻】\n");
            prompt.append("标题：").append(title).append("\n");
            if (summary != null && !summary.isBlank())
                prompt.append("摘要：").append(summary).append("\n");
            prompt.append("来源：").append(source).append("\n\n");

            prompt.append("请用Markdown格式输出以下内容（简洁，总字数200字以内）：\n");
            prompt.append("1. **性质判断**：利好 / 利空 / 中性，并说明核心理由（1句话）\n");
            prompt.append("2. **受影响板块/个股**：哪些行业或具体股票受影响，重点标注用户持仓中的标的\n");
            prompt.append("3. **短期操作建议**：针对用户持仓给出持有/观望/减仓/加仓的具体建议\n");

            String result = callDeepSeekApi(prompt.toString());
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

            List<Asset> assets = assetService.getUserAssets(user);
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一位专业的A股投资顾问。\n\n");

            if (!assets.isEmpty()) {
                prompt.append("【用户持仓（如与关键词相关请重点分析）】\n");
                assets.forEach(a -> prompt.append(String.format("- %s（%s）\n", a.getName(), a.getCode())));
                prompt.append("\n");
            }

            prompt.append("【关键词「").append(keyword).append("」相关新闻（共").append(newsList.size()).append("条）】\n");
            newsList.stream().limit(15).forEach(n ->
                    prompt.append(String.format("- 【%s】%s（%s）\n",
                            n.getSource() != null ? n.getSource() : "财经",
                            n.getTitle(),
                            n.getFetchedAt() != null ? new java.text.SimpleDateFormat("MM-dd").format(n.getFetchedAt()) : "")));
            prompt.append("\n");

            prompt.append("请用Markdown格式输出以下内容：\n");
            prompt.append("1. **整体情绪**：极度悲观/悲观/中性/乐观/极度乐观，给出0~100的情绪评分\n");
            prompt.append("2. **核心信息提炼**：3~5条最重要的信息点\n");
            prompt.append("3. **对用户持仓的影响**：哪些持仓受波及，程度如何\n");
            prompt.append("4. **操作建议**：基于当前新闻情绪给出具体的近期操作策略\n");

            String result = callDeepSeekApi(prompt.toString());
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
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是一位个人财务顾问，请针对以下投资目标给出专业、个性化的分析。\n\n");

            // ── 用户风险画像 ──
            userProfileService.findByUser(user).ifPresent(profile -> {
                prompt.append("【用户风险画像】\n");
                prompt.append(String.format("- 年龄：%d 岁\n", profile.getAge()));
                prompt.append(String.format("- 年收入：%s 元，月结余：%s 元\n",
                        profile.getAnnualIncome(), profile.getMonthlySavings()));
                prompt.append(String.format("- 投资经验：%s\n", profile.getInvestmentExperience()));
                prompt.append(String.format("- 风险等级：%s（评分 %d/100）\n", profile.getRiskLevel(), profile.getRiskScore()));
                prompt.append(String.format("- 最大可接受亏损：%d%%\n", profile.getMaxLossPercent()));
                prompt.append(String.format("- 投资期限：%s，流动性需求：%s\n", profile.getInvestmentHorizon(), profile.getLiquidityNeed()));
                prompt.append(String.format("- 偏好资产：%s\n\n", profile.getPreferredAssets()));
            });

            // ── 当前持仓 ──
            java.util.List<Asset> assets = assetService.getUserAssets(user);
            if (!assets.isEmpty()) {
                prompt.append("【当前持仓】\n");
                BigDecimal totalValue = assetService.calculateTotalAssetValue(user);
                for (Asset a : assets) {
                    BigDecimal pnl = a.getCurrentPrice().subtract(a.getPurchasePrice()).multiply(a.getAmount());
                    prompt.append(String.format("- %s（%s）%s：%s 份，买入价 %s，当前价 %s，市值 %s，浮盈 %s\n",
                            a.getName(), a.getCode(), a.getType(),
                            a.getAmount(), a.getPurchasePrice(), a.getCurrentPrice(),
                            a.getTotalValue(), pnl));
                }
                java.util.Map<String, BigDecimal> alloc = assetService.calculateAssetAllocation(user);
                prompt.append(String.format("- 总资产：%s 元\n", totalValue));
                prompt.append("- 仓位占比：");
                alloc.forEach((k, v) -> prompt.append(String.format("%s %.1f%%  ", k, v)));
                prompt.append("\n\n");
            }

            // ── 市场情绪 ──
            try {
                MarketSentimentService.MarketSentiment sentiment = marketSentimentService.getLatest();
                if (sentiment != null && sentiment.score() >= 0) {
                    prompt.append("【当前市场情绪】\n");
                    prompt.append(String.format("- 情绪指数：%d/100（%s）\n", sentiment.score(), sentiment.level()));
                    prompt.append(String.format("- 涨家数：%d，跌家数：%d，平家数：%d\n",
                            sentiment.upCount(), sentiment.downCount(), sentiment.flatCount()));
                    prompt.append(String.format("- 更新时间：%s\n\n", sentiment.updateTime()));
                }
            } catch (Exception ignored) {}

            // ── 投资目标详情 ──
            prompt.append("【投资目标】\n");
            prompt.append(String.format("- 目标名称：%s\n", goal.getGoalName()));
            prompt.append(String.format("- 目标金额：%s 元\n", goal.getTargetAmount()));
            prompt.append(String.format("- 当前积累：%s 元（完成率 %s%%）\n", goal.getCurrentAmount(), completionRate));
            prompt.append(String.format("- 目标日期：%s\n", goal.getTargetDate()));
            prompt.append(String.format("- 目标风险等级：%s\n", goal.getRiskLevel()));
            prompt.append(String.format("- 达成概率（线性估算）：%d%%\n", achieveProbability));
            if (monthlyGap.compareTo(java.math.BigDecimal.ZERO) > 0)
                prompt.append(String.format("- 月度缺口：还需额外投入 %s 元才能按时达成\n", monthlyGap));
            else
                prompt.append("- 月度缺口：当前投入速度已足够按时达成目标\n");
            prompt.append("\n");

            prompt.append("请用 Markdown 格式输出：\n");
            prompt.append("1. **目标可行性评估**：结合用户风险画像和当前持仓，分析达成概率是否合理\n");
            prompt.append("2. **缺口应对建议**：结合市场情绪和持仓结构，给出具体的资金调配建议\n");
            prompt.append("3. **资产配置建议**：根据目标风险等级和用户实际持仓，推荐调整方向\n");
            prompt.append("4. **风险提示**：结合当前市场情绪和用户风险承受能力的注意事项\n");
            prompt.append("5. **行动建议**：接下来最重要的 2-3 个具体可执行行动\n");

            String result = callDeepSeekApi(prompt.toString());
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
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(deepSeekProps.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(deepSeekProps.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(deepSeekProps.getTimeout(), TimeUnit.SECONDS)
                .build();

        // 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", deepSeekProps.getModel());

        ArrayNode messages = objectMapper.createArrayNode();

        // 系统角色：设定 AI 身份
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content",
                "你是一位专业的个人财务顾问，根据用户的风险画像、持仓情况和市场行情，" +
                "提供符合其风险承受能力和投资目标的个性化建议。" +
                "若推荐超出用户风险承受范围的标的，必须明确标注风险提示。" +
                "请用中文回答，格式使用 Markdown，分析要有数据支撑，建议要具体可操作。");
        messages.add(systemMsg);

        // 用户消息：携带所有上下文数据
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        requestBody.set("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 4000);

        RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(requestBody),
                MediaType.get("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(deepSeekProps.getUrl())
                .addHeader("Authorization", "Bearer " + deepSeekProps.getKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("DeepSeek API 返回错误: HTTP " + response.code()
                        + " " + (response.body() != null ? response.body().string() : ""));
            }

            String responseBody = response.body().string();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            return jsonNode
                    .path("choices").get(0)
                    .path("message")
                    .path("content")
                    .asText("AI 分析内容解析失败，请稍后重试。");
        }
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
