```xml
// File: pom.xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    <groupId>com.vega</groupId>
    <artifactId>news</artifactId>
    <version>0.0.1</version>
    <name>vega.news</name>
    <description>Vega News Service</description>
    <properties>
        <java.version>21</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>vega.news.0.0.1</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

```java
// File: src/main/java/com/vega/news/VegaNewsApplication.java
package com.vega.news;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.vega.news.config.NewsProperties;

@SpringBootApplication
@EnableConfigurationProperties(NewsProperties.class)
@EnableScheduling
public class VegaNewsApplication {

    public static void main(String[] args) {
        SpringApplication.run(VegaNewsApplication.class, args);
    }
}
```

```java
// File: src/main/java/com/vega/news/client/UpstoxNewsClient.java
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
```

```java
// File: src/main/java/com/vega/news/config/AnalyticAccountTokenProvider.java
package com.vega.news.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@Slf4j
public class AnalyticAccountTokenProvider {

    @Value("${upstox.auth.file}")
    private String authFile;

    private String accessToken;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        load();
    }

    private void load() {
        try {
            File file = new File(authFile);
            if (!file.exists()) {
                throw new IllegalStateException("Auth file not found at: " + authFile);
            }

            JsonNode root = objectMapper.readTree(file);

            JsonNode analytic = root.path("accounts").path("analytic");

            if (analytic.isMissingNode()) {
                throw new IllegalStateException("accounts.analytic not found in auth file");
            }

            accessToken = analytic.path("accessToken").asText();

            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("analytic accessToken missing or empty");
            }

            log.info("Loaded analytic access token successfully");

        } catch (Exception ex) {
            throw new RuntimeException("Failed loading " + authFile, ex);
        }
    }

    public String getAccessToken() {
        return accessToken;
    }
}
```

```java
// File: src/main/java/com/vega/news/config/AsyncConfig.java
package com.vega.news.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    @Bean
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

```java
// File: src/main/java/com/vega/news/config/HttpClientConfig.java
package com.vega.news.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }
}
```

```java
// File: src/main/java/com/vega/news/config/NewsProperties.java
package com.vega.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "news")
public class NewsProperties {

    private String vegaRoot = "/root/news";
    private final Refresh refresh = new Refresh();
    private final Retention retention = new Retention();
    private final Upstox upstox = new Upstox();
    private final Storage storage = new Storage();

    public String getVegaRoot() { return vegaRoot; }
    public void setVegaRoot(String vegaRoot) { this.vegaRoot = vegaRoot; }
    public Refresh getRefresh() { return refresh; }
    public Retention getRetention() { return retention; }
    public Upstox getUpstox() { return upstox; }
    public Storage getStorage() { return storage; }

    public static class Refresh {
        private String interval = "15m";
        public String getInterval() { return interval; }
        public void setInterval(String interval) { this.interval = interval; }
    }

    public static class Retention {
        private int days = 3650;
        public int getDays() { return days; }
        public void setDays(int days) { this.days = days; }
    }

    public static class Upstox {
        private int pageSize = 100;
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    }

    public static class Storage {
        private String root;
        private String holdingsView;
        private String positionsView;

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public String getHoldingsView() { return holdingsView; }
        public void setHoldingsView(String holdingsView) { this.holdingsView = holdingsView; }
        public String getPositionsView() { return positionsView; }
        public void setPositionsView(String positionsView) { this.positionsView = positionsView; }
    }
}
```

```java
// File: src/main/java/com/vega/news/controller/NewsController.java
package com.vega.news.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.news.config.NewsProperties;
import com.vega.news.model.NewsArticle;
import com.vega.news.service.InstrumentNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final InstrumentNewsService instrumentNewsService;
    private final NewsProperties properties;

    @GetMapping("/instrument/{isin}")
    public ResponseEntity<List<NewsArticle>> getInstrumentNews(@PathVariable String isin) {
        List<NewsArticle> articles = instrumentNewsService.getNewsForIsins(java.util.Collections.singleton(isin)).get(isin);
        return ResponseEntity.ok(articles != null ? articles : java.util.Collections.emptyList());
    }

    @GetMapping(value = "/holdings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> getHoldingsNews() {
        File file = new File(properties.getStorage().getHoldingsView());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "/positions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> getPositionsNews() {
        File file = new File(properties.getStorage().getPositionsView());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
```

