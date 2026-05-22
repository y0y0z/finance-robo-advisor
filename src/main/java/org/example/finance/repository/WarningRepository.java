package org.example.finance.repository;

import org.example.finance.model.User;
import org.example.finance.model.Warning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarningRepository extends JpaRepository<Warning, Long> {
    // 根据代码查询警告
    Warning findByCode(String code);

    // 根据类型查询警告
    List<Warning> findByType(String type);

    // 根据用户查询该用户所有预警
    List<Warning> findByUser(User user);

    // 查询指定用户的已触发预警（用于仪表盘高亮展示）
    List<Warning> findByUserAndStatusIn(User user, List<String> statuses);

    // 查询所有处于监控中的预警（定时任务使用）
    List<Warning> findByStatus(String status);

    // 防重：查某用户某代码下未处理的 AI 预警（含义字段用于区分子类型）
    boolean existsByUserAndCodeAndMeaningAndStatusIn(User user, String code, String meaning, List<String> statuses);
}
