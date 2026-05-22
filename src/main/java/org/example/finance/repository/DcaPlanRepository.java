package org.example.finance.repository;

import org.example.finance.model.DcaPlan;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import org.example.finance.model.InvestmentGoal;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface DcaPlanRepository extends JpaRepository<DcaPlan, Long> {
    List<DcaPlan> findByUserOrderByCreateTimeDesc(User user);
    List<DcaPlan> findByUserAndStatus(User user, String status);
    List<DcaPlan> findByStatus(String status);
    List<DcaPlan> findByGoal(InvestmentGoal goal);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM DcaRecord r WHERE r.plan = :plan")
    BigDecimal sumExecutedAmount(@Param("plan") DcaPlan plan);
}
