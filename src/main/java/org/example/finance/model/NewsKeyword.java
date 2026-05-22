package org.example.finance.model;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * 财经新闻关键词
 * 用户可以设置关键词，系统每30分钟自动从财经网站抓取包含该关键词的新闻。
 */
@Data
@Entity
@Table(name = "news_keywords")
public class NewsKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关键词内容，如 "比亚迪"、"央行降息"、"新能源" */
    @Column(nullable = false)
    private String keyword;

    /** 关联用户（用户隔离） */
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    /** 创建时间 */
    private Date createTime;

    /** 最近一次抓取时间 */
    private Date lastFetchTime;
}
