package org.example.finance.repository;

import org.example.finance.model.DcaPlan;
import org.example.finance.model.DcaRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DcaRecordRepository extends JpaRepository<DcaRecord, Long> {
    List<DcaRecord> findByPlanOrderByExecuteDateDesc(DcaPlan plan);
    int countByPlan(DcaPlan plan);
    void deleteByPlan(DcaPlan plan);
}
