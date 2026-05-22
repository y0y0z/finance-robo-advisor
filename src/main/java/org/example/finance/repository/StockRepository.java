package org.example.finance.repository;

import org.example.finance.model.Stock;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockRepository extends JpaRepository<Stock, Long> {

    /** 查询某用户关注的指定代码（用于预警检测时精确匹配） */
    Stock findByCode(String code);

    /** 查询某用户关注的所有股票/基金 */
    List<Stock> findByUser(User user);

    /** 查询某用户关注的指定代码（用户隔离版，避免跨用户冲突） */
    Stock findByUserAndCode(User user, String code);
}