```java
// File: src/main/java/com/vega/news/model/NewsArticle.java
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
```

```java
// File: src/main/java/com/vega/news/model/NewsMetadata.java
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
```

```java
// File: src/main/java/com/vega/news/scheduler/NewsRefreshScheduler.java
package com.vega.news.scheduler;

import com.vega.news.service.PortfolioNewsBuilderService;
import com.vega.news.service.NewsRefreshCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsRefreshScheduler {

    private final PortfolioNewsBuilderService builderService;
    private final NewsRefreshCoordinator coordinator;

    // Refresh holdings every 15 minutes
    @Scheduled(fixedDelayString = "PT15M")
    public void refreshHoldingsNews() {
        if (!coordinator.tryLockHoldings()) {
            log.warn("Skipping Holdings News refresh: already running");
            return;
        }
        try {
            log.info("Running scheduled refresh for Holdings News");
            builderService.buildHoldingsView();
        } finally {
            coordinator.unlockHoldings();
        }
    }

    // Refresh positions every 15 minutes
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public void refreshPositionsNews() {
        if (!coordinator.tryLockPositions()) {
            log.warn("Skipping Positions News refresh: already running");
            return;
        }
        try {
            log.info("Running scheduled refresh for Positions News");
            builderService.buildPositionsView();
        } finally {
            coordinator.unlockPositions();
        }
    }
}
```

```java
// File: src/main/java/com/vega/news/scheduler/NewsStartupRunner.java
package com.vega.news.scheduler;

import com.vega.news.service.PortfolioNewsBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsStartupRunner implements CommandLineRunner {

    private final PortfolioNewsBuilderService builderService;
    private final ExecutorService virtualThreadExecutor;

    @Override
    public void run(String... args) {
        log.info("Vega News Application started. Initializing views in background...");
        virtualThreadExecutor.submit(() -> {
            try {
                builderService.buildHoldingsView();
                builderService.buildPositionsView();
                log.info("Initial background view generation complete.");
            } catch (Exception e) {
                log.error("Failed to perform initial news view build", e);
            }
        });
        log.info("Startup runner submitted background initialization tasks.");
    }
}
```

```java
// File: src/main/java/com/vega/news/service/ArchiveLockManager.java
package com.vega.news.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ArchiveLockManager {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock getLock(String isin) {
        return locks.computeIfAbsent(isin, k -> new ReentrantLock());
    }
}
```

```java
// File: src/main/java/com/vega/news/service/InstrumentNewsService.java
package com.vega.news.service;

import com.vega.news.client.UpstoxNewsClient;
import com.vega.news.model.NewsArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentNewsService {

    private final UpstoxNewsClient newsClient;
    private final NewsInstrumentArchiveService archiveService;
    private final InstrumentService instrumentService;

    public Map<String, List<NewsArticle>> getArchivedNews(Set<String> isins) {
        Map<String, List<NewsArticle>> result = new HashMap<>();
        for (String isin : isins) {
            List<NewsArticle> archivedArticles = archiveService.loadArchive(isin);
            archivedArticles.sort((a, b) -> Long.compare(b.getPublishedTime(), a.getPublishedTime()));
            result.put(isin, archivedArticles);
        }
        return result;
    }

    public void refreshNews(Set<String> isins) {
        if (isins.isEmpty()) return;
        
        Map<String, String> isinToInstrumentKey = new HashMap<>();

        // Group into batches of 30
        List<String> currentBatchKeys = new ArrayList<>();
        List<String> currentBatchIsins = new ArrayList<>();

        for (String isin : isins) {
            String instrumentKey = instrumentService.getInstrumentKeyByIsin(isin);
            if (instrumentKey != null) {
                isinToInstrumentKey.put(instrumentKey, isin);
                currentBatchKeys.add(instrumentKey);
                currentBatchIsins.add(isin);

                if (currentBatchKeys.size() == 30) {
                    processBatch(currentBatchIsins, currentBatchKeys, isinToInstrumentKey);
                    currentBatchKeys.clear();
                    currentBatchIsins.clear();
                }
            }
        }

        // Process remainder
        if (!currentBatchKeys.isEmpty()) {
            processBatch(currentBatchIsins, currentBatchKeys, isinToInstrumentKey);
        }
    }

    private void processBatch(List<String> isins, List<String> instrumentKeys, Map<String, String> isinToInstrumentKey) {
        String keysCsv = String.join(",", instrumentKeys);
        List<NewsArticle> fetchedArticles = newsClient.fetchNews("instrument_keys", null, keysCsv);

        // Group fetched by ISIN
        Map<String, List<NewsArticle>> groupedByIsin = new HashMap<>();
        for (NewsArticle article : fetchedArticles) {
            String isin = isinToInstrumentKey.get(article.getInstrumentKey());
            if (isin != null) {
                article.setIsin(isin);
                groupedByIsin.computeIfAbsent(isin, k -> new ArrayList<>()).add(article);
            }
        }

        // Append to individual archives
        for (Map.Entry<String, List<NewsArticle>> entry : groupedByIsin.entrySet()) {
            archiveService.appendNewArticles(entry.getKey(), entry.getValue());
        }
    }
}
```

