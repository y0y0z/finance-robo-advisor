package org.example.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DeepSeek API 配置属性
 * 统一管理 deepseek.api.* 配置，替代各 Service 中重复的 @Value 注入
 */
@Component
@ConfigurationProperties(prefix = "deepseek.api")
public class DeepSeekProperties {

    private String key;
    private String url;
    private String model;
    private int timeout = 300;

    public String getKey()    { return key; }
    public String getUrl()    { return url; }
    public String getModel()  { return model; }
    public int    getTimeout(){ return timeout; }

    public void setKey(String key)       { this.key = key; }
    public void setUrl(String url)       { this.url = url; }
    public void setModel(String model)   { this.model = model; }
    public void setTimeout(int timeout)  { this.timeout = timeout; }
}
