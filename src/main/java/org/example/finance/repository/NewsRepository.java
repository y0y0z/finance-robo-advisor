package org.example.finance.repository;

import org.example.finance.model.News;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsRepository extends JpaRepository<News, Long> {
    List<News> findTop20ByKeywordOrderByFetchedAtDesc(String keyword);
    boolean existsByUrl(String url);

    /** 按关键词列表查最近新闻（用于用户隔离：只看自己关键词的新闻） */
    List<News> findTop50ByKeywordInOrderByFetchedAtDesc(List<String> keywords);
}
