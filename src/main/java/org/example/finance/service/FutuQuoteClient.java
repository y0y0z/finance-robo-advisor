package org.example.finance.service;

import com.futu.openapi.FTAPI;
import com.futu.openapi.FTAPI_Conn;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Qot;
import com.futu.openapi.pb.Common;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotSub;
import jakarta.annotation.PreDestroy;
import org.example.finance.config.FutuOpenDProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class FutuQuoteClient implements FTSPI_Conn, FTSPI_Qot {

    private static final Logger log = LoggerFactory.getLogger(FutuQuoteClient.class);

    private final FutuOpenDProperties properties;
    private final MarketResolver marketResolver;
    private final ConcurrentMap<Integer, CompletableFuture<QotSub.Response>> subReplies = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CompletableFuture<QotGetBasicQot.Response>> quoteReplies = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private FTAPI_Conn_Qot quoteConn;
    private volatile boolean connected;

    public FutuQuoteClient(FutuOpenDProperties properties, MarketResolver marketResolver) {
        this.properties = properties;
        this.marketResolver = marketResolver;
    }

    public Optional<QuoteSnapshot> getQuote(String code, String market) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        QotCommon.Security security = marketResolver.toFutuSecurity(code, market);
        if (security == null) {
            log.warn("无法识别 Futu 行情市场 [{}({})]", market, code);
            return Optional.empty();
        }
        try {
            ensureConnected();
            if (properties.isAutoSubscribe()) {
                subscribeBasicQuote(security);
            }
            QotGetBasicQot.Response response = requestBasicQuote(security);
            if (response.getRetType() != Common.RetType.RetType_Succeed_VALUE) {
                log.warn("Futu 行情查询失败 [{}({})]: {}", market, code, response.getRetMsg());
                return Optional.empty();
            }
            if (!response.hasS2C() || response.getS2C().getBasicQotListCount() == 0) {
                log.warn("Futu 行情返回空数据 [{}({})]", market, code);
                return Optional.empty();
            }
            return toSnapshot(response.getS2C().getBasicQotList(0));
        } catch (Exception e) {
            log.warn("Futu 行情获取异常 [{}({})]: {}", market, code, e.getMessage());
            return Optional.empty();
        }
    }

    private synchronized void ensureConnected() throws TimeoutException {
        if (connected && quoteConn != null) {
            return;
        }
        if (initialized.compareAndSet(false, true)) {
            FTAPI.init();
        }
        FTAPI_Conn_Qot conn = new FTAPI_Conn_Qot();
        conn.setClientInfo("finance-robo-advisor", 1);
        conn.setConnSpi(this);
        conn.setQotSpi(this);
        if (!conn.initConnect(properties.getHost(), properties.getPort(), false)) {
            throw new IllegalStateException("无法连接 Futu OpenD " + properties.getHost() + ":" + properties.getPort());
        }
        quoteConn = conn;
        waitUntilConnected();
    }

    private void waitUntilConnected() throws TimeoutException {
        long deadline = System.currentTimeMillis() + properties.getConnectTimeoutMs();
        while (!connected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Futu OpenD 连接被中断", e);
            }
        }
        if (!connected) {
            throw new TimeoutException("连接 Futu OpenD 超时");
        }
    }

    private void subscribeBasicQuote(QotCommon.Security security) throws Exception {
        QotSub.C2S c2s = QotSub.C2S.newBuilder()
                .addSecurityList(security)
                .addSubTypeList(QotCommon.SubType.SubType_Basic_VALUE)
                .setIsSubOrUnSub(true)
                .setIsRegOrUnRegPush(false)
                .setIsFirstPush(false)
                .setExtendedTime(true)
                .build();
        QotSub.Request request = QotSub.Request.newBuilder().setC2S(c2s).build();
        int serialNo = quoteConn.sub(request);
        CompletableFuture<QotSub.Response> future = new CompletableFuture<>();
        subReplies.put(serialNo, future);
        QotSub.Response response = future.get(properties.getQuoteTimeoutMs(), TimeUnit.MILLISECONDS);
        subReplies.remove(serialNo);
        if (response.getRetType() != Common.RetType.RetType_Succeed_VALUE) {
            throw new IllegalStateException("Futu 订阅行情失败: " + response.getRetMsg());
        }
    }

    private QotGetBasicQot.Response requestBasicQuote(QotCommon.Security security) throws Exception {
        QotGetBasicQot.C2S c2s = QotGetBasicQot.C2S.newBuilder()
                .addSecurityList(security)
                .build();
        QotGetBasicQot.Request request = QotGetBasicQot.Request.newBuilder().setC2S(c2s).build();
        int serialNo = quoteConn.getBasicQot(request);
        CompletableFuture<QotGetBasicQot.Response> future = new CompletableFuture<>();
        quoteReplies.put(serialNo, future);
        try {
            return future.get(properties.getQuoteTimeoutMs(), TimeUnit.MILLISECONDS);
        } finally {
            quoteReplies.remove(serialNo);
        }
    }

    private Optional<QuoteSnapshot> toSnapshot(QotCommon.BasicQot qot) {
        BigDecimal price = bd(qot.getCurPrice());
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        BigDecimal lastClose = bd(qot.getLastClosePrice());
        BigDecimal priceChange = BigDecimal.ZERO;
        BigDecimal changePercent = BigDecimal.ZERO;
        if (lastClose != null && lastClose.compareTo(BigDecimal.ZERO) > 0) {
            priceChange = price.subtract(lastClose).setScale(4, RoundingMode.HALF_UP);
            changePercent = priceChange.multiply(new BigDecimal("100"))
                    .divide(lastClose, 4, RoundingMode.HALF_UP);
        }
        return Optional.of(new QuoteSnapshot(price, priceChange, changePercent, null, null, qot.getName()));
    }

    private BigDecimal bd(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    @Override
    public void onInitConnect(FTAPI_Conn client, long errCode, String desc) {
        connected = errCode == 0;
        if (!connected) {
            log.warn("Futu OpenD 连接初始化失败: {} {}", errCode, desc);
        }
    }

    @Override
    public void onDisconnect(FTAPI_Conn client, long errCode) {
        connected = false;
        log.warn("Futu OpenD 连接断开: {}", errCode);
    }

    @Override
    public void onReply_Sub(FTAPI_Conn client, int serialNo, QotSub.Response response) {
        CompletableFuture<QotSub.Response> future = subReplies.get(serialNo);
        if (future != null) {
            future.complete(response);
        }
    }

    @Override
    public void onReply_GetBasicQot(FTAPI_Conn client, int serialNo, QotGetBasicQot.Response response) {
        CompletableFuture<QotGetBasicQot.Response> future = quoteReplies.get(serialNo);
        if (future != null) {
            future.complete(response);
        }
    }

    @PreDestroy
    public void close() {
        if (quoteConn != null) {
            quoteConn.close();
        }
        if (initialized.get()) {
            FTAPI.unInit();
        }
    }
}