```java
// File: src/main/java/com/vega/news/service/InstrumentService.java
package com.vega.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Service
@Slf4j
public class InstrumentService {

    private final String instrumentPath;
    private final ObjectMapper objectMapper;
    private final Map<String, InstrumentInfo> isinMap = new HashMap<>();
    private final Map<String, InstrumentInfo> instrumentKeyMap = new HashMap<>();
    private final Map<String, InstrumentInfo> fnoEquities = new HashMap<>();

    public InstrumentService(@Value("${upstox.instrument-path}") String instrumentPath, ObjectMapper objectMapper) {
        this.instrumentPath = instrumentPath;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        log.info("Loading instruments from {}", instrumentPath);
        File file = new File(instrumentPath);
        if (!file.exists()) {
            log.warn("Instrument file not found at {}", instrumentPath);
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(file);
            if (root.isArray()) {
                Set<String> fnoUnderlyingKeys = new HashSet<>();
                for (JsonNode node : root) {
                    String isin = node.path("isin").asText(null);
                    String instrumentKey = node.path("instrument_key").asText(null);
                    String segment = node.path("segment").asText("");
                    String underlyingType = node.path("underlying_type").asText("");
                    String underlyingKey = node.path("underlying_key").asText("");

                    if ("NSE_FO".equals(segment) && "EQUITY".equals(underlyingType) && !underlyingKey.isEmpty()) {
                        fnoUnderlyingKeys.add(underlyingKey);
                    }

                    InstrumentInfo info = new InstrumentInfo();
                    info.setIsin(isin);
                    info.setSymbol(node.path("trading_symbol").asText(node.path("asset_symbol").asText()));
                    info.setName(node.path("name").asText(null));
                    info.setExchange(node.path("exchange").asText());
                    info.setInstrumentKey(instrumentKey);
                    info.setSegment(segment);

                    if (isin != null && !isin.isEmpty()) {
                        isinMap.put(isin, info);
                    }
                    if (instrumentKey != null && !instrumentKey.isEmpty()) {
                        instrumentKeyMap.put(instrumentKey, info);
                    }
                }

                // Identify F&O linked equities
                for (String key : fnoUnderlyingKeys) {
                    InstrumentInfo eqInfo = instrumentKeyMap.get(key);
                    if (eqInfo != null && "NSE_EQ".equals(eqInfo.getSegment()) && eqInfo.getIsin() != null) {
                        fnoEquities.put(eqInfo.getIsin(), eqInfo);
                    }
                }
            }
            log.info("Loaded {} instruments (ISINs: {}, Keys: {}) from {}", 
                    isinMap.size(), isinMap.size(), instrumentKeyMap.size(), instrumentPath);
            log.info("Identified {} F&O linked equities.", fnoEquities.size());
        } catch (IOException e) {
            log.error("Failed to load instruments: {}", e.getMessage());
        }
    }

    public Map<String, InstrumentInfo> getFnoEquities() {
        return Collections.unmodifiableMap(fnoEquities);
    }

    public InstrumentInfo getInstrument(String isin) {
        return isinMap.get(isin);
    }

    public String getInstrumentKeyByIsin(String isin) {
        InstrumentInfo info = isinMap.get(isin);
        return info != null ? info.getInstrumentKey() : null;
    }

    public InstrumentInfo getByInstrumentKey(String instrumentKey) {
        return instrumentKeyMap.get(instrumentKey);
    }

    public String getCompetitorInstrumentKey(String isin) {
        InstrumentInfo info = isinMap.get(isin);
        if (info == null) {
            return null;
        }
        if (info.getInstrumentKey() != null && !info.getInstrumentKey().isBlank()) {
            return info.getInstrumentKey();
        }
        return info.getExchange() + "|" + isin;
    }

    @Data
    public static class InstrumentInfo {
        private String isin;
        private String symbol;
        private String name;
        private String exchange;
        private String instrumentKey;
        private String segment;
    }
}
```

