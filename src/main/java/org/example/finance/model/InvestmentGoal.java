package org.example.finance.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

@Data
@Entity
@Table(name = "investment_goals", indexes = {
    @Index(name = "idx_goal_user", columnList = "user_id")
})
public class InvestmentGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String goalName;        // 目标名称，如"退休金"、"买房首付"
    private BigDecimal targetAmount; // 目标金额
    private BigDecimal currentAmount = BigDecimal.ZERO; // 当前已积累金额
    private LocalDate targetDate;   // 目标达成日期
    private String riskLevel;       // CONSERVATIVE / BALANCED / AGGRESSIVE
    private int priority;           // 优先级 1=最高
    private String notes;

    @Column(nullable = false)
    private String status = "ACTIVE"; // ACTIVE / COMPLETED / PAUSED

    private Date createTime;
}
