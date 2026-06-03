package org.example.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "futu.opend")
public class FutuOpenDProperties {

    private boolean enabled = true;
    private String host = "127.0.0.1";
    private int port = 11111;
    private int connectTimeoutMs = 5000;
    private int quoteTimeoutMs = 5000;
    private boolean autoSubscribe = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getQuoteTimeoutMs() {
        return quoteTimeoutMs;
    }

    public void setQuoteTimeoutMs(int quoteTimeoutMs) {
        this.quoteTimeoutMs = quoteTimeoutMs;
    }

    public boolean isAutoSubscribe() {
        return autoSubscribe;
    }

    public void setAutoSubscribe(boolean autoSubscribe) {
        this.autoSubscribe = autoSubscribe;
    }
}
