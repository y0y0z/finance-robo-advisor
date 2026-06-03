package org.example.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.finance.model.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 市场行情数据服务
 *
 * 数据来源：
 *   股票 / 基金 → Futu OpenD
 *   天天基金仅作为基金可选兜底
 *
 * 调用链：StockPriceUpdateService → MarketDataService → 外部 API → 返回填充好的 Stock 对象
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    /** 东方财富行情接口：fltt=2 表示浮点直出，不需要÷100 */
    private static final String EASTMONEY_STOCK_URL =
            "https://push2.eastmoney.com/api/qt/stock/get" +
            "?secid=%s&fields=f43,f169,f170,f162,f167&fltt=2&invt=2";

    /** 天天基金实时估值（JSONP 格式） */
    private static final String FUND_ESTIMATE_URL =
            "http://fundgz.1234567.com.cn/js/%s.js?rt=%d";

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final FutuQuoteClient futuQuoteClient;

    @Value("${marketdata.stock.eastmoney-fallback-enabled:false}")
    private boolean eastmoneyFallbackEnabled;

    @Value("${marketdata.fund.eastmoney-fallback-enabled:false}")
    private boolean fundFallbackEnabled;

    public MarketDataService(OkHttpClient client, ObjectMapper mapper, FutuQuoteClient futuQuoteClient) {
        this.client = client;
        this.mapper = mapper;
        this.futuQuoteClient = futuQuoteClient;
    }

    /**
     * 获取股票实时行情，成功则更新 stock 字段并返回 true，失败返回 false
     */
    public boolean fetchStockPrice(Stock stock) {
        Optional<QuoteSnapshot> snapshot = futuQuoteClient.getQuote(stock.getCode(), stock.getMarket());
        if (snapshot.isPresent()) {
            applyQuote(stock, snapshot.get());
            log.debug("Futu 股票行情 [{}({})]：价格={} 涨跌幅={}%",
                    stock.getName(), stock.getCode(), stock.getPrice(), stock.getChangePercent());
            return true;
        }
        if (eastmoneyFallbackEnabled) {
            return fetchStockPriceFromEastmoney(stock);
        }
        return false;
    }

    private void applyQuote(Stock stock, QuoteSnapshot quote) {
        stock.setPrice(quote.price());
        stock.setPriceChange(quote.priceChange() != null ? quote.priceChange() : BigDecimal.ZERO);
        stock.setChangePercent(quote.changePercent() != null ? quote.changePercent() : BigDecimal.ZERO);
        if (quote.pe() != null) stock.setPe(quote.pe());
        if (quote.pb() != null) stock.setPb(quote.pb());
        if ((stock.getName() == null || stock.getName().isBlank()) && quote.name() != null && !quote.name().isBlank()) {
            stock.setName(quote.name());
        }
    }

    private boolean fetchStockPriceFromEastmoney(Stock stock) {
        String secid = buildSecId(stock.getCode());
        if (secid == null) {
            log.warn("无法识别股票代码 [{}] 的东方财富市场，跳过兜底行情拉取", stock.getCode());
            return false;
        }

        String url = String.format(EASTMONEY_STOCK_URL, secid);
        String body = httpGet(url);
        if (body == null) return false;

        try {
            JsonNode data = mapper.readTree(body).path("data");
            if (data.isMissingNode() || data.isNull()) {
                log.warn("股票 [{}({})] 东方财富行情返回空数据", stock.getName(), stock.getCode());
                return false;
            }

            BigDecimal price       = decimal(data, "f43");
            BigDecimal priceChange = decimal(data, "f169");
            BigDecimal changePct   = decimal(data, "f170");
            BigDecimal pe          = decimal(data, "f162");
            BigDecimal pb          = decimal(data, "f167");

            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("股票 [{}({})] 东方财富价格异常（{}），跳过本次更新", stock.getName(), stock.getCode(), price);
                return false;
            }

            applyQuote(stock, new QuoteSnapshot(price, priceChange, changePct, pe, pb, null));
            return true;
        } catch (Exception e) {
            log.error("解析东方财富股票行情响应失败 [{}({})]：{}", stock.getName(), stock.getCode(), e.getMessage());
            return false;
        }
    }

    /**
     * 获取基金实时行情，优先使用 Futu OpenD；天天基金仅在显式开启时兜底。
     */
    public boolean fetchFundPrice(Stock stock) {
        Optional<QuoteSnapshot> snapshot = futuQuoteClient.getQuote(stock.getCode(), stock.getMarket());
        if (snapshot.isPresent()) {
            applyQuote(stock, snapshot.get());
            stock.setNav(stock.getPrice());
            log.debug("Futu 基金行情 [{}({})]：净值={} 涨跌幅={}%",
                    stock.getName(), stock.getCode(), stock.getPrice(), stock.getChangePercent());
            return true;
        }
        if (!fundFallbackEnabled) {
            return false;
        }

        String url = String.format(FUND_ESTIMATE_URL, stock.getCode(), System.currentTimeMillis());
        String body = httpGet(url);
        if (body == null) return false;

        try {
            // 解析 JSONP 取括号内的 JSON
            int start = body.indexOf('(');
            int end   = body.lastIndexOf(')');
            if (start < 0 || end <= start) {
                log.warn("基金 [{}({})] JSONP 格式异常：{}", stock.getName(), stock.getCode(), body);
                return false;
            }

            JsonNode data = mapper.readTree(body.substring(start + 1, end));

            String gszStr   = data.path("gsz").asText("").trim();
            String dwjzStr  = data.path("dwjz").asText("").trim();
            String gszzlStr = data.path("gszzl").asText("0").trim();

            // 优先用今日实时估值，非交易时段降级用昨日净值
            BigDecimal price = null;
            boolean isEstimate = false;
            if (!gszStr.isEmpty() && !gszStr.equals("0")) {
                price = safeBD(gszStr);
                isEstimate = true;
            }
            if (price == null && !dwjzStr.isEmpty()) {
                price = safeBD(dwjzStr);
            }

            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("基金 [{}({})] 净值数据无效，gsz={} dwjz={}",
                        stock.getName(), stock.getCode(), gszStr, dwjzStr);
                return false;
            }

            BigDecimal changePct = safeBD(gszzlStr);
            if (changePct == null) changePct = BigDecimal.ZERO;

            // 涨跌额 = 净值 × 涨跌幅% / 100
            BigDecimal priceChange = price.multiply(changePct)
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);

            stock.setPrice(price);
            stock.setNav(price);
            stock.setChangePercent(changePct);
            stock.setPriceChange(priceChange);

            log.debug("基金估值 [{}({})]：净值={} 涨跌幅={}% {}",
                    stock.getName(), stock.getCode(), price, changePct,
                    isEstimate ? "(实时估值)" : "(昨日净值)");
            return true;

        } catch (Exception e) {
            log.error("解析基金估值响应失败 [{}({})]：{}", stock.getName(), stock.getCode(), e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────

    /**
     * 根据 A 股代码前缀构建东方财富 secid（市场编号.代码）
     * 沪市=1（含科创板688）  深市/创业板=0
     * 基金代码不走此方法
     */
    public String buildSecId(String code) {
        if (code == null || code.isBlank()) return null;
        if (code.startsWith("6") || code.startsWith("688")) return "1." + code;
        if (code.startsWith("0") || code.startsWith("3"))   return "0." + code;
        return null;
    }

    /** 执行 HTTP GET，返回响应体字符串；失败返回 null */
    private String httpGet(String url) {
        Request req = new Request.Builder()
                .url(url)
                .addHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Referer", "https://www.eastmoney.com/")
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                log.warn("HTTP {} 请求失败，状态码 {}", url, resp.code());
                return null;
            }
            return resp.body() != null ? resp.body().string() : null;
        } catch (Exception e) {
            log.warn("HTTP 请求异常 {}：{}", url, e.getMessage());
            return null;
        }
    }

    /** 从 JsonNode 中安全读取 BigDecimal */
    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        return safeBD(n.asText());
    }

    /** 字符串安全转 BigDecimal，失败返回 null */
    private BigDecimal safeBD(String s) {
        if (s == null || s.isBlank() || s.equals("-")) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
