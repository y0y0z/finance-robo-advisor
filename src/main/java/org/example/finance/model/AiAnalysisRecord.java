package org.example.finance.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.Date;

/**
 * AI 分析记录表
 * 保存每次 AI 分析的输入参数和输出结果，供用户随时查看历史
 */
@Data
@Entity
@Table(name = "ai_analysis_records")
public class AiAnalysisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * 分析类型：
     *   PORTFOLIO   - 整体持仓分析
     *   STOCK       - 单只股票/基金专项分析
     *   NEWS        - 单条新闻解读
     *   NEWS_KEYWORD - 关键词情绪汇总
     */
    @Column(nullable = false, length = 20)
    private String type;

    /** 分析标题（股票名/关键词/新闻标题，用于列表展示） */
    private String subject;

    /** AI 输出的完整 Markdown 内容 */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 生成耗时（毫秒） */
    private Long durationMs;

    /** 创建时间 */
    private Date createdAt;
}
