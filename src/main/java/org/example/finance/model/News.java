package org.example.finance.model;


import jakarta.persistence.*;
import lombok.Data;
import java.util.Date;

@Data
@Entity
@Table(name = "news")
public class News {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String source;
    private Date publishTime;
    private String category; // 政治、经济等
    private String url;

    /** 触发该新闻抓取的关键词（为空表示非关键词触发） */
    private String keyword;

    /** 抓取入库时间 */
    private Date fetchedAt;
}

