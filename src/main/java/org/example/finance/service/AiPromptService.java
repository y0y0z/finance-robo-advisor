package org.example.finance.service;

import org.example.finance.model.AiAnalysisRecord;
import org.example.finance.model.Asset;
import org.example.finance.model.InvestmentGoal;
import org.example.finance.model.News;
import org.example.finance.model.Stock;
import org.example.finance.model.User;
import org.example.finance.model.Warning;
import org.example.finance.repository.AiAnalysisRecordRepository;
import org.example.finance.repository.InvestmentGoalRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI Prompt 构建服务。
 *
 * 该服务只负责把用户画像、持仓、行情、预警、新闻、目标和历史分析记录
 * 组织为任务导向型上下文；模型调用、候选评审和记录保存仍由 AIService 处理。
 */
@Service
public class AiPromptService {

    public record ReviewCandidateContext(String code, String content) {}

    private final StockService stockService;
    private final NewsService newsService;
    private final AssetService assetService;
    private final WarningService warningService;
    private final UserProfileService userProfileService;
    private final MarketSentimentService marketSentimentService;
    private final AiAnalysisRecordRepository recordRepository;
    private final InvestmentGoalRepository goalRepository;

    public AiPromptService(StockService stockService,
                           NewsService newsService,
                           AssetService assetService,
                           WarningService warningService,
                           UserProfileService userProfileService,
                           MarketSentimentService marketSentimentService,
                           AiAnalysisRecordRepository recordRepository,
                           InvestmentGoalRepository goalRepository) {
        this.stockService = stockService;
        this.newsService = newsService;
        this.assetService = assetService;
        this.warningService = warningService;
        this.userProfileService = userProfileService;
        this.marketSentimentService = marketSentimentService;
        this.recordRepository = recordRepository;
        this.goalRepository = goalRepository;
    }

