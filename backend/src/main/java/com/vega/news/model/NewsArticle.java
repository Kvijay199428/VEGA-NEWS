package com.vega.news.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticle {
    private String isin;
    private String instrumentKey;
    private String heading;
    private String summary;
    private String thumbnail;
    private String articleLink;
    private long publishedTime;
    private String sourceHash;
}
