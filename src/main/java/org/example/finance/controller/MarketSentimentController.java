package org.example.finance.controller;

import org.example.finance.service.MarketSentimentService;
import org.example.finance.service.MarketSentimentService.MarketSentiment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 市场情绪控制器
 */
@RestController
public class MarketSentimentController {

    private final MarketSentimentService marketSentimentService;

    public MarketSentimentController(MarketSentimentService marketSentimentService) {
        this.marketSentimentService = marketSentimentService;
    }

    /**
     * 前端轮询接口：返回最新市场情绪数据
     * 未初始化时返回默认中性值，避免前端报错
     */
    @GetMapping("/api/market-sentiment")
    public ResponseEntity<Map<String, Object>> getSentiment() {
        MarketSentiment s = marketSentimentService.getLatest();
        if (s == null) {
            // 尚未初始化，返回占位数据
            return ResponseEntity.ok(Map.of(
                    "score", 50,
                    "level", "数据加载中",
                    "color", "#6c757d",
                    "upCount", 0,
                    "downCount", 0,
                    "flatCount", 0,
                    "totalCount", 0,
                    "updateTime", "--:--:--"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "score",      s.score(),
                "level",      s.level(),
                "color",      s.color(),
                "upCount",    s.upCount(),
                "downCount",  s.downCount(),
                "flatCount",  s.flatCount(),
                "totalCount", s.totalCount(),
                "updateTime", s.updateTime()
        ));
    }
}
