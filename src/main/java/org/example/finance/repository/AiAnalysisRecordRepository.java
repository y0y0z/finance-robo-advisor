package org.example.finance.repository;

import org.example.finance.model.AiAnalysisRecord;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAnalysisRecordRepository extends JpaRepository<AiAnalysisRecord, Long> {

    /** 查询某用户所有记录，按时间倒序 */
    List<AiAnalysisRecord> findByUserOrderByCreatedAtDesc(User user);
}
