package com.vega.news.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.news.config.AnalyticAccountTokenProvider;
import com.vega.news.model.NewsArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpstoxNewsClient {

    private final HttpClient httpClient;
    private final AnalyticAccountTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;

    private static final String UPSTOX_API_URL = "https://api.upstox.com/v2/news";

    public List<NewsArticle> fetchNews(String category, String isin, String instrumentKey) {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            log.error("No valid Upstox token available");
            return new ArrayList<>();
        }

        String url = UPSTOX_API_URL + "?category=" + category;
        if ("instrument_keys".equals(category) && instrumentKey != null) {
            try {
                String encodedKey = java.net.URLEncoder.encode(instrumentKey, StandardCharsets.UTF_8.toString());
                url += "&instrument_keys=" + encodedKey;
            } catch (Exception e) {
                log.error("Failed to encode instrument_keys", e);
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return parseNewsResponse(response.body(), isin, instrumentKey);
            } else {
                log.error("Failed to fetch news. Status: {}, Body: {}", response.statusCode(), response.body());
                // Retry once as per strategy
                log.info("Retrying fetch news once...");
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return parseNewsResponse(response.body(), isin, instrumentKey);
                } else {
                    log.error("Retry failed. Status: {}, Body: {}", response.statusCode(), response.body());
                }
            }
        } catch (Exception e) {
            log.error("Exception while fetching news from Upstox", e);
        }
        return new ArrayList<>();
    }

    private List<NewsArticle> parseNewsResponse(String responseBody, String isin, String instrumentKey) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.path("data");
        List<NewsArticle> articles = new ArrayList<>();

        if (data.isObject()) {
            data.fields().forEachRemaining(entry -> {
                JsonNode items = entry.getValue();
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String heading = item.path("heading").asText("");
                        String summary = item.path("summary").asText("");
                        String articleLink = item.path("article_link").asText("");
                        long publishedTime = item.path("published_time").asLong(System.currentTimeMillis());
                        
                        String sourceHash = generateSourceHash(heading, articleLink, publishedTime);

                        NewsArticle article = NewsArticle.builder()
                                .isin(isin)
                                .instrumentKey(instrumentKey)
                                .heading(heading)
                                .summary(summary)
                                .thumbnail(item.path("thumbnail").asText(""))
                                .articleLink(articleLink)
                                .publishedTime(publishedTime)
                                .sourceHash(sourceHash)
                                .build();

                        articles.add(article);
                    }
                }
            });
        }
        return articles;
    }

    private String generateSourceHash(String heading, String link, long time) {
        try {
            String input = heading + link + time;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate source hash", e);
        }
    }
}
