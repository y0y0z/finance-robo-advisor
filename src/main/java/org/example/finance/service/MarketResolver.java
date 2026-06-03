package org.example.finance.service;

import com.futu.openapi.pb.QotCommon;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class MarketResolver {

    public static final String MARKET_AUTO = "AUTO";
    public static final String MARKET_CN_SH = "CN_SH";
    public static final String MARKET_CN_SZ = "CN_SZ";
    public static final String MARKET_HK = "HK";
    public static final String MARKET_US = "US";
    public static final String MARKET_FUND = "FUND";
    public static final String MARKET_UNKNOWN = "UNKNOWN";

    public String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    public String normalizeMarket(String type, String code, String market) {
        String requested = normalizeMarketLabel(market);
        if (!MARKET_AUTO.equals(requested) && !MARKET_UNKNOWN.equals(requested)) {
            return requested;
        }
        return inferMarket(code);
    }

    public String displayName(String market) {
        return switch (normalizeMarketLabel(market)) {
            case MARKET_CN_SH -> "A股-沪市";
            case MARKET_CN_SZ -> "A股-深市";
            case MARKET_HK -> "港股";
            case MARKET_US -> "美股";
            case MARKET_FUND -> "基金";
            default -> "未知";
        };
    }

    public QotCommon.Security toFutuSecurity(String code, String market) {
        String normalizedCode = normalizeCode(code);
        QotCommon.QotMarket futuMarket = switch (normalizeMarketLabel(market)) {
            case MARKET_CN_SH -> QotCommon.QotMarket.QotMarket_CNSH_Security;
            case MARKET_CN_SZ -> QotCommon.QotMarket.QotMarket_CNSZ_Security;
            case MARKET_HK -> QotCommon.QotMarket.QotMarket_HK_Security;
            case MARKET_US -> QotCommon.QotMarket.QotMarket_US_Security;
            default -> QotCommon.QotMarket.QotMarket_Unknown;
        };
        if (futuMarket == QotCommon.QotMarket.QotMarket_Unknown || normalizedCode.isBlank()) {
            return null;
        }
        return QotCommon.Security.newBuilder()
                .setMarket(futuMarket.getNumber())
                .setCode(normalizedCode)
                .build();
    }

    private String inferMarket(String code) {
        String normalizedCode = normalizeCode(code);
        if (normalizedCode.isBlank()) {
            return MARKET_UNKNOWN;
        }
        if (normalizedCode.matches("^[A-Z][A-Z0-9.-]{0,14}$")) {
            return MARKET_US;
        }
        if (normalizedCode.matches("^\\d{5}$")) {
            return MARKET_HK;
        }
        if (normalizedCode.matches("^6\\d{5}$") || normalizedCode.matches("^688\\d{3}$")) {
            return MARKET_CN_SH;
        }
        if (normalizedCode.matches("^[03]\\d{5}$")) {
            return MARKET_CN_SZ;
        }
        if (normalizedCode.matches("^(5|15|16|18|50|51|52)\\d+$")) {
            return MARKET_FUND;
        }
        return MARKET_UNKNOWN;
    }

    private String normalizeMarketLabel(String market) {
        if (market == null || market.isBlank()) {
            return MARKET_AUTO;
        }
        String value = market.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (value) {
            case "AUTO", "自动", "自动识别" -> MARKET_AUTO;
            case "CN", "A", "A股", "ASHARE", "A_SHARE", "沪市", "SH", "SHANGHAI", "CN_SH", "CNSH", "科创板" -> MARKET_CN_SH;
            case "SZ", "SHENZHEN", "CN_SZ", "CNSZ", "深市", "创业板" -> MARKET_CN_SZ;
            case "HK", "港股", "HONGKONG", "HONG_KONG" -> MARKET_HK;
            case "US", "美股", "USA", "NASDAQ", "NYSE" -> MARKET_US;
            case "FUND", "基金" -> MARKET_FUND;
            case "UNKNOWN", "未知" -> MARKET_UNKNOWN;
            default -> value;
        };
    }
}
