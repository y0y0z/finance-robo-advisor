package org.example.finance.service;

import org.example.finance.model.News;
import org.example.finance.model.NewsKeyword;
import org.example.finance.model.User;
import org.example.finance.repository.NewsKeywordRepository;
import org.example.finance.repository.NewsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);

    private final NewsRepository newsRepository;
    private final NewsKeywordRepository newsKeywordRepository;

    public NewsService(NewsRepository newsRepository, NewsKeywordRepository newsKeywordRepository) {
        this.newsRepository = newsRepository;
        this.newsKeywordRepository = newsKeywordRepository;
    }

    /**
     * 获取当前用户所有关键词对应的最新50条新闻（用户隔离）
     * 若用户尚未添加任何关键词，返回空列表
     */
    public List<News> getAllNews(User user) {
        List<String> keywords = getKeywordsByUser(user)
                .stream()
                .map(NewsKeyword::getKeyword)
                .toList();
        if (keywords.isEmpty()) return Collections.emptyList();
        return newsRepository.findTop50ByKeywordInOrderByFetchedAtDesc(keywords);
    }

    /** 按关键词查新闻，先校验该关键词属于当前用户 */
    public List<News> getNewsByKeyword(User user, String keyword) {
        boolean owned = getKeywordsByUser(user).stream()
                .anyMatch(kw -> kw.getKeyword().equals(keyword));
        if (!owned) return Collections.emptyList();
        return newsRepository.findTop20ByKeywordOrderByFetchedAtDesc(keyword);
    }

    /** 获取当前用户设置的所有关键词 */
    public List<NewsKeyword> getKeywordsByUser(User user) {
        return newsKeywordRepository.findByUser(user);
    }

    /** 添加关键词（同一用户不允许重复） */
    public boolean addKeyword(User user, String keyword) {
        if (newsKeywordRepository.existsByUserAndKeyword(user, keyword)) {
            return false;
        }
        NewsKeyword kw = new NewsKeyword();
        kw.setUser(user);
        kw.setKeyword(keyword.trim());
        kw.setCreateTime(new Date());
        newsKeywordRepository.save(kw);
        log.info("用户 [{}] 添加关键词: {}", user.getName(), keyword);
        return true;
    }

    /** 删除关键词（校验归属权） */
    public void deleteKeyword(Long id, User user) {
        newsKeywordRepository.findById(id).ifPresent(kw -> {
            if (kw.getUser().getId().equals(user.getId())) {
                newsKeywordRepository.delete(kw);
                log.info("用户 [{}] 删除关键词: {}", user.getName(), kw.getKeyword());
            }
        });
    }
}