    public String buildPortfolioPrompt(User user) {
        StringBuilder prompt = new StringBuilder();

        userProfileService.findByUser(user).ifPresent(p -> {
            String horizonLabel = switch (p.getInvestmentHorizon() == null ? "" : p.getInvestmentHorizon()) {
                case "LONG" -> "长期（>5年）";
                case "MEDIUM" -> "中期（1-5年）";
                default -> "短期（<1年）";
            };
            String goalLabel = switch (p.getInvestmentGoal() == null ? "" : p.getInvestmentGoal()) {
                case "WEALTH_GROWTH" -> "财富增值";
                case "INCOME" -> "稳定收益";
                case "PRESERVATION" -> "资产保值";
                case "SPECULATION" -> "高风险投机";
                default -> p.getInvestmentGoal();
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

        List<Asset> assets = assetService.getUserAssets(user);
        appendHoldings(prompt, assets);
        appendWatchedMarkets(prompt);
        appendTriggeredWarnings(prompt, user);
        appendLatestNews(prompt, user);
        appendAllocation(prompt, assets);
        appendActiveGoals(prompt, user);
        appendRecentPortfolioAnalysis(prompt, user);
        appendPortfolioRequirements(prompt);

        return prompt.toString();
    }

    public String buildStockPrompt(User user, String code, String name) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请对以下股票/基金进行深度投资分析：\n\n");

        Stock target = stockService.getAllStocks().stream()
                .filter(s -> code.equals(s.getCode()))
                .findFirst().orElse(null);

        if (target != null) {
            prompt.append("【").append(name).append("（").append(code).append("）基本信息】\n");
            prompt.append(String.format("- 当前价格：%s 元\n", target.getPrice()));
            if (target.getPriceChange() != null) {
                prompt.append(String.format("- 今日涨跌：%s 元（%s%%）\n",
                        target.getPriceChange(), target.getChangePercent()));
            }
            if (target.getPe() != null) prompt.append(String.format("- 市盈率（PE）：%s\n", target.getPe()));
            if (target.getPb() != null) prompt.append(String.format("- 市净率（PB）：%s\n", target.getPb()));
            if (target.getNav() != null) prompt.append(String.format("- 基金净值：%s\n", target.getNav()));
            if (target.getMarket() != null) prompt.append(String.format("- 所属市场：%s\n", target.getMarket()));
            prompt.append("\n");
        }

        assetService.getUserAssets(user).stream()
                .filter(a -> code.equals(a.getCode()))
                .findFirst()
                .ifPresent(a -> {
                    BigDecimal cost = a.getAmount().multiply(a.getPurchasePrice());
                    BigDecimal profit = a.getTotalValue().subtract(cost);
                    prompt.append("【用户持仓情况】\n");
                    prompt.append(String.format("- 持有 %s 股，买入价 %s 元，当前价 %s 元\n",
                            a.getAmount(), a.getPurchasePrice(), a.getCurrentPrice()));
                    prompt.append(String.format("- 市值 %s 元，浮动收益 %s%s 元\n\n",
                            a.getTotalValue(), profit.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "", profit));
                });

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
        List<News> newsToShow = relatedNews.isEmpty() ? allNews.stream().limit(5).toList() : relatedNews;
        if (!newsToShow.isEmpty()) {
            prompt.append(relatedNews.isEmpty()
                    ? "【最新财经新闻（未找到专项新闻，以下为近期市场动态）】\n"
                    : "【" + name + " 相关新闻（最多8条）】\n");
            newsToShow.forEach(n -> prompt.append(String.format("- 【%s】%s（来源：%s）\n",
                    n.getCategory() != null ? n.getCategory() : "财经", n.getTitle(), n.getSource())));
            prompt.append("\n");
        }

        appendRecentSubjectAnalysis(prompt, user, "STOCK", name + "（" + code + "）");

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

    public String buildNewsAnalysisPrompt(User user, String title, String summary, String source) {
        StringBuilder prompt = new StringBuilder();
        List<Asset> assets = assetService.getUserAssets(user);
        prompt.append("你是一位专业的A股投资顾问。\n\n");

        if (!assets.isEmpty()) {
            prompt.append("【用户当前持仓】\n");
            assets.forEach(a -> prompt.append(String.format("- %s（%s）持有 %s 股，买入价 %s 元，当前价 %s 元\n",
                    a.getName(), a.getCode(), a.getAmount(), a.getPurchasePrice(), a.getCurrentPrice())));
            prompt.append("\n");
        }

        prompt.append("【待分析新闻】\n");
        prompt.append("标题：").append(title).append("\n");
        if (summary != null && !summary.isBlank()) prompt.append("摘要：").append(summary).append("\n");
        prompt.append("来源：").append(source).append("\n\n");

        prompt.append("请用Markdown格式输出以下内容（简洁，总字数200字以内）：\n");
        prompt.append("1. **性质判断**：利好 / 利空 / 中性，并说明核心理由（1句话）\n");
        prompt.append("2. **受影响板块/个股**：哪些行业或具体股票受影响，重点标注用户持仓中的标的\n");
        prompt.append("3. **短期操作建议**：针对用户持仓给出持有/观望/减仓/加仓的具体建议\n");
        return prompt.toString();
    }

    public String buildKeywordSentimentPrompt(User user, String keyword, List<News> newsList) {
        StringBuilder prompt = new StringBuilder();
        List<Asset> assets = assetService.getUserAssets(user);
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

        appendRecentSubjectAnalysis(prompt, user, "NEWS_KEYWORD", "关键词：" + keyword);

        prompt.append("请用Markdown格式输出以下内容：\n");
        prompt.append("1. **整体情绪**：极度悲观/悲观/中性/乐观/极度乐观，给出0~100的情绪评分\n");
        prompt.append("2. **核心信息提炼**：3~5条最重要的信息点\n");
        prompt.append("3. **对用户持仓的影响**：哪些持仓受波及，程度如何\n");
        prompt.append("4. **操作建议**：基于当前新闻情绪给出具体的近期操作策略\n");
        return prompt.toString();
    }

    public String buildGoalAdvicePrompt(User user, InvestmentGoal goal,
                                        BigDecimal completionRate, int achieveProbability,
                                        BigDecimal monthlyGap) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一位个人财务顾问，请针对以下投资目标给出专业、个性化的分析。\n\n");

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

        List<Asset> assets = assetService.getUserAssets(user);
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

        appendMarketSentiment(prompt);
        appendRecentSubjectAnalysis(prompt, user, "GOAL", goal.getGoalName());

        prompt.append("【投资目标】\n");
        prompt.append(String.format("- 目标名称：%s\n", goal.getGoalName()));
        prompt.append(String.format("- 目标金额：%s 元\n", goal.getTargetAmount()));
        prompt.append(String.format("- 当前积累：%s 元（完成率 %s%%）\n", goal.getCurrentAmount(), completionRate));
        prompt.append(String.format("- 目标日期：%s\n", goal.getTargetDate()));
        prompt.append(String.format("- 目标风险等级：%s\n", goal.getRiskLevel()));
        prompt.append(String.format("- 达成概率（线性估算）：%d%%\n", achieveProbability));
        if (monthlyGap.compareTo(BigDecimal.ZERO) > 0) {
            prompt.append(String.format("- 月度缺口：还需额外投入 %s 元才能按时达成\n", monthlyGap));
        } else {
            prompt.append("- 月度缺口：当前投入速度已足够按时达成目标\n");
        }
        prompt.append("\n");

        prompt.append("请用 Markdown 格式输出：\n");
        prompt.append("1. **目标可行性评估**：结合用户风险画像和当前持仓，分析达成概率是否合理\n");
        prompt.append("2. **缺口应对建议**：结合市场情绪和持仓结构，给出具体的资金调配建议\n");
        prompt.append("3. **资产配置建议**：根据目标风险等级和用户实际持仓，推荐调整方向\n");
        prompt.append("4. **风险提示**：结合当前市场情绪和用户风险承受能力的注意事项\n");
        prompt.append("5. **行动建议**：接下来最重要的 2-3 个具体可执行行动\n");
        return prompt.toString();
    }

    public String buildReviewPrompt(String originalPrompt, List<ReviewCandidateContext> candidates) {
        StringBuilder reviewPrompt = new StringBuilder();
        reviewPrompt.append("下面有若干份匿名 AI 投顾候选建议。你不知道候选来自哪个模型。\n");
        reviewPrompt.append("请不要根据篇幅、语言风格或猜测的模型来源评分，只根据内容是否正确利用用户数据评分。\n");
        reviewPrompt.append("评分维度均为 0-5 分：profileConsistency、holdingSpecificity、marketSentimentUse、newsUse、goalConsistency、riskWarning、actionability、safety。\n");
        reviewPrompt.append("其中 profileConsistency 不是看是否提到画像，而是判断建议是否符合风险等级、最大亏损承受能力、投资期限和流动性需求；其它维度也按是否正确利用数据评分。\n");
        reviewPrompt.append("必须为下面每一个候选都输出一条评分记录，candidate 字段必须原样使用候选编号。\n");
        reviewPrompt.append("只输出一个 JSON 对象，不要输出 Markdown 代码块、解释文字或额外字段。JSON 格式必须严格如下：\n");
        reviewPrompt.append("{\"scores\":[{\"candidate\":\"A\",\"profileConsistency\":4,\"holdingSpecificity\":4,\"marketSentimentUse\":3,\"newsUse\":3,\"goalConsistency\":4,\"riskWarning\":5,\"actionability\":4,\"safety\":5,\"reason\":\"...\"}]}\n");
        reviewPrompt.append("如果只有候选 A/B/C，也只能使用 A/B/C，不要使用模型名、中文候选名或序号。\n\n");
        reviewPrompt.append("【原始上下文摘要】\n").append(limitText(originalPrompt, 2500)).append("\n\n");
        for (ReviewCandidateContext c : candidates) {
            reviewPrompt.append("【候选 ").append(c.code()).append("】\n")
                    .append(limitText(c.content(), 3500)).append("\n\n");
        }
        return reviewPrompt.toString();
    }

    public String buildPortfolioCandidateSystemPrompt() {
        return "You are a professional personal robo-advisor. Answer in Chinese Markdown. "
                + "Use the provided profile, holdings, warnings, news, sentiment and goals. "
                + "Do not mention your model name or provider name. "
                + "Do not promise returns or give absolute buy/sell orders.";
    }

    public String buildReviewSystemPrompt() {
        return "You are an impartial evaluator for robo-advisor reports. Return only a JSON object with a scores array.";
    }

    public String buildDefaultAdviceSystemPrompt() {
        return "你是一位专业的个人财务顾问，根据用户的风险画像、持仓情况和市场行情，"
                + "提供符合其风险承受能力和投资目标的个性化建议。"
                + "若推荐超出用户风险承受范围的标的，必须明确标注风险提示。"
                + "请用中文回答，格式使用 Markdown，分析要有数据支撑，建议要具体可操作。";
    }

    private void appendHoldings(StringBuilder prompt, List<Asset> assets) {
        if (assets.isEmpty()) return;
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

    private void appendWatchedMarkets(StringBuilder prompt) {
        List<Stock> stocks = stockService.getAllStocks();
        if (stocks.isEmpty()) return;
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

    private void appendTriggeredWarnings(StringBuilder prompt, User user) {
        List<Warning> triggeredWarnings = warningService.getTriggeredWarningsByUser(user);
        if (triggeredWarnings.isEmpty()) return;
        prompt.append("【已触发的预警（需要重点关注）】\n");
        for (Warning w : triggeredWarnings) {
            String statusLabel = switch (w.getStatus()) {
                case "WARNING" -> "价格预警";
                case "PROFIT" -> "止盈触发";
                case "LOSS" -> "止损触发";
                default -> w.getStatus();
            };
            prompt.append(String.format("- %s（%s）：%s，触发价格 %s 元，预设含义：%s\n",
                    w.getName(), w.getCode(), statusLabel, w.getTriggeredPrice(), w.getMeaning()));
        }
        prompt.append("\n");
    }

    private void appendLatestNews(StringBuilder prompt, User user) {
        List<News> newsList = newsService.getAllNews(user);
        if (newsList.isEmpty()) return;
        prompt.append("【最新财经新闻（最多5条）】\n");
        newsList.stream().limit(5).forEach(news ->
                prompt.append(String.format("- 【%s】%s（来源：%s）\n",
                        news.getCategory(), news.getTitle(), news.getSource())));
        prompt.append("\n");
    }

    private void appendAllocation(StringBuilder prompt, List<Asset> assets) {
        if (assets.isEmpty()) return;
        BigDecimal totalValue = assets.stream()
                .map(Asset::getTotalValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalValue.compareTo(BigDecimal.ZERO) <= 0) return;

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

    private void appendActiveGoals(StringBuilder prompt, User user) {
        List<InvestmentGoal> goals = goalRepository.findByUserAndStatus(user, "ACTIVE");
        if (goals.isEmpty()) return;

        prompt.append("【进行中的投资目标】\n");
        goals.stream().limit(5).forEach(goal -> {
            BigDecimal target = goal.getTargetAmount() == null ? BigDecimal.ZERO : goal.getTargetAmount();
            BigDecimal current = goal.getCurrentAmount() == null ? BigDecimal.ZERO : goal.getCurrentAmount();
            BigDecimal rate = BigDecimal.ZERO;
            if (target.compareTo(BigDecimal.ZERO) > 0) {
                rate = current.multiply(BigDecimal.valueOf(100))
                        .divide(target, 1, java.math.RoundingMode.HALF_UP);
            }
            prompt.append(String.format("- %s：目标金额 %s 元，当前金额 %s 元，完成率 %s%%，目标日期 %s，风险等级 %s\n",
                    goal.getGoalName(), target, current, rate, goal.getTargetDate(), goal.getRiskLevel()));
        });
        prompt.append("\n");
    }

    private void appendRecentPortfolioAnalysis(StringBuilder prompt, User user) {
        List<AiAnalysisRecord> recentRecords =
                recordRepository.findTop3ByUserAndTypeOrderByCreatedAtDesc(user, "PORTFOLIO");
        if (recentRecords.isEmpty()) return;

        prompt.append("【近期历史AI分析摘要】\n");
        prompt.append("以下内容来自该用户最近的整体持仓分析记录，只用于比较风险提示和建议方向的变化，不直接照搬旧结论。\n");
        for (AiAnalysisRecord record : recentRecords) {
            prompt.append(String.format("- %s：%s\n",
                    record.getCreatedAt(), summarizeHistoryContent(record.getContent())));
        }
        prompt.append("\n");
    }

    private void appendRecentSubjectAnalysis(StringBuilder prompt, User user, String type, String subject) {
        List<AiAnalysisRecord> recentRecords =
                recordRepository.findTop3ByUserAndTypeAndSubjectOrderByCreatedAtDesc(user, type, subject);
        if (recentRecords.isEmpty()) return;

        prompt.append("【同对象历史AI分析摘要】\n");
        prompt.append("以下内容来自同一分析对象的最近历史记录，只用于比较风险提示和建议方向变化，不直接照搬旧结论。\n");
        for (AiAnalysisRecord record : recentRecords) {
            prompt.append(String.format("- %s：%s\n",
                    record.getCreatedAt(), summarizeHistoryContent(record.getContent())));
        }
        prompt.append("\n");
    }

    private void appendMarketSentiment(StringBuilder prompt) {
        try {
            MarketSentimentService.MarketSentiment sentiment = marketSentimentService.getLatest();
            if (sentiment != null && sentiment.score() >= 0) {
                prompt.append("【当前市场情绪】\n");
                prompt.append(String.format("- 情绪指数：%d/100（%s）\n", sentiment.score(), sentiment.level()));
                prompt.append(String.format("- 涨家数：%d，跌家数：%d，平盘数：%d\n",
                        sentiment.upCount(), sentiment.downCount(), sentiment.flatCount()));
                prompt.append(String.format("- 更新时间：%s\n\n", sentiment.updateTime()));
            }
        } catch (Exception ignored) {
            // 情绪不是目标分析的硬依赖，失败时不影响 Prompt 构建。
        }
    }

    private void appendPortfolioRequirements(StringBuilder prompt) {
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
    }

    private String summarizeHistoryContent(String content) {
        if (content == null || content.isBlank()) return "无有效内容";
        String normalized = content
                .replaceAll("(?s)```json.*?```", "")
                .replaceAll("(?s)## AI 建议质量评估.*", "")
                .replaceAll("[#>*`|]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return limitText(normalized, 500);
    }

    private String limitText(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
