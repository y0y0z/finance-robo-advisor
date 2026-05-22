package org.example.finance.controller;

import jakarta.servlet.http.HttpSession;
import org.example.finance.constant.ModelKeys;
import org.example.finance.constant.Routes;
import org.example.finance.constant.SessionKeys;
import org.example.finance.constant.Views;
import org.example.finance.model.News;
import org.example.finance.model.User;
import org.example.finance.service.NewsKeywordFetchService;
import org.example.finance.service.NewsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class NewsController {

    private final NewsService newsService;
    private final NewsKeywordFetchService newsKeywordFetchService;

    public NewsController(NewsService newsService, NewsKeywordFetchService newsKeywordFetchService) {
        this.newsService = newsService;
        this.newsKeywordFetchService = newsKeywordFetchService;
    }

    @GetMapping("/news")
    public String news(@RequestParam(required = false) String keyword,
                       HttpSession session, Model model) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        List<News> newsList = (keyword != null && !keyword.isBlank())
                ? newsService.getNewsByKeyword(user, keyword)   // 校验关键词属于当前用户
                : newsService.getAllNews(user);                  // 只返回当前用户关键词的新闻

        model.addAttribute(ModelKeys.NEWS, newsList);
        model.addAttribute(ModelKeys.KEYWORDS, newsService.getKeywordsByUser(user));
        model.addAttribute(ModelKeys.ACTIVE_KEYWORD, keyword);
        return Views.NEWS;
    }

    @PostMapping("/news/keywords/add")
    public String addKeyword(@RequestParam String keyword,
                             HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        keyword = keyword.trim();

        if (keyword.isEmpty()) {
            ra.addFlashAttribute("error", "关键词不能为空");
            return Routes.redirectTo(Routes.NEWS);
        }
        if (keyword.length() > 20) {
            ra.addFlashAttribute("error", "关键词长度不能超过20字");
            return Routes.redirectTo(Routes.NEWS);
        }
        if (!newsService.addKeyword(user, keyword)) {
            ra.addFlashAttribute("error", "关键词「" + keyword + "」已存在");
            return Routes.redirectTo(Routes.NEWS);
        }

        final String kw = keyword;
        newsKeywordFetchService.fetchNowAsync(kw);

        ra.addFlashAttribute("success", "关键词「" + keyword + "」添加成功，正在后台抓取相关新闻...");
        return Routes.redirectTo(Routes.NEWS);
    }

    @GetMapping("/news/keywords/delete/{id}")
    public String deleteKeyword(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute(SessionKeys.USER);
        newsService.deleteKeyword(id, user);
        return Routes.redirectTo(Routes.NEWS);
    }

    @GetMapping("/news/keywords/fetch/{id}")
    public String fetchNow(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        User user = (User) session.getAttribute(SessionKeys.USER);

        newsService.getKeywordsByUser(user).stream()
                .filter(kw -> kw.getId().equals(id))
                .findFirst()
                .ifPresent(kw -> newsKeywordFetchService.fetchNowAsync(kw.getKeyword()));

        ra.addFlashAttribute("success", "已触发抓取，请稍后刷新页面查看");
        return Routes.redirectTo(Routes.NEWS);
    }
}
