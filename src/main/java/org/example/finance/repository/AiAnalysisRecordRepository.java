package org.example.finance.repository;

import org.example.finance.model.AiAnalysisRecord;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAnalysisRecordRepository extends JpaRepository<AiAnalysisRecord, Long> {

    /** 查询某用户所有记录，按时间倒序 */
    List<AiAnalysisRecord> findByUserOrderByCreatedAtDesc(User user);

    /** 查询某类分析的最近记录，作为下一次 AI 分析的历史上下文 */
    List<AiAnalysisRecord> findTop3ByUserAndTypeOrderByCreatedAtDesc(User user, String type);

    /** 查询某个分析对象的最近记录，支持标的、目标和关键词等场景的历史对比 */
    List<AiAnalysisRecord> findTop3ByUserAndTypeAndSubjectOrderByCreatedAtDesc(User user, String type, String subject);
}
