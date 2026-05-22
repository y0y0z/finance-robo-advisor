package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.ModelKeys;
import org.example.finance.constant.Routes;
import org.example.finance.constant.SessionKeys;
import org.example.finance.constant.Views;
import org.example.finance.model.AiAnalysisRecord;
import org.example.finance.model.User;
import org.example.finance.repository.AiAnalysisRecordRepository;
import org.example.finance.service.AIService;
import org.example.finance.service.WarningCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Controller
public class AIController {

    private static final Logger log = LoggerFactory.getLogger(AIController.class);

    private final AIService aiService;
    private final WarningCheckService warningCheckService;
    private final AiAnalysisRecordRepository recordRepository;

    public AIController(AIService aiService,
                        WarningCheckService warningCheckService,
                        AiAnalysisRecordRepository recordRepository) {
        this.aiService = aiService;
        this.warningCheckService = warningCheckService;
        this.recordRepository = recordRepository;
    }

    @GetMapping("/ai-advice")
    public String aiAdvice(HttpSession session, Model model) {
        model.addAttribute(ModelKeys.USER, session.getAttribute(SessionKeys.USER));
        return Views.AI_ADVICE;
    }

    /** 异步接口：全局投资建议（整体持仓分析） */
    @GetMapping("/api/ai-advice")
    public ResponseEntity<String> getAdvice(HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        return ResponseEntity.ok(aiService.generateInvestmentAdvice(user));
    }

    /**
     * 异步接口：单只股票/基金专项 AI 分析
     * 结合相关新闻、行情数据、用户持仓给出专项建议
     */
    @GetMapping("/api/ai-advice/stock")
    public ResponseEntity<String> getStockAdvice(@RequestParam String code,
                                                  @RequestParam String name,
                                                  HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        log.info("用户 [{}] 请求 AI 专项分析: {}({})", user.getName(), name, code);
        return ResponseEntity.ok(aiService.generateStockAdvice(user, code, name));
    }

    /**
     * 前端轮询接口：取出当前用户待弹出的预警通知，取后即清空（保证每条只弹一次）
     */
    @GetMapping("/api/warnings/pending")
    public ResponseEntity<List<Map<String, String>>> getPendingWarnings(HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        return ResponseEntity.ok(warningCheckService.pollNotifications(user.getId()));
    }

    /**
     * 单条新闻 AI 解读：分析利好/利空 + 结合用户持仓的操作建议
     */
    @GetMapping("/api/ai-advice/news")
    public ResponseEntity<String> getNewsAnalysis(@RequestParam String title,
                                                   @RequestParam(required = false, defaultValue = "") String summary,
                                                   @RequestParam(required = false, defaultValue = "") String source,
                                                   HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        log.info("用户 [{}] 请求新闻AI解读: {}", user.getName(), title);
        return ResponseEntity.ok(aiService.generateNewsAnalysis(user, title, summary, source));
    }

    /**
     * 关键词新闻情绪汇总：汇总某关键词下所有新闻的整体情绪和操作建议
     */
    @GetMapping("/api/ai-advice/news-keyword")
    public ResponseEntity<String> getKeywordSentiment(@RequestParam String keyword,
                                                       HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        log.info("用户 [{}] 请求关键词情绪分析: {}", user.getName(), keyword);
        return ResponseEntity.ok(aiService.generateKeywordSentiment(user, keyword));
    }

    /**
     * AI 分析历史列表页
     */
    @GetMapping("/ai-advice/history")
    public String historyPage(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        List<AiAnalysisRecord> records = recordRepository.findByUserOrderByCreatedAtDesc(user);
        model.addAttribute(ModelKeys.USER, user);
        model.addAttribute("records", records);
        model.addAttribute("total", records.size());
        return Views.AI_ADVICE_HISTORY;
    }

    /**
     * 单条 AI 分析记录详情页
     */
    @GetMapping("/ai-advice/history/{id}")
    public String historyDetail(@PathVariable Long id, HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        java.util.Optional<AiAnalysisRecord> opt = recordRepository.findById(id);
        if (opt.isEmpty() || !opt.get().getUser().getId().equals(user.getId())) {
            return Routes.redirectTo(Routes.AI_ADVICE_HISTORY);
        }
        model.addAttribute(ModelKeys.USER, user);
        model.addAttribute("record", opt.get());
        return Views.AI_ADVICE_HISTORY_DETAIL;
    }
}
