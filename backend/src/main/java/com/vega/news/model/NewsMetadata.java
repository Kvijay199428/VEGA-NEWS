package com.vega.news.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsMetadata {
    private String isin;
    private int totalArticles;
    private long lastUpdated;
    private long latestArticle;
}
