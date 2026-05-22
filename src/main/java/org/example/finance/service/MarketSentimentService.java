package org.example.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * 市场情绪服务
 * 每5分钟从东方财富 API 拉取 A股全市场涨跌家数，
 * 计算情绪指数（0~100）并缓存在内存中供前端轮询。
 */
@Service
public class MarketSentimentService {

    private static final Logger log = LoggerFactory.getLogger(MarketSentimentService.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final RedisTemplate<String,String> redisTemplate;

    /**
     * redis key
     */
    private static final String TURNOVER_KEY = "market:turnover:daily";
    private static final String SENTIMENT_KEY = "market:sentiment:history";
    /**
     * 东方财富 A股全市场行情列表接口
     * fs=m:0+t:6,m:0+t:13,m:0+t:80,m:1+t:2,m:1+t:23 覆盖沪深主板、创业板、科创板
     * fields=f3 只取涨跌幅字段，减少流量
     * pz=6000 一次拿足（A股总数约5000只）
     */
    private static final String EASTMONEY_API =
            "https://push2.eastmoney.com/api/qt/clist/get" +
                    "?pn=1&pz=6000&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281" +
                    "&fltt=2&invt=2&fid=f3" +
                    "&fs=m:0+t:6,m:0+t:13,m:0+t:80,m:1+t:2,m:1+t:23" +
                    "&fields=f3,f6";
    private final RestTemplate restTemplate;

    /** 缓存最新情绪数据，volatile 保证多线程可见性 */
    private volatile MarketSentiment latestSentiment = null;

    public MarketSentimentService(OkHttpClient httpClient, ObjectMapper objectMapper,
                                  RedisTemplate<String,String> redisTemplate, RestTemplate restTemplate) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.restTemplate = restTemplate;
    }

    // ===== 定时刷新 =====

    /** 每5分钟刷新一次，初始延迟15秒（等应用启动稳定） */
    @Scheduled(fixedRateString = "${schedule.market-sentiment.fixed-rate}", initialDelayString = "${schedule.market-sentiment.initial-delay}")
    public void refresh() {
        try {
            latestSentiment = fetchFromEastMoney();
            log.info("[市场情绪] 刷新完成：得分={} 等级={} 上涨={} 下跌={} 平盘={}",
                    latestSentiment.score(), latestSentiment.level(),
                    latestSentiment.upCount(), latestSentiment.downCount(), latestSentiment.flatCount());
        } catch (Exception e) {
            log.error("[市场情绪] 刷新失败: {}", e.getMessage());
        }
    }

    /** 返回缓存的情绪数据（null 表示尚未初始化） */
    public MarketSentiment getLatest() {
        return latestSentiment;
    }

    // ===== 数据抓取 =====

