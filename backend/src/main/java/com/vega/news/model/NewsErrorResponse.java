package com.vega.news.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NewsErrorResponse {
    private String status;
    private String code;
    private String isin;
    private String message;
}