```java
// File: src/main/java/com/vega/news/service/NewsInstrumentArchiveService.java
package com.vega.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.news.config.NewsProperties;
import com.vega.news.model.NewsArticle;
import com.vega.news.model.NewsMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsInstrumentArchiveService {

    private final ObjectMapper objectMapper;
    private final NewsProperties properties;
    private final ArchiveLockManager lockManager;

    public List<NewsArticle> loadArchive(String isin) {
        Path archivePath = getArchivePath(isin);
        List<NewsArticle> articles = new ArrayList<>();
        if (Files.exists(archivePath)) {
            try (BufferedReader reader = Files.newBufferedReader(archivePath)) {
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    try {
                        articles.add(objectMapper.readValue(line, NewsArticle.class));
                    } catch (IOException e) {
                        log.warn("Skipping malformed JSONL line {} in archive for ISIN: {}", lineNum, isin);
                    }
                }
            } catch (IOException e) {
                log.error("Failed to load archive for ISIN: {}", isin, e);
            }
        }
        return articles;
    }

    public void appendNewArticles(String isin, List<NewsArticle> newArticles) {
        if (newArticles == null || newArticles.isEmpty()) return;

        java.util.concurrent.locks.ReentrantLock lock = lockManager.getLock(isin);
        lock.lock();
        try {
            Set<String> existingHashes = getExistingHashes(isin);
            List<NewsArticle> articlesToAppend = new ArrayList<>();
            long latestPublishedTime = 0;

            for (NewsArticle article : newArticles) {
                if (!existingHashes.contains(article.getSourceHash())) {
                    articlesToAppend.add(article);
                    existingHashes.add(article.getSourceHash());
                    if (article.getPublishedTime() > latestPublishedTime) {
                        latestPublishedTime = article.getPublishedTime();
                    }
                }
            }

            if (articlesToAppend.isEmpty()) return;

            Path archivePath = getArchivePath(isin);
            try {
                Files.createDirectories(archivePath.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(archivePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (NewsArticle article : articlesToAppend) {
                        writer.write(objectMapper.writeValueAsString(article));
                        writer.newLine();
                    }
                }
                // Count actual lines in archive for metadata correctness
                int totalArticles = countLines(archivePath);
                updateMetadata(isin, totalArticles, latestPublishedTime);
                updateHashIndex(isin, existingHashes);
                log.info("Appended {} new articles for ISIN: {}", articlesToAppend.size(), isin);
            } catch (IOException e) {
                log.error("Failed to append articles for ISIN: {}", isin, e);
            }
        } finally {
            lock.unlock();
        }
    }

    public void performRetentionCleanup(String isin, long cutoffTime) {
        java.util.concurrent.locks.ReentrantLock lock = lockManager.getLock(isin);
        lock.lock();
        try {
            List<NewsArticle> articles = loadArchive(isin);
            List<NewsArticle> retainedArticles = new ArrayList<>();
            Set<String> retainedHashes = new HashSet<>();
            long latestPublishedTime = 0;
            boolean changed = false;

            for (NewsArticle article : articles) {
                if (article.getPublishedTime() >= cutoffTime) {
                    retainedArticles.add(article);
                    retainedHashes.add(article.getSourceHash());
                    if (article.getPublishedTime() > latestPublishedTime) {
                        latestPublishedTime = article.getPublishedTime();
                    }
                } else {
                    changed = true;
                }
            }

            if (changed) {
                Path archivePath = getArchivePath(isin);
                try (BufferedWriter writer = Files.newBufferedWriter(archivePath)) {
                    for (NewsArticle article : retainedArticles) {
                        writer.write(objectMapper.writeValueAsString(article));
                        writer.newLine();
                    }
                }
                updateHashIndex(isin, retainedHashes);
                updateMetadata(isin, retainedArticles.size(), latestPublishedTime);
                log.info("Retention cleanup: Removed {} articles for ISIN: {}", articles.size() - retainedArticles.size(), isin);
            }
        } catch (IOException e) {
            log.error("Failed to perform retention cleanup for ISIN: {}", isin, e);
        } finally {
            lock.unlock();
        }
    }

    public List<String> getAllArchivedIsins() {
        Path instrumentsDir = Paths.get(properties.getStorage().getRoot(), "instruments");
        if (!Files.exists(instrumentsDir)) return new ArrayList<>();
        try (Stream<Path> stream = Files.list(instrumentsDir)) {
            return stream
                    .filter(path -> path.toString().endsWith(".jsonl"))
                    .map(path -> path.getFileName().toString().replace(".jsonl", ""))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list archived ISINs", e);
            return new ArrayList<>();
        }
    }

    private int countLines(Path path) {
        if (!Files.exists(path)) return 0;
        try (Stream<String> lines = Files.lines(path)) {
            return (int) lines.count();
        } catch (IOException e) {
            log.error("Failed to count lines in file: {}", path, e);
            return 0;
        }
    }

    private Set<String> getExistingHashes(String isin) {
        Path hashPath = getHashIndexPath(isin);
        if (Files.exists(hashPath)) {
            try {
                JsonNode root = objectMapper.readTree(hashPath.toFile());
                JsonNode hashesNode = root.path("hashes");
                if (hashesNode.isArray()) {
                    Set<String> hashes = new HashSet<>();
                    for (JsonNode node : hashesNode) {
                        hashes.add(node.asText());
                    }
                    return hashes;
                }
            } catch (IOException e) {
                log.error("Failed to read hash index for ISIN: {}", isin, e);
            }
        }
        
        // Fallback: If hash index doesn't exist, build it from archive (one-time migration)
        Set<String> hashes = new HashSet<>();
        Path archivePath = getArchivePath(isin);
        if (Files.exists(archivePath)) {
            log.info("Hash index missing for ISIN: {}. Rebuilding from archive...", isin);
            try (BufferedReader reader = Files.newBufferedReader(archivePath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        if (node.has("sourceHash")) {
                            hashes.add(node.get("sourceHash").asText());
                        }
                    } catch (IOException ignored) {}
                }
                updateHashIndex(isin, hashes);
            } catch (IOException e) {
                log.error("Failed to rebuild hash index for ISIN: {}", isin, e);
            }
        }
        return hashes;
    }

    private void updateHashIndex(String isin, Set<String> hashes) {
        Path hashPath = getHashIndexPath(isin);
        try {
            Files.createDirectories(hashPath.getParent());
            Map<String, Object> content = new HashMap<>();
            content.put("isin", isin);
            content.put("hashes", hashes);
            Files.writeString(hashPath, objectMapper.writeValueAsString(content));
        } catch (IOException e) {
            log.error("Failed to update hash index for ISIN: {}", isin, e);
        }
    }

    private void updateMetadata(String isin, int totalArticles, long latestArticle) {
        Path metadataPath = getMetadataPath(isin);
        try {
            Files.createDirectories(metadataPath.getParent());
            NewsMetadata metadata = NewsMetadata.builder()
                    .isin(isin)
                    .totalArticles(totalArticles)
                    .lastUpdated(System.currentTimeMillis())
                    .latestArticle(latestArticle)
                    .build();
            Files.writeString(metadataPath, objectMapper.writeValueAsString(metadata));
        } catch (IOException e) {
            log.error("Failed to update metadata for ISIN: {}", isin, e);
        }
    }

    private Path getArchivePath(String isin) {
        return Paths.get(properties.getStorage().getRoot(), "instruments", isin + ".jsonl");
    }

    private Path getMetadataPath(String isin) {
        return Paths.get(properties.getStorage().getRoot(), "metadata", "instruments", isin + ".json");
    }

    private Path getHashIndexPath(String isin) {
        return Paths.get(properties.getStorage().getRoot(), "metadata", "hashes", isin + ".json");
    }
}
```