    private MarketSentiment fetchFromEastMoney() throws Exception {
        Request request = new Request.Builder()
                .url(EASTMONEY_API)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", "https://quote.eastmoney.com/")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("HTTP " + response.code());
            // 东方财富接口说明
            // f2 最新价
            // f3 涨跌幅
            // f4 涨跌额
            // f5 成交量
            // f6 成交额
            String body = response.body().string();
            return calculateSentiment(body);
        }
    }

    /**
     * 计算市场情绪分
     * 计算公式为
     * 情绪分 = 广度分数 * 0.35 + 涨跌强度 * 0.3 + 成交量 * 0.2 + 趋势动量 * 0.15
     * @param json
     * @return
     * @throws Exception
     */
    private MarketSentiment calculateSentiment(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode diff = root.path("data").path("diff");

        int upCount = 0, downCount = 0, flatCount = 0;

        // 总涨跌幅度
        double totalPctChange = 0D;

        // 有效股票数量
        int validStockCount = 0;

        // 全市场成交额
        double totalTurnover = 0D;

        if (diff.isArray()) {

            for (JsonNode stock : diff) {

                Double pctChange = parseDouble(stock, "f3");
                Double turnover = parseDouble(stock, "f6");

                // 数据异常直接跳过
                if (pctChange == null
                        || turnover == null) {
                    continue;
                }

                // 市场广度统计
                if (pctChange > 0) {
                    upCount++;
                } else if (pctChange < 0) {
                    downCount++;
                } else {
                    flatCount++;
                }

                // 涨跌强度
                totalPctChange += pctChange;

                // 成交量情绪
                totalTurnover += turnover;

                validStockCount++;
            }
        }

        boolean isTrading = isTradingTime();

        //   情绪分计算：
        //   交易时段内 → 通过公式计算
        //   非交易时段 → 固定返回 -1，前端据此显示"休市"而非一个无意义的分数
        int score;
        String level, color;
        if (!isTrading) {
            score = -1;
            level = "休市";
            color = "#6c757d";
        } else {
            score = doCalculateScore(totalPctChange,validStockCount,totalTurnover,upCount,flatCount);
            if      (score >= 80) { level = "极度贪婪"; color = "#dc3545"; }
            else if (score >= 60) { level = "贪婪";     color = "#fd7e14"; }
            else if (score >= 40) { level = "中性";     color = "#6c757d"; }
            else if (score >= 20) { level = "恐惧";     color = "#0d6efd"; }
            else                  { level = "极度恐惧"; color = "#6f42c1"; }
        }

        String updateTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return new MarketSentiment(score, level, color, upCount, downCount, flatCount,
                upCount + downCount + flatCount, updateTime, isTrading);
    }

    /**
     * 计算情绪值
     * 1. 根据广度 成交量得分 以及涨跌幅度 得到初步情绪值
     * 2. 根据初步情绪值得到趋势动量
     * 3. 根据趋势动量得到最终情绪值
     * @return
     */
    private int doCalculateScore(double totalPctChange,
             int validStockCount,
             double totalTurnover,
                                 int upCount,
                                 int flatCount){
        double baseScore = doCalculateBreadth(upCount,flatCount,validStockCount) * 0.4
                            + doCalculateStrength(totalPctChange,validStockCount) * 0.35
                            + doCalculateTurnover(totalTurnover) * 0.25;
        double momentum = doCalculateMomentum(baseScore);

        int finalScore = (int) (baseScore + momentum * 0.3);

        finalScore = Math.max(0,
                Math.min(100, finalScore));

        redisTemplate.opsForList().leftPush(SENTIMENT_KEY, String.valueOf(baseScore));

        return finalScore;
    }

    /**
     * API中读取的JSON格式转化为double数据格式
     * @param node
     * @param field
     * @return
     */
    private Double parseDouble(JsonNode node, String field) {

        JsonNode valueNode = node.path(field);

        if (valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }

        String value = valueNode.asText("").trim();

        if (value.isEmpty()
                || "-".equals(value)
                || "--".equals(value)) {
            return null;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid field [{}] value: {}", field, value);
            return null;
        }
    }

    /**
     * 判断当前是否在交易时段（工作日 09:15–11:30 / 13:00–15:05）
     * 包含集合竞价与收盘后缓冲时间
     * @return
     */
    private boolean isTradingTime(){
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek dow = now.getDayOfWeek();
        LocalTime t = now.toLocalTime();
        return (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY)
                && ((t.isAfter(LocalTime.of(9, 15))  && t.isBefore(LocalTime.of(11, 30)))
                ||  (t.isAfter(LocalTime.of(13, 0))  && t.isBefore(LocalTime.of(15, 5))));
    }

    /**
     * 计算市场广度
     * 公式 = (上涨数 + 平盘数 * 0.5) / 总数
     * @param upCount
     * @param flatCount
     * @param totalCount
     * @return
     */
    private double doCalculateBreadth(int upCount,int flatCount,int totalCount){
        return (upCount + flatCount * 0.5D) / totalCount * 100D;
    }

    /**
     * 计算涨跌强度
     * 公式 = 总涨跌额 / 总公司数量
     * 再映射到情绪得分
     * @return
     */
    private double doCalculateStrength(double totalPctChange, int totalCount) {
        if (totalCount == 0) return 50;
        double avgPctChange = totalPctChange / totalCount;
        double strengthScore =
                50 + avgPctChange * 10;
        return Math.max(0,
                Math.min(100, strengthScore));
    }

    /**
     * 计算成交量得分
     * 公式 = 当天总成交额 / 五天时间内的平均成交额
     * 再映射到情绪得分
     * @return
     */
    private double doCalculateTurnover (double totalTurnover){
        //redisTemplate.opsForList().leftPush(TURNOVER_KEY, String.valueOf(totalTurnover));
        //
        double progress = calculateTradingProgress();
        //
        double predictTotalTurnover = totalTurnover / progress;
        List<String> turnoverList = redisTemplate.opsForList().range(TURNOVER_KEY, 0, 4);
        if(turnoverList == null || turnoverList.isEmpty()){
            return 50;
        }
        double avgTurnover = turnoverList.stream().map(Double::parseDouble).reduce(0D, Double::sum) / turnoverList.size();
        double turnoverRatio = predictTotalTurnover / avgTurnover;
        return Math.max(0,
                Math.min(100, turnoverRatio * 50));
    }

    /**
     * 计算趋势动量
     * @return
     */
    private double doCalculateMomentum(double baseScore){
        List<String> sentimentList = redisTemplate.opsForList().range(SENTIMENT_KEY, 0, 4);
        if(sentimentList == null || sentimentList.isEmpty()){
            return 0;
        }
        Double sum = sentimentList.stream().map(Double::parseDouble).reduce(0D, Double::sum);
        double historyAvg = sum / sentimentList.size();
        return baseScore - historyAvg;
    }

    /**
     * 计算当前时间占总交易时间的比例
     * @return
     */
    private double calculateTradingProgress() {

        LocalTime now = LocalTime.now();

        int tradedMinutes = 0;

        // 上午
        if (!now.isBefore(LocalTime.of(9, 30))) {

            LocalTime morningEnd =
                    now.isBefore(LocalTime.of(11, 30))
                            ? now
                            : LocalTime.of(11, 30);

            tradedMinutes += Duration.between(
                    LocalTime.of(9, 30),
                    morningEnd
            ).toMinutes();
        }

        // 下午
        if (!now.isBefore(LocalTime.of(13, 0))) {

            LocalTime afternoonEnd =
                    now.isBefore(LocalTime.of(15, 0))
                            ? now
                            : LocalTime.of(15, 0);

            tradedMinutes += Duration.between(
                    LocalTime.of(13, 0),
                    afternoonEnd
            ).toMinutes();
        }

        return Math.min(1.0,
                tradedMinutes / 240.0);
    }
    /**
     * 市场情绪数据结构
     * @param score
     * @param level
     * @param color
     * @param upCount
     * @param downCount
     * @param flatCount
     * @param totalCount
     * @param updateTime
     * @param isTrading
     */
    public record MarketSentiment(
            int score,        // 0~100 交易时段情绪，-1 表示休市
            String level,     // 极度贪婪/贪婪/中性/恐惧/极度恐惧/休市
            String color,     // 对应颜色 hex
            int upCount,      // 上涨家数
            int downCount,    // 下跌家数
            int flatCount,    // 平盘家数
            int totalCount,   // 总家数
            String updateTime,// 更新时间 HH:mm:ss
            boolean isTrading // 当前是否在交易时段
    ) {}
}
