package org.example.finance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

/**
 * HTTP 客户端与序列化工具的单例 Bean 配置
 *
 * ObjectMapper 是线程安全的，全局共享一个实例可以避免重复创建的开销。
 * OkHttpClient 提供一个默认实例（10s 连接 / 15s 读写），各服务如需不同超时
 * 可通过 client.newBuilder().readTimeout(...).build() 派生，共享底层连接池。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * 共享的默认 OkHttpClient（行情/新闻抓取类服务使用）。
     * AI 类服务的超时来自 DeepSeekProperties，在调用时通过 newBuilder() 派生。
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