```java
// File: src/main/java/com/vega/news/service/NewsMergeService.java
package com.vega.news.service;

import com.vega.news.model.NewsArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class NewsMergeService {

    public List<NewsArticle> mergeArchives(List<List<NewsArticle>> archives) {
        List<NewsArticle> merged = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();

        for (List<NewsArticle> archive : archives) {
            for (NewsArticle article : archive) {
                if (seenHashes.add(article.getSourceHash())) {
                    merged.add(article);
                }
            }
        }

        merged.sort((a, b) -> Long.compare(b.getPublishedTime(), a.getPublishedTime()));
        return merged;
    }
}
```

```java
// File: src/main/java/com/vega/news/service/NewsRefreshCoordinator.java
package com.vega.news.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NewsRefreshCoordinator {
    private final AtomicBoolean holdingsRunning = new AtomicBoolean(false);
    private final AtomicBoolean positionsRunning = new AtomicBoolean(false);

    public boolean tryLockHoldings() {
        return holdingsRunning.compareAndSet(false, true);
    }

    public void unlockHoldings() {
        holdingsRunning.set(false);
    }

    public boolean tryLockPositions() {
        return positionsRunning.compareAndSet(false, true);
    }

    public void unlockPositions() {
        positionsRunning.set(false);
    }
}
```

