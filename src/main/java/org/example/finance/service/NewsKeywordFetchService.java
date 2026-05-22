package org.example.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.finance.model.News;
import org.example.finance.model.NewsKeyword;
import org.example.finance.repository.NewsKeywordRepository;
import org.example.finance.repository.NewsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 关键词财经新闻定时抓取服务
 *
 * 数据来源：新浪财经滚动新闻 JSON 接口（feed.mix.sina.com.cn）
 * 经实测唯一可用的免费财经新闻 JSON 接口，无需登录，无 Bot 检测。
 * 策略：拉取最新财经滚动新闻，在本地按关键词过滤标题/摘要后入库。
 */
@Service
public class NewsKeywordFetchService {

    private static final Logger log = LoggerFactory.getLogger(NewsKeywordFetchService.class);

    /**
     * 新浪财经滚动新闻 JSON 接口（实测可用）
     * pageid=153 lid=2516 = 财经综合滚动
     * 响应结构：{ "result": { "status": {"code":0}, "data": [...] } }
     * 字段：title / url / intro / media_name / ctime(Unix时间戳字符串)
     */
    private static final String SINA_ROLL_API =
            "https://feed.mix.sina.com.cn/api/roll/get" +
            "?pageid=153&lid=2516&k=0&num=50&page=1";

    private static final Set<String> BLACKLIST = Set.of(
            "开户", "免费领取", "限时优惠", "注册送", "下载APP",
            "扫码", "红包", "广告", "推广", "返现", "佣金", "招募"
    );

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final NewsKeywordRepository newsKeywordRepository;
    private final NewsRepository newsRepository;

    public NewsKeywordFetchService(OkHttpClient http, ObjectMapper mapper,
                                   NewsKeywordRepository newsKeywordRepository,
                                   NewsRepository newsRepository) {
        this.http = http;
        this.mapper = mapper;
        this.newsKeywordRepository = newsKeywordRepository;
        this.newsRepository = newsRepository;
    }

    @Scheduled(fixedRateString = "${schedule.news-keyword-fetch.fixed-rate}", initialDelayString = "${schedule.news-keyword-fetch.initial-delay}")
    public void fetchNewsByKeywords() {
        List<NewsKeyword> keywords = newsKeywordRepository.findAll();
        if (keywords.isEmpty()) return;

        // 一次性拉取滚动新闻，所有关键词共用同一批数据
        JsonNode articles = fetchRollNews();
        if (articles == null) {
            log.warn("新浪滚动新闻接口无数据，本次跳过");
            return;
        }

        log.info("开始关键词新闻匹配，共 {} 个关键词，本批 {} 条新闻",
                keywords.size(), articles.size());
        int totalSaved = 0;

        for (NewsKeyword kw : keywords) {
            int saved = matchAndSave(kw.getKeyword(), articles);
            totalSaved += saved;
            kw.setLastFetchTime(new Date());
            newsKeywordRepository.save(kw);
        }

        log.info("抓取完成，本次新增 {} 条", totalSaved);
    }

    /** 拉取新浪滚动新闻，返回 data 数组节点；失败返回 null */
    private JsonNode fetchRollNews() {
        try {
            Request req = new Request.Builder()
                    .url(SINA_ROLL_API)
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                    .addHeader("Referer", "https://finance.sina.com.cn/")
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonNode root = mapper.readTree(resp.body().string());
                int code = root.path("result").path("status").path("code").asInt(-1);
                if (code != 0) return null;
                JsonNode data = root.path("result").path("data");
                return data.isArray() && !data.isEmpty() ? data : null;
            }
        } catch (Exception e) {
            log.warn("新浪滚动新闻请求失败: {}", e.getMessage());
            return null;
        }
    }

    /** 在已拉取的文章列表中按关键词过滤并入库 */
    private int matchAndSave(String keyword, JsonNode articles) {
        int saved = 0;
        for (JsonNode item : articles) {
            if (saved >= 10) break;

            String title  = item.path("title").asText("").trim();
            String url    = item.path("url").asText("").trim();
            String intro  = item.path("intro").asText("").trim();
            String source = item.path("media_name").asText("新浪财经").trim();
            String ctimeStr = item.path("ctime").asText("0");

            // 关键词相关性过滤
            if (!title.contains(keyword) && !intro.contains(keyword)) continue;
            if (!isValid(title, url)) continue;
            if (newsRepository.existsByUrl(url)) continue;

            News news = new News();
            news.setTitle(title);
            news.setContent(intro.isBlank() ? title : intro);
            news.setSource(source);
            news.setUrl(url);
            news.setKeyword(keyword);
            news.setCategory("关键词:" + keyword);
            news.setFetchedAt(new Date());
            // ctime 是 Unix 时间戳字符串
            try {
                news.setPublishTime(new Date(Long.parseLong(ctimeStr) * 1000));
            } catch (NumberFormatException e) {
                news.setPublishTime(new Date());
            }

            newsRepository.save(news);
            saved++;
        }
        return saved;
    }

    private boolean isValid(String title, String url) {
        if (title == null || title.length() < 8) return false;
        if (url == null || url.isBlank()) return false;
        for (String banned : BLACKLIST) {
            if (title.contains(banned)) return false;
        }
        return true;
    }

    public int fetchNow(String keyword) {
        JsonNode articles = fetchRollNews();
        if (articles == null) return 0;
        return matchAndSave(keyword, articles);
    }

    /** 异步版本：供 Controller 在请求线程外触发，立即返回响应给用户 */
    @Async
    public void fetchNowAsync(String keyword) {
        int count = fetchNow(keyword);
        log.info("异步抓取 [{}] 完成，新增 {} 条", keyword, count);
    }
}
