package org.example.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 基金净值抓取服务
 *
 * 主接口：天天基金历史净值 API（lsjz），取最新一条确认净值。
 * 备用接口：fundgz 实时估算（仅交易日有效）。
 */
@Service
public class NavFetchService {

    private static final Logger log = LoggerFactory.getLogger(NavFetchService.class);

    /**
     * 天天基金历史净值接口：返回最新确认净值，需要 Referer 头。
     * 比移动端 API 更稳定。
     */
    private static final String LSJZ_URL =
            "https://api.fund.eastmoney.com/f10/lsjz?fundCode=%s&pageIndex=1&pageSize=1";

    private static final String REFERER = "https://fund.eastmoney.com/";

    /**
     * 备用：fundgz 实时估算接口（盘中有效，晚上可能返回旧日期）
     */
    private static final String FUNDGZ_URL =
            "http://fundgz.1234567.com.cn/js/%s.js?rt=%d";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public NavFetchService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 抓取指定基金代码的最新确认净值。
     * 优先用天天基金历史净值接口，失败时降级到 fundgz 估算接口。
     * 返回 null 表示两个接口均失败。
     */
    public FundNav fetchNav(String fundCode) {
        FundNav nav = fetchFromLsjz(fundCode);
        if (nav != null) return nav;

        log.warn("天天基金 lsjz 接口失败，降级到 fundgz 估算接口: {}", fundCode);
        return fetchFromFundgz(fundCode);
    }

    // ── 天天基金历史净值接口 ────────────────────────
    private FundNav fetchFromLsjz(String fundCode) {
        try {
            String url = String.format(LSJZ_URL, fundCode);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Referer", REFERER);
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) return null;

            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("Data");
            JsonNode list = data.path("LSJZList");
            if (!list.isArray() || list.isEmpty()) return null;

            JsonNode latest = list.get(0);
            String navStr  = latest.path("DWJZ").asText(null);
            String dateStr = latest.path("FSRQ").asText(null);

            if (navStr == null || navStr.isBlank() || dateStr == null || dateStr.isBlank()) return null;

            if (dateStr.length() > 10) dateStr = dateStr.substring(0, 10);

            BigDecimal nav    = new BigDecimal(navStr);
            LocalDate navDate = LocalDate.parse(dateStr);
            log.debug("天天基金净值 [{}]: {} ({})", fundCode, nav, navDate);
            return new FundNav(fundCode, null, nav, navDate);

        } catch (Exception e) {
            log.warn("天天基金 lsjz 净值抓取失败 [{}]: {}", fundCode, e.getMessage());
            return null;
        }
    }

    // ── fundgz 估算净值接口（备用）──────────────────
    private FundNav fetchFromFundgz(String fundCode) {
        try {
            String url = String.format(FUNDGZ_URL, fundCode, System.currentTimeMillis());
            String body = restTemplate.getForObject(url, String.class);
            if (body == null || body.isBlank()) return null;

            int start = body.indexOf('(');
            int end   = body.lastIndexOf(')');
            if (start < 0 || end <= start) {
                log.warn("fundgz 响应格式异常: {} -> {}", fundCode, body);
                return null;
            }
            String json = body.substring(start + 1, end);

            JsonNode node   = objectMapper.readTree(json);
            String navStr   = node.path("dwjz").asText(null);
            String dateStr  = node.path("jzrq").asText(null);
            String name     = node.path("name").asText(null);

            if (navStr == null || dateStr == null) return null;

            BigDecimal nav    = new BigDecimal(navStr);
            LocalDate navDate = LocalDate.parse(dateStr);
            return new FundNav(fundCode, name, nav, navDate);

        } catch (Exception e) {
            log.warn("fundgz 净值抓取失败 [{}]: {}", fundCode, e.getMessage());
            return null;
        }
    }

    public record FundNav(String code, String name, BigDecimal nav, LocalDate navDate) {}
}
