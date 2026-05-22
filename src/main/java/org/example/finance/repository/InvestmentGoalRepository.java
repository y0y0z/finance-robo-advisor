package org.example.finance.repository;

import org.example.finance.model.InvestmentGoal;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InvestmentGoalRepository extends JpaRepository<InvestmentGoal, Long> {
    List<InvestmentGoal> findByUserOrderByPriorityAscCreateTimeDesc(User user);
    List<InvestmentGoal> findByUserAndStatus(User user, String status);
}
