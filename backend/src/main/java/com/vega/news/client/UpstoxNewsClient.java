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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpstoxNewsClient {

    private final HttpClient httpClient;
    private final AnalyticAccountTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;

    private static final String UPSTOX_API_URL = "https://api.upstox.com/v2/news";
    private static final Set<Integer> RETRYABLE_STATUS_CODES = new HashSet<>(Arrays.asList(429, 500, 502, 503, 504));

    public List<NewsArticle> fetchNews(String category, String isin, String instrumentKey) {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            log.error("No valid Upstox token available");
            return new ArrayList<>();
        }

        String url = UPSTOX_API_URL + "?category=" + category;
        int keyCount = 0;
        if ("instrument_keys".equals(category) && instrumentKey != null) {
            try {
                keyCount = instrumentKey.split(",").length;
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
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return parseNewsResponse(response.body(), isin, instrumentKey);
            } else {
                log.error("Failed to fetch news. CATEGORY={}, KEYS={}, STATUS={}, Body={}", category, keyCount, response.statusCode(), response.body());
                
                if (RETRYABLE_STATUS_CODES.contains(response.statusCode())) {
                    log.info("Retrying fetch news once due to retryable status {}...", response.statusCode());
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        return parseNewsResponse(response.body(), isin, instrumentKey);
                    } else {
                        log.error("Retry failed. CATEGORY={}, KEYS={}, STATUS={}, Body={}", category, keyCount, response.statusCode(), response.body());
                    }
                } else {
                    log.warn("Non-retryable status {} received. Skipping retry.", response.statusCode());
                }
            }
        } catch (Exception e) {
            log.error("Exception while fetching news from Upstox. CATEGORY={}, KEYS={}", category, keyCount, e);
        }
        return new ArrayList<>();
    }

    private List<NewsArticle> parseNewsResponse(String responseBody, String isin, String instrumentKey) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        
        // Basic schema validation
        if (!root.has("status") || !"success".equals(root.path("status").asText())) {
            log.error("Upstox API returned error or unexpected status. Response: {}", responseBody);
            return new ArrayList<>();
        }

        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            log.error("Upstox API response missing data field. Response: {}", responseBody);
            return new ArrayList<>();
        }

        List<NewsArticle> articles = new ArrayList<>();

        if (data.isObject()) {
            data.fields().forEachRemaining(entry -> {
                String responseInstrumentKey = entry.getKey();
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
                                .instrumentKey(responseInstrumentKey)
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
        } else {
            log.error("Schema mismatch: expected 'data' to be an object in Upstox news response. Response: {}", responseBody);
        }
        return articles;
    }

    private String generateSourceHash(String heading, String link, long time) {
        try {
            String input = (link != null && !link.isEmpty()) ? link : heading;
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