```java
// File: src/main/java/com/vega/news/service/NewsRetentionService.java
package com.vega.news.service;

import com.vega.news.config.NewsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsRetentionService {

    private final NewsInstrumentArchiveService archiveService;
    private final NewsProperties properties;

    // Run at 1 AM every day
    @Scheduled(cron = "0 0 1 * * ?")
    public void runRetentionCleanup() {
        log.info("Starting scheduled news retention cleanup");
        int retentionDays = properties.getRetention().getDays();
        long cutoffTime = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);

        List<String> isins = archiveService.getAllArchivedIsins();
        log.info("Checking retention for {} archived ISINs. Cutoff time: {}", isins.size(), cutoffTime);

        for (String isin : isins) {
            try {
                archiveService.performRetentionCleanup(isin, cutoffTime);
            } catch (Exception e) {
                log.error("Failed to perform retention cleanup for ISIN: {}", isin, e);
            }
        }
        log.info("Finished news retention cleanup");
    }
}
```

```java
// File: src/main/java/com/vega/news/service/PortfolioNewsBuilderService.java
package com.vega.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.news.config.NewsProperties;
import com.vega.news.model.NewsArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioNewsBuilderService {

    private final PortfolioReaderService readerService;
    private final InstrumentNewsService instrumentNewsService;
    private final NewsMergeService mergeService;
    private final ObjectMapper objectMapper;
    private final NewsProperties properties;
    private final PortfolioSnapshotService snapshotService;

    public void buildHoldingsView() {
        log.info("Building Holdings News View...");
        Set<String> currentIsins = readerService.readHoldingsIsins();
        Set<String> previousIsins = snapshotService.loadSnapshot("holdings");

        // Detect changes
        java.util.Set<String> addedIsins = new java.util.HashSet<>(currentIsins);
        addedIsins.removeAll(previousIsins);

        if (!addedIsins.isEmpty()) {
            log.info("Detected {} new ISINs in holdings. Refreshing news for them.", addedIsins.size());
            instrumentNewsService.refreshNews(addedIsins);
        } else {
            log.info("No new ISINs detected in holdings. Skipping API fetch.");
        }

        buildView(currentIsins, Paths.get(properties.getStorage().getHoldingsView()));
        snapshotService.saveSnapshot("holdings", currentIsins);
    }

    public void buildPositionsView() {
        log.info("Building Positions News View...");
        Set<String> currentIsins = readerService.readPositionsIsins();
        Set<String> previousIsins = snapshotService.loadSnapshot("positions");

        // Detect changes
        java.util.Set<String> addedIsins = new java.util.HashSet<>(currentIsins);
        addedIsins.removeAll(previousIsins);

        if (!addedIsins.isEmpty()) {
            log.info("Detected {} new ISINs in positions. Refreshing news for them.", addedIsins.size());
            instrumentNewsService.refreshNews(addedIsins);
        } else {
            log.info("No new ISINs detected in positions. Skipping API fetch.");
        }

        buildView(currentIsins, Paths.get(properties.getStorage().getPositionsView()));
        snapshotService.saveSnapshot("positions", currentIsins);
    }

    private void buildView(Set<String> isins, Path outputPath) {
        if (isins.isEmpty()) {
            log.info("No ISINs found for view: {}", outputPath);
            return;
        }

        List<List<NewsArticle>> allArchives = new ArrayList<>(instrumentNewsService.getArchivedNews(isins).values());

        List<NewsArticle> mergedNews = mergeService.mergeArchives(allArchives);

        try {
            Files.createDirectories(outputPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (NewsArticle article : mergedNews) {
                    writer.write(objectMapper.writeValueAsString(article));
                    writer.newLine();
                }
            }
            log.info("Successfully built view at {} with {} articles", outputPath, mergedNews.size());
        } catch (IOException e) {
            log.error("Failed to write news view to {}", outputPath, e);
        }
    }
}
```

