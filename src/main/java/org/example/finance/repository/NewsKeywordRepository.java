package org.example.finance.repository;

import org.example.finance.model.NewsKeyword;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsKeywordRepository extends JpaRepository<NewsKeyword, Long> {
    List<NewsKeyword> findByUser(User user);
    boolean existsByUserAndKeyword(User user, String keyword);
}