```java
// File: src/main/java/com/vega/news/service/PortfolioReaderService.java
package com.vega.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioReaderService {

    private final ObjectMapper objectMapper;

    public Set<String> readHoldingsIsins() {
        Set<String> isins = new HashSet<>();
        Path holdingsPath = Paths.get("/root/news/storage/user/holdings/holdings.jsonl");
        if (!Files.exists(holdingsPath)) {
            return isins;
        }

        try {
            String content = Files.readString(holdingsPath);
            JsonNode node = objectMapper.readTree(content);
            JsonNode dataArray = node.path("data");
            if (dataArray.isArray()) {
                for (JsonNode holding : dataArray) {
                    if (holding.has("isin")) {
                        isins.add(holding.path("isin").asText());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to read/parse holdings", e);
        }
        return isins;
    }

    public Set<String> readPositionsIsins() {
        Set<String> isins = new HashSet<>();
        Path positionsPath = Paths.get("/root/news/storage/user/positions/positions.jsonl");
        if (!Files.exists(positionsPath)) {
            return isins;
        }

        try {
            String content = Files.readString(positionsPath);
            JsonNode node = objectMapper.readTree(content);
            JsonNode dataArray = node.path("data");
            if (dataArray.isArray()) {
                for (JsonNode position : dataArray) {
                    int quantity = position.path("quantity").asInt(0);
                    String instrumentToken = position.path("instrument_token").asText("");
                    
                    if (quantity > 0 && instrumentToken.contains("_EQ|")) {
                        String[] parts = instrumentToken.split("\\|");
                        if (parts.length == 2) {
                            isins.add(parts[1]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to read/parse positions", e);
        }
        return isins;
    }
}
```

```java
// File: src/main/java/com/vega/news/service/PortfolioSnapshotService.java
package com.vega.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.news.config.NewsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioSnapshotService {

    private final ObjectMapper objectMapper;
    private final NewsProperties properties;

    public Set<String> loadSnapshot(String name) {
        Path snapshotPath = getSnapshotPath(name);
        if (Files.exists(snapshotPath)) {
            try {
                return objectMapper.readValue(snapshotPath.toFile(), objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class));
            } catch (IOException e) {
                log.error("Failed to load portfolio snapshot: {}", name, e);
            }
        }
        return new HashSet<>();
    }

    public void saveSnapshot(String name, Set<String> isins) {
        Path snapshotPath = getSnapshotPath(name);
        try {
            Files.createDirectories(snapshotPath.getParent());
            objectMapper.writeValue(snapshotPath.toFile(), isins);
        } catch (IOException e) {
            log.error("Failed to save portfolio snapshot: {}", name, e);
        }
    }

    private Path getSnapshotPath(String name) {
        return Paths.get(properties.getStorage().getRoot(), "state", name + "_snapshot.json");
    }
}
```

```java
// File: src/main/java/com/vega/news/util/GlobalExceptionHandler.java
package com.vega.news.util;

import jakarta.validation.ConstraintViolationException;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(ConstraintViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.builder()
                        .status("error")
                        .message(e.getMessage())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllExceptions(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.builder()
                        .status("error")
                        .message("An unexpected error occurred: " + e.getMessage())
                        .build());
    }

    @Data
    @Builder
    public static class ErrorResponse {
        private String status;
        private String message;
    }
}
```

```yaml
// File: src/main/resources/application.yml
spring:
  application:
    name: vega.news

logging:
  level:
    com.vega.news: INFO

upstox:
  instrument-path: ${news.vega-root}/data/instruments/upstox/upstox.json
  auth:
    file: ${news.vega-root}/auth/upstox/auth.upstox.json

news:
  vega-root: /root/news
  refresh:
    interval: 15m
  retention:
    days: 3650
  upstox:
    page-size: 100
  storage:
    root: ${news.vega-root}/storage/news
    holdings-view: ${news.vega-root}/storage/news/holdings.jsonl
    positions-view: ${news.vega-root}/storage/news/positions.jsonl
```
