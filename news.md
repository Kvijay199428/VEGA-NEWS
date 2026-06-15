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

    @Bean(destroyMethod = "close")
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
        List<NewsArticle> articles = instrumentNewsService.getArchivedNews(java.util.Collections.singleton(isin)).get(isin);
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
        if (!coordinator.tryLockRefresh()) {
            log.warn("Skipping Holdings News refresh: already running");
            return;
        }
        try {
            log.info("Running scheduled refresh for Holdings News");
            builderService.buildHoldingsView();
        } finally {
            coordinator.unlockRefresh();
        }
    }

    // Refresh positions every 15 minutes
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public void refreshPositionsNews() {
        if (!coordinator.tryLockRefresh()) {
            log.warn("Skipping Positions News refresh: already running");
            return;
        }
        try {
            log.info("Running scheduled refresh for Positions News");
            builderService.buildPositionsView();
        } finally {
            coordinator.unlockRefresh();
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
            try (Stream<String> lines = Files.lines(hashPath)) {
                return lines.collect(Collectors.toSet());
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
            Files.write(hashPath, hashes);
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
        return Paths.get(properties.getStorage().getRoot(), "metadata", "hash-index", isin + ".txt");
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
    private final AtomicBoolean globalRefreshRunning = new AtomicBoolean(false);

    public boolean tryLockRefresh() {
        return globalRefreshRunning.compareAndSet(false, true);
    }

    public void unlockRefresh() {
        globalRefreshRunning.set(false);
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
        long cutoffTime = System.currentTimeMillis() - java.time.Duration.ofDays(retentionDays).toMillis();

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
            log.info("Detected {} new ISINs in holdings.", addedIsins.size());
        }

        log.info("Refreshing news for all {} holdings ISINs.", currentIsins.size());
        instrumentNewsService.refreshNews(currentIsins);

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
            log.info("Detected {} new ISINs in positions.", addedIsins.size());
        }

        log.info("Refreshing news for all {} positions ISINs.", currentIsins.size());
        instrumentNewsService.refreshNews(currentIsins);

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

```json
// File: collector/news/INE002A01018/metadata.json
{
  "isin": "INE002A01018",
  "symbol": "RELIANCE",
  "name": "RELIANCE INDUSTRIES LTD",
  "last_fetch": "2026-06-15T07:57:08.560528Z"
}
```

```json
// File: collector/news/INE003A01024/metadata.json
{
  "isin": "INE003A01024",
  "symbol": "SIEMENS",
  "name": "SIEMENS LTD",
  "last_fetch": "2026-06-15T07:57:12.550569Z"
}
```

```json
// File: collector/news/INE006I01046/metadata.json
{
  "isin": "INE006I01046",
  "symbol": "ASTRAL",
  "name": "ASTRAL LIMITED",
  "last_fetch": "2026-06-15T07:57:06.155384Z"
}
```

```json
// File: collector/news/INE009A01021/metadata.json
{
  "isin": "INE009A01021",
  "symbol": "INFY",
  "name": "INFOSYS LIMITED",
  "last_fetch": "2026-06-15T07:57:04.991163Z"
}
```

```json
// File: collector/news/INE00H001014/metadata.json
{
  "isin": "INE00H001014",
  "symbol": "SWIGGY",
  "name": "SWIGGY LIMITED",
  "last_fetch": "2026-06-15T07:57:10.762194Z"
}
```

```json
// File: collector/news/INE00R701025/metadata.json
{
  "isin": "INE00R701025",
  "symbol": "DALBHARAT",
  "name": "DALMIA BHARAT LIMITED",
  "last_fetch": "2026-06-15T07:57:04.455586Z"
}
```

```json
// File: collector/news/INE010B01027/metadata.json
{
  "isin": "INE010B01027",
  "symbol": "ZYDUSLIFE",
  "name": "ZYDUS LIFESCIENCES LTD",
  "last_fetch": "2026-06-15T07:57:09.602738Z"
}
```

```json
// File: collector/news/INE016A01026/metadata.json
{
  "isin": "INE016A01026",
  "symbol": "DABUR",
  "name": "DABUR INDIA LTD",
  "last_fetch": "2026-06-15T07:57:12.704483Z"
}
```

```json
// File: collector/news/INE018A01030/metadata.json
{
  "isin": "INE018A01030",
  "symbol": "LT",
  "name": "LARSEN & TOUBRO LTD.",
  "last_fetch": "2026-06-15T07:57:12.048631Z"
}
```

```json
// File: collector/news/INE018E01016/metadata.json
{
  "isin": "INE018E01016",
  "symbol": "SBICARD",
  "name": "SBI CARDS & PAY SER LTD",
  "last_fetch": "2026-06-15T07:57:11.093639Z"
}
```

```json
// File: collector/news/INE019A01038/metadata.json
{
  "isin": "INE019A01038",
  "symbol": "JSWSTEEL",
  "name": "JSW STEEL LIMITED",
  "last_fetch": "2026-06-15T07:57:08.372631Z"
}
```

```json
// File: collector/news/INE01EA01019/metadata.json
{
  "isin": "INE01EA01019",
  "symbol": "VMM",
  "name": "VISHAL MEGA MART LIMITED",
  "last_fetch": "2026-06-15T07:57:05.318102Z"
}
```

```json
// File: collector/news/INE020B01018/metadata.json
{
  "isin": "INE020B01018",
  "symbol": "RECLTD",
  "name": "REC LIMITED",
  "last_fetch": "2026-06-15T07:57:10.590930Z"
}
```

```json
// File: collector/news/INE021A01026/metadata.json
{
  "isin": "INE021A01026",
  "symbol": "ASIANPAINT",
  "name": "ASIAN PAINTS LIMITED",
  "last_fetch": "2026-06-15T07:57:07.559305Z"
}
```

```json
// File: collector/news/INE022Q01020/metadata.json
{
  "isin": "INE022Q01020",
  "symbol": "IEX",
  "name": "INDIAN ENERGY EXC LTD",
  "last_fetch": "2026-06-15T07:57:07.676323Z"
}
```

```json
// File: collector/news/INE027H01010/metadata.json
{
  "isin": "INE027H01010",
  "symbol": "MAXHEALTH",
  "name": "MAX HEALTHCARE INS LTD",
  "last_fetch": "2026-06-15T07:57:06.775799Z"
}
```

```json
// File: collector/news/INE028A01039/metadata.json
{
  "isin": "INE028A01039",
  "symbol": "BANKBARODA",
  "name": "BANK OF BARODA",
  "last_fetch": "2026-06-15T07:57:05.501376Z"
}
```

```json
// File: collector/news/INE029A01011/metadata.json
{
  "isin": "INE029A01011",
  "symbol": "BPCL",
  "name": "BHARAT PETROLEUM CORP  LT",
  "last_fetch": "2026-06-15T07:57:12.543305Z"
}
```

```json
// File: collector/news/INE030A01027/metadata.json
{
  "isin": "INE030A01027",
  "symbol": "HINDUNILVR",
  "name": "HINDUSTAN UNILEVER LTD.",
  "last_fetch": "2026-06-15T07:57:05.327388Z"
}
```

```json
// File: collector/news/INE038A01020/metadata.json
{
  "isin": "INE038A01020",
  "symbol": "HINDALCO",
  "name": "HINDALCO  INDUSTRIES  LTD",
  "last_fetch": "2026-06-15T07:57:10.495094Z"
}
```

```json
// File: collector/news/INE040A01034/metadata.json
{
  "isin": "INE040A01034",
  "symbol": "HDFCBANK",
  "name": "HDFC BANK LTD",
  "last_fetch": "2026-06-15T07:57:08.811191Z"
}
```

```json
// File: collector/news/INE040H01021/metadata.json
{
  "isin": "INE040H01021",
  "symbol": "SUZLON",
  "name": "SUZLON ENERGY LIMITED",
  "last_fetch": "2026-06-15T07:57:11.998156Z"
}
```

```json
// File: collector/news/INE044A01036/metadata.json
{
  "isin": "INE044A01036",
  "symbol": "SUNPHARMA",
  "name": "SUN PHARMACEUTICAL IND L",
  "last_fetch": "2026-06-15T07:57:10.357955Z"
}
```

```json
// File: collector/news/INE047A01021/metadata.json
{
  "isin": "INE047A01021",
  "symbol": "GRASIM",
  "name": "GRASIM INDUSTRIES LTD",
  "last_fetch": "2026-06-15T07:57:06.999089Z"
}
```

```json
// File: collector/news/INE04I401011/metadata.json
{
  "isin": "INE04I401011",
  "symbol": "KPITTECH",
  "name": "KPIT TECHNOLOGIES LIMITED",
  "last_fetch": "2026-06-15T07:57:10.516766Z"
}
```

```json
// File: collector/news/INE053A01029/metadata.json
{
  "isin": "INE053A01029",
  "symbol": "INDHOTEL",
  "name": "THE INDIAN HOTELS CO. LTD",
  "last_fetch": "2026-06-15T07:57:08.076531Z"
}
```

```json
// File: collector/news/INE053F01010/metadata.json
{
  "isin": "INE053F01010",
  "symbol": "IRFC",
  "name": "INDIAN RAILWAY FIN CORP L",
  "last_fetch": "2026-06-15T07:57:07.466543Z"
}
```

```json
// File: collector/news/INE059A01026/metadata.json
{
  "isin": "INE059A01026",
  "symbol": "CIPLA",
  "name": "CIPLA LTD",
  "last_fetch": "2026-06-15T07:57:09.652422Z"
}
```

```json
// File: collector/news/INE061F01013/metadata.json
{
  "isin": "INE061F01013",
  "symbol": "FORTIS",
  "name": "FORTIS HEALTHCARE LTD",
  "last_fetch": "2026-06-15T07:57:10.528880Z"
}
```

```json
// File: collector/news/INE062A01020/metadata.json
{
  "isin": "INE062A01020",
  "symbol": "SBIN",
  "name": "STATE BANK OF INDIA",
  "last_fetch": "2026-06-15T07:57:06.432834Z"
}
```

```json
// File: collector/news/INE066A01021/metadata.json
{
  "isin": "INE066A01021",
  "symbol": "EICHERMOT",
  "name": "EICHER MOTORS LTD",
  "last_fetch": "2026-06-15T07:57:09.069186Z"
}
```

```json
// File: collector/news/INE066F01020/metadata.json
{
  "isin": "INE066F01020",
  "symbol": "HAL",
  "name": "HINDUSTAN AERONAUTICS LTD",
  "last_fetch": "2026-06-15T07:57:08.612771Z"
}
```

```json
// File: collector/news/INE066P01011/metadata.json
{
  "isin": "INE066P01011",
  "symbol": "INOXWIND",
  "name": "INOX WIND LIMITED",
  "last_fetch": "2026-06-15T07:57:07.348861Z"
}
```

```json
// File: collector/news/INE067A01029/metadata.json
{
  "isin": "INE067A01029",
  "symbol": "CGPOWER",
  "name": "CG POWER AND IND SOL LTD",
  "last_fetch": "2026-06-15T07:57:12.266581Z"
}
```

```json
// File: collector/news/INE070A01015/metadata.json
{
  "isin": "INE070A01015",
  "symbol": "SHREECEM",
  "name": "SHREE CEMENT LIMITED",
  "last_fetch": "2026-06-15T07:57:04.590480Z"
}
```

```json
// File: collector/news/INE073K01018/metadata.json
{
  "isin": "INE073K01018",
  "symbol": "SONACOMS",
  "name": "SONA BLW PRECISION FRGS L",
  "last_fetch": "2026-06-15T07:57:06.169841Z"
}
```

```json
// File: collector/news/INE075A01022/metadata.json
{
  "isin": "INE075A01022",
  "symbol": "WIPRO",
  "name": "WIPRO LTD",
  "last_fetch": "2026-06-15T07:57:06.532867Z"
}
```

```json
// File: collector/news/INE079A01024/metadata.json
{
  "isin": "INE079A01024",
  "symbol": "AMBUJACEM",
  "name": "AMBUJA CEMENTS LTD",
  "last_fetch": "2026-06-15T07:57:08.587217Z"
}
```

```json
// File: collector/news/INE07Y701011/metadata.json
{
  "isin": "INE07Y701011",
  "symbol": "POWERINDIA",
  "name": "HITACHI ENERGY INDIA LTD",
  "last_fetch": "2026-06-15T07:57:12.390004Z"
}
```

```json
// File: collector/news/INE081A01020/metadata.json
{
  "isin": "INE081A01020",
  "symbol": "TATASTEEL",
  "name": "TATA STEEL LIMITED",
  "last_fetch": "2026-06-15T07:57:06.384370Z"
}
```

```json
// File: collector/news/INE084A01016/metadata.json
{
  "isin": "INE084A01016",
  "symbol": "BANKINDIA",
  "name": "BANK OF INDIA",
  "last_fetch": "2026-06-15T07:57:10.911145Z"
}
```

```json
// File: collector/news/INE089A01031/metadata.json
{
  "isin": "INE089A01031",
  "symbol": "DRREDDY",
  "name": "DR. REDDY S LABORATORIES",
  "last_fetch": "2026-06-15T07:57:09.254697Z"
}
```

```json
// File: collector/news/INE090A01021/metadata.json
{
  "isin": "INE090A01021",
  "symbol": "ICICIBANK",
  "name": "ICICI BANK LTD.",
  "last_fetch": "2026-06-15T07:57:11.952949Z"
}
```

```json
// File: collector/news/INE092T01019/metadata.json
{
  "isin": "INE092T01019",
  "symbol": "IDFCFIRSTB",
  "name": "IDFC FIRST BANK LIMITED",
  "last_fetch": "2026-06-15T07:57:12.178025Z"
}
```

```json
// File: collector/news/INE093I01010/metadata.json
{
  "isin": "INE093I01010",
  "symbol": "OBEROIRLTY",
  "name": "OBEROI REALTY LIMITED",
  "last_fetch": "2026-06-15T07:57:05.943704Z"
}
```

```json
// File: collector/news/INE094A01015/metadata.json
{
  "isin": "INE094A01015",
  "symbol": "HINDPETRO",
  "name": "HINDUSTAN PETROLEUM CORP",
  "last_fetch": "2026-06-15T07:57:05.891802Z"
}
```

```json
// File: collector/news/INE095A01012/metadata.json
{
  "isin": "INE095A01012",
  "symbol": "INDUSINDBK",
  "name": "INDUSIND BANK LIMITED",
  "last_fetch": "2026-06-15T07:57:10.039159Z"
}
```

```json
// File: collector/news/INE095N01031/metadata.json
{
  "isin": "INE095N01031",
  "symbol": "NBCC",
  "name": "NBCC (INDIA) LIMITED",
  "last_fetch": "2026-06-15T07:57:08.393063Z"
}
```

```json
// File: collector/news/INE0BS701011/metadata.json
{
  "isin": "INE0BS701011",
  "symbol": "PREMIERENE",
  "name": "PREMIER ENERGIES LIMITED",
  "last_fetch": "2026-06-15T07:57:10.680075Z"
}
```

```json
// File: collector/news/INE0J1Y01017/metadata.json
{
  "isin": "INE0J1Y01017",
  "symbol": "LICI",
  "name": "LIFE INSURA CORP OF INDIA",
  "last_fetch": "2026-06-15T07:57:08.748907Z"
}
```

```json
// File: collector/news/INE0V6F01027/metadata.json
{
  "isin": "INE0V6F01027",
  "symbol": "HYUNDAI",
  "name": "HYUNDAI MOTOR INDIA LTD",
  "last_fetch": "2026-06-15T07:57:09.496960Z"
}
```

```json
// File: collector/news/INE101A01026/metadata.json
{
  "isin": "INE101A01026",
  "symbol": "M&M",
  "name": "MAHINDRA & MAHINDRA LTD",
  "last_fetch": "2026-06-15T07:57:11.750691Z"
}
```

```json
// File: collector/news/INE102D01028/metadata.json
{
  "isin": "INE102D01028",
  "symbol": "GODREJCP",
  "name": "GODREJ CONSUMER PRODUCTS",
  "last_fetch": "2026-06-15T07:57:04.984529Z"
}
```

```json
// File: collector/news/INE111A01025/metadata.json
{
  "isin": "INE111A01025",
  "symbol": "CONCOR",
  "name": "CONTAINER CORP OF IND LTD",
  "last_fetch": "2026-06-15T07:57:06.318654Z"
}
```

```json
// File: collector/news/INE114A01011/metadata.json
{
  "isin": "INE114A01011",
  "symbol": "SAIL",
  "name": "STEEL AUTHORITY OF INDIA",
  "last_fetch": "2026-06-15T07:57:12.489354Z"
}
```

```json
// File: collector/news/INE115A01026/metadata.json
{
  "isin": "INE115A01026",
  "symbol": "LICHSGFIN",
  "name": "LIC HOUSING FINANCE LTD",
  "last_fetch": "2026-06-15T07:57:07.766240Z"
}
```

```json
// File: collector/news/INE117A01022/metadata.json
{
  "isin": "INE117A01022",
  "symbol": "ABB",
  "name": "ABB INDIA LIMITED",
  "last_fetch": "2026-06-15T07:57:07.958863Z"
}
```

```json
// File: collector/news/INE118A01012/metadata.json
{
  "isin": "INE118A01012",
  "symbol": "BAJAJHLDNG",
  "name": "BAJAJ HOLDINGS & INVS LTD",
  "last_fetch": "2026-06-15T07:57:05.709423Z"
}
```

```json
// File: collector/news/INE118H01025/metadata.json
{
  "isin": "INE118H01025",
  "symbol": "BSE",
  "name": "BSE LIMITED",
  "last_fetch": "2026-06-15T07:57:10.163580Z"
}
```

```json
// File: collector/news/INE121A01024/metadata.json
{
  "isin": "INE121A01024",
  "symbol": "CHOLAFIN",
  "name": "CHOLAMANDALAM IN & FIN CO",
  "last_fetch": "2026-06-15T07:57:06.786967Z"
}
```

```json
// File: collector/news/INE121E01018/metadata.json
{
  "isin": "INE121E01018",
  "symbol": "JSWENERGY",
  "name": "JSW ENERGY LIMITED",
  "last_fetch": "2026-06-15T07:57:08.354033Z"
}
```

```json
// File: collector/news/INE121J01017/metadata.json
{
  "isin": "INE121J01017",
  "symbol": "INDUSTOWER",
  "name": "INDUS TOWERS LIMITED",
  "last_fetch": "2026-06-15T07:57:08.163608Z"
}
```

```json
// File: collector/news/INE123W01016/metadata.json
{
  "isin": "INE123W01016",
  "symbol": "SBILIFE",
  "name": "SBI LIFE INSURANCE CO LTD",
  "last_fetch": "2026-06-15T07:57:05.680686Z"
}
```

```json
// File: collector/news/INE127D01025/metadata.json
{
  "isin": "INE127D01025",
  "symbol": "HDFCAMC",
  "name": "HDFC AMC LIMITED",
  "last_fetch": "2026-06-15T07:57:07.549663Z"
}
```

```json
// File: collector/news/INE129A01019/metadata.json
{
  "isin": "INE129A01019",
  "symbol": "GAIL",
  "name": "GAIL (INDIA) LTD",
  "last_fetch": "2026-06-15T07:57:12.469828Z"
}
```

```json
// File: collector/news/INE134E01011/metadata.json
{
  "isin": "INE134E01011",
  "symbol": "PFC",
  "name": "POWER FIN CORP LTD.",
  "last_fetch": "2026-06-15T07:57:04.985525Z"
}
```

```json
// File: collector/news/INE138Y01010/metadata.json
{
  "isin": "INE138Y01010",
  "symbol": "KFINTECH",
  "name": "KFIN TECHNOLOGIES LIMITED",
  "last_fetch": "2026-06-15T07:57:09.702316Z"
}
```

```json
// File: collector/news/INE139A01034/metadata.json
{
  "isin": "INE139A01034",
  "symbol": "NATIONALUM",
  "name": "NATIONAL ALUMINIUM CO LTD",
  "last_fetch": "2026-06-15T07:57:04.455024Z"
}
```

```json
// File: collector/news/INE148I01020/metadata.json
{
  "isin": "INE148I01020",
  "symbol": "SAMMAANCAP",
  "name": "SAMMAAN CAPITAL LIMITED",
  "last_fetch": "2026-06-15T07:57:09.792174Z"
}
```

```json
// File: collector/news/INE148O01028/metadata.json
{
  "isin": "INE148O01028",
  "symbol": "DELHIVERY",
  "name": "DELHIVERY LIMITED",
  "last_fetch": "2026-06-15T07:57:11.741870Z"
}
```

```json
// File: collector/news/INE154A01025/metadata.json
{
  "isin": "INE154A01025",
  "symbol": "ITC",
  "name": "ITC LTD",
  "last_fetch": "2026-06-15T07:57:11.541841Z"
}
```

```json
// File: collector/news/INE155A01022/metadata.json
{
  "isin": "INE155A01022",
  "symbol": "TMPV",
  "name": "TATA MOTORS PASS VEH LTD",
  "last_fetch": "2026-06-15T07:57:09.831896Z"
}
```

```json
// File: collector/news/INE158A01026/metadata.json
{
  "isin": "INE158A01026",
  "symbol": "HEROMOTOCO",
  "name": "HERO MOTOCORP LIMITED",
  "last_fetch": "2026-06-15T07:57:05.628957Z"
}
```

```json
// File: collector/news/INE160A01022/metadata.json
{
  "isin": "INE160A01022",
  "symbol": "PNB",
  "name": "PUNJAB NATIONAL BANK",
  "last_fetch": "2026-06-15T07:57:04.869936Z"
}
```

```json
// File: collector/news/INE171A01029/metadata.json
{
  "isin": "INE171A01029",
  "symbol": "FEDERALBNK",
  "name": "FEDERAL BANK LTD",
  "last_fetch": "2026-06-15T07:57:10.753200Z"
}
```

```json
// File: collector/news/INE171Z01026/metadata.json
{
  "isin": "INE171Z01026",
  "symbol": "BDL",
  "name": "BHARAT DYNAMICS LIMITED",
  "last_fetch": "2026-06-15T07:57:04.605223Z"
}
```

```json
// File: collector/news/INE176B01034/metadata.json
{
  "isin": "INE176B01034",
  "symbol": "HAVELLS",
  "name": "HAVELLS INDIA LIMITED",
  "last_fetch": "2026-06-15T07:57:11.335887Z"
}
```

```json
// File: collector/news/INE180A01020/metadata.json
{
  "isin": "INE180A01020",
  "symbol": "MFSL",
  "name": "MAX FINANCIAL SERV LTD",
  "last_fetch": "2026-06-15T07:57:09.704987Z"
}
```

```json
// File: collector/news/INE192A01025/metadata.json
{
  "isin": "INE192A01025",
  "symbol": "TATACONSUM",
  "name": "TATA CONSUMER PRODUCT LTD",
  "last_fetch": "2026-06-15T07:57:05.686066Z"
}
```

```json
// File: collector/news/INE192R01011/metadata.json
{
  "isin": "INE192R01011",
  "symbol": "DMART",
  "name": "AVENUE SUPERMARTS LIMITED",
  "last_fetch": "2026-06-15T07:57:09.624872Z"
}
```

```json
// File: collector/news/INE195A01028/metadata.json
{
  "isin": "INE195A01028",
  "symbol": "SUPREMEIND",
  "name": "SUPREME INDUSTRIES LTD",
  "last_fetch": "2026-06-15T07:57:10.153242Z"
}
```

```json
// File: collector/news/INE196A01026/metadata.json
{
  "isin": "INE196A01026",
  "symbol": "MARICO",
  "name": "MARICO LIMITED",
  "last_fetch": "2026-06-15T07:57:08.087807Z"
}
```

```json
// File: collector/news/INE200A01026/metadata.json
{
  "isin": "INE200A01026",
  "symbol": "GVT&D",
  "name": "GE VERNOVA T&D INDIA LTD",
  "last_fetch": "2026-06-15T07:57:08.867798Z"
}
```

```json
// File: collector/news/INE200M01039/metadata.json
{
  "isin": "INE200M01039",
  "symbol": "VBL",
  "name": "VARUN BEVERAGES LIMITED",
  "last_fetch": "2026-06-15T07:57:12.675301Z"
}
```

```json
// File: collector/news/INE202E01016/metadata.json
{
  "isin": "INE202E01016",
  "symbol": "IREDA",
  "name": "INDIAN RENEWABLE ENERGY",
  "last_fetch": "2026-06-15T07:57:09.022793Z"
}
```

```json
// File: collector/news/INE205A01025/metadata.json
{
  "isin": "INE205A01025",
  "symbol": "VEDL",
  "name": "VEDANTA LIMITED",
  "last_fetch": "2026-06-15T07:57:06.779649Z"
}
```

```json
// File: collector/news/INE208A01029/metadata.json
{
  "isin": "INE208A01029",
  "symbol": "ASHOKLEY",
  "name": "ASHOK LEYLAND LTD",
  "last_fetch": "2026-06-15T07:57:11.759171Z"
}
```

```json
// File: collector/news/INE211B01039/metadata.json
{
  "isin": "INE211B01039",
  "symbol": "PHOENIXLTD",
  "name": "THE PHOENIX MILLS LTD",
  "last_fetch": "2026-06-15T07:57:07.315181Z"
}
```

```json
// File: collector/news/INE213A01029/metadata.json
{
  "isin": "INE213A01029",
  "symbol": "ONGC",
  "name": "OIL AND NATURAL GAS CORP.",
  "last_fetch": "2026-06-15T07:57:07.376532Z"
}
```

```json
// File: collector/news/INE214T01019/metadata.json
{
  "isin": "INE214T01019",
  "symbol": "LTM",
  "name": "LTM LIMITED",
  "last_fetch": "2026-06-15T07:57:11.326810Z"
}
```

```json
// File: collector/news/INE216A01030/metadata.json
{
  "isin": "INE216A01030",
  "symbol": "BRITANNIA",
  "name": "BRITANNIA INDUSTRIES LTD",
  "last_fetch": "2026-06-15T07:57:06.255712Z"
}
```

```json
// File: collector/news/INE226A01021/metadata.json
{
  "isin": "INE226A01021",
  "symbol": "VOLTAS",
  "name": "VOLTAS LTD",
  "last_fetch": "2026-06-15T07:57:06.957584Z"
}
```

```json
// File: collector/news/INE237A01036/metadata.json
{
  "isin": "INE237A01036",
  "symbol": "KOTAKBANK",
  "name": "KOTAK MAHINDRA BANK LTD",
  "last_fetch": "2026-06-15T07:57:05.134122Z"
}
```

```json
// File: collector/news/INE238A01034/metadata.json
{
  "isin": "INE238A01034",
  "symbol": "AXISBANK",
  "name": "AXIS BANK LIMITED",
  "last_fetch": "2026-06-15T07:57:10.387452Z"
}
```

```json
// File: collector/news/INE239A01024/metadata.json
{
  "isin": "INE239A01024",
  "symbol": "NESTLEIND",
  "name": "NESTLE INDIA LIMITED",
  "last_fetch": "2026-06-15T07:57:07.993257Z"
}
```

```json
// File: collector/news/INE242A01010/metadata.json
{
  "isin": "INE242A01010",
  "symbol": "IOC",
  "name": "INDIAN OIL CORP LTD",
  "last_fetch": "2026-06-15T07:57:10.507771Z"
}
```

```json
// File: collector/news/INE245A01021/metadata.json
{
  "isin": "INE245A01021",
  "symbol": "TATAPOWER",
  "name": "TATA POWER CO LTD",
  "last_fetch": "2026-06-15T07:57:08.068065Z"
}
```

```json
// File: collector/news/INE249Z01020/metadata.json
{
  "isin": "INE249Z01020",
  "symbol": "MAZDOCK",
  "name": "MAZAGON DOCK SHIPBUIL LTD",
  "last_fetch": "2026-06-15T07:57:11.476808Z"
}
```

```json
// File: collector/news/INE257A01026/metadata.json
{
  "isin": "INE257A01026",
  "symbol": "BHEL",
  "name": "BHEL",
  "last_fetch": "2026-06-15T07:57:12.461965Z"
}
```

```json
// File: collector/news/INE259A01022/metadata.json
{
  "isin": "INE259A01022",
  "symbol": "COLPAL",
  "name": "COLGATE PALMOLIVE LTD.",
  "last_fetch": "2026-06-15T07:57:12.272930Z"
}
```

```json
// File: collector/news/INE260B01028/metadata.json
{
  "isin": "INE260B01028",
  "symbol": "GODFRYPHLP",
  "name": "GODFREY PHILLIPS INDIA LT",
  "last_fetch": "2026-06-15T07:57:08.773777Z"
}
```

```json
// File: collector/news/INE262H01021/metadata.json
{
  "isin": "INE262H01021",
  "symbol": "PERSISTENT",
  "name": "PERSISTENT SYSTEMS LTD",
  "last_fetch": "2026-06-15T07:57:10.891370Z"
}
```

```json
// File: collector/news/INE263A01024/metadata.json
{
  "isin": "INE263A01024",
  "symbol": "BEL",
  "name": "BHARAT ELECTRONICS LTD",
  "last_fetch": "2026-06-15T07:57:10.628048Z"
}
```

```json
// File: collector/news/INE267A01025/metadata.json
{
  "isin": "INE267A01025",
  "symbol": "HINDZINC",
  "name": "HINDUSTAN ZINC LIMITED",
  "last_fetch": "2026-06-15T07:57:04.580860Z"
}
```

```json
// File: collector/news/INE271C01023/metadata.json
{
  "isin": "INE271C01023",
  "symbol": "DLF",
  "name": "DLF LIMITED",
  "last_fetch": "2026-06-15T07:57:09.187962Z"
}
```

```json
// File: collector/news/INE274J01014/metadata.json
{
  "isin": "INE274J01014",
  "symbol": "OIL",
  "name": "OIL INDIA LTD",
  "last_fetch": "2026-06-15T07:57:09.908872Z"
}
```

```json
// File: collector/news/INE280A01028/metadata.json
{
  "isin": "INE280A01028",
  "symbol": "TITAN",
  "name": "TITAN COMPANY LIMITED",
  "last_fetch": "2026-06-15T07:57:09.759782Z"
}
```

```json
// File: collector/news/INE296A01032/metadata.json
{
  "isin": "INE296A01032",
  "symbol": "BAJFINANCE",
  "name": "BAJAJ FINANCE LIMITED",
  "last_fetch": "2026-06-15T07:57:08.370192Z"
}
```

```json
// File: collector/news/INE298A01020/metadata.json
{
  "isin": "INE298A01020",
  "symbol": "CUMMINSIND",
  "name": "CUMMINS INDIA LTD",
  "last_fetch": "2026-06-15T07:57:09.875815Z"
}
```

```json
// File: collector/news/INE298J01013/metadata.json
{
  "isin": "INE298J01013",
  "symbol": "NAM-INDIA",
  "name": "NIPPON L I A M LTD",
  "last_fetch": "2026-06-15T07:57:06.781948Z"
}
```

```json
// File: collector/news/INE299U01018/metadata.json
{
  "isin": "INE299U01018",
  "symbol": "CROMPTON",
  "name": "CROMPT GREA CON ELEC LTD",
  "last_fetch": "2026-06-15T07:57:08.805103Z"
}
```

```json
// File: collector/news/INE302A01020/metadata.json
{
  "isin": "INE302A01020",
  "symbol": "EXIDEIND",
  "name": "EXIDE INDUSTRIES LTD",
  "last_fetch": "2026-06-15T07:57:09.613803Z"
}
```

```json
// File: collector/news/INE303R01014/metadata.json
{
  "isin": "INE303R01014",
  "symbol": "KALYANKJIL",
  "name": "KALYAN JEWELLERS IND LTD",
  "last_fetch": "2026-06-15T07:57:11.769867Z"
}
```

```json
// File: collector/news/INE318A01026/metadata.json
{
  "isin": "INE318A01026",
  "symbol": "PIDILITIND",
  "name": "PIDILITE INDUSTRIES LTD",
  "last_fetch": "2026-06-15T07:57:09.167258Z"
}
```

```json
// File: collector/news/INE323A01026/metadata.json
{
  "isin": "INE323A01026",
  "symbol": "BOSCHLTD",
  "name": "BOSCH LIMITED",
  "last_fetch": "2026-06-15T07:57:11.247397Z"
}
```

```json
// File: collector/news/INE326A01037/metadata.json
{
  "isin": "INE326A01037",
  "symbol": "LUPIN",
  "name": "LUPIN LIMITED",
  "last_fetch": "2026-06-15T07:57:07.170880Z"
}
```

```json
// File: collector/news/INE338I01027/metadata.json
{
  "isin": "INE338I01027",
  "symbol": "MOTILALOFS",
  "name": "MOTILAL OSWAL FIN LTD",
  "last_fetch": "2026-06-15T07:57:10.378058Z"
}
```

```json
// File: collector/news/INE343H01029/metadata.json
{
  "isin": "INE343H01029",
  "symbol": "SOLARINDS",
  "name": "SOLAR INDUSTRIES (I) LTD",
  "last_fetch": "2026-06-15T07:57:11.515699Z"
}
```

```json
// File: collector/news/INE347G01014/metadata.json
{
  "isin": "INE347G01014",
  "symbol": "PETRONET",
  "name": "PETRONET LNG LIMITED",
  "last_fetch": "2026-06-15T07:57:12.209163Z"
}
```

```json
// File: collector/news/INE356A01018/metadata.json
{
  "isin": "INE356A01018",
  "symbol": "MPHASIS",
  "name": "MPHASIS LIMITED",
  "last_fetch": "2026-06-15T07:57:07.677575Z"
}
```

```json
// File: collector/news/INE361B01024/metadata.json
{
  "isin": "INE361B01024",
  "symbol": "DIVISLAB",
  "name": "DIVI S LABORATORIES LTD",
  "last_fetch": "2026-06-15T07:57:05.143673Z"
}
```

```json
// File: collector/news/INE364U01010/metadata.json
{
  "isin": "INE364U01010",
  "symbol": "ADANIGREEN",
  "name": "ADANI GREEN ENERGY LTD",
  "last_fetch": "2026-06-15T07:57:10.161782Z"
}
```

```json
// File: collector/news/INE371P01015/metadata.json
{
  "isin": "INE371P01015",
  "symbol": "AMBER",
  "name": "AMBER ENTERPRISES (I) LTD",
  "last_fetch": "2026-06-15T07:57:08.941503Z"
}
```

```json
// File: collector/news/INE376G01013/metadata.json
{
  "isin": "INE376G01013",
  "symbol": "BIOCON",
  "name": "BIOCON LIMITED.",
  "last_fetch": "2026-06-15T07:57:04.991804Z"
}
```

```json
// File: collector/news/INE377N01017/metadata.json
{
  "isin": "INE377N01017",
  "symbol": "WAAREEENER",
  "name": "WAAREE ENERGIES LIMITED",
  "last_fetch": "2026-06-15T07:57:06.639698Z"
}
```

```json
// File: collector/news/INE388Y01029/metadata.json
{
  "isin": "INE388Y01029",
  "symbol": "NYKAA",
  "name": "FSN E COMMERCE VENTURES",
  "last_fetch": "2026-06-15T07:57:07.959786Z"
}
```

```json
// File: collector/news/INE397D01024/metadata.json
{
  "isin": "INE397D01024",
  "symbol": "BHARTIARTL",
  "name": "BHARTI AIRTEL LIMITED",
  "last_fetch": "2026-06-15T07:57:09.626158Z"
}
```

```json
// File: collector/news/INE405E01023/metadata.json
{
  "isin": "INE405E01023",
  "symbol": "UNOMINDA",
  "name": "UNO MINDA LIMITED",
  "last_fetch": "2026-06-15T07:57:05.689093Z"
}
```

```json
// File: collector/news/INE406A01037/metadata.json
{
  "isin": "INE406A01037",
  "symbol": "AUROPHARMA",
  "name": "AUROBINDO PHARMA LTD",
  "last_fetch": "2026-06-15T07:57:11.742931Z"
}
```

```json
// File: collector/news/INE414G01012/metadata.json
{
  "isin": "INE414G01012",
  "symbol": "MUTHOOTFIN",
  "name": "MUTHOOT FINANCE LIMITED",
  "last_fetch": "2026-06-15T07:57:07.284679Z"
}
```

```json
// File: collector/news/INE415G01027/metadata.json
{
  "isin": "INE415G01027",
  "symbol": "RVNL",
  "name": "RAIL VIKAS NIGAM LIMITED",
  "last_fetch": "2026-06-15T07:57:11.280631Z"
}
```

```json
// File: collector/news/INE417T01026/metadata.json
{
  "isin": "INE417T01026",
  "symbol": "POLICYBZR",
  "name": "PB FINTECH LIMITED",
  "last_fetch": "2026-06-15T07:57:11.174245Z"
}
```

```json
// File: collector/news/INE423A01024/metadata.json
{
  "isin": "INE423A01024",
  "symbol": "ADANIENT",
  "name": "ADANI ENTERPRISES LIMITED",
  "last_fetch": "2026-06-15T07:57:12.304626Z"
}
```

```json
// File: collector/news/INE437A01024/metadata.json
{
  "isin": "INE437A01024",
  "symbol": "APOLLOHOSP",
  "name": "APOLLO HOSPITALS ENTER. L",
  "last_fetch": "2026-06-15T07:57:07.962783Z"
}
```

```json
// File: collector/news/INE451A01017/metadata.json
{
  "isin": "INE451A01017",
  "symbol": "FORCEMOT",
  "name": "FORCE MOTORS LIMITED",
  "last_fetch": "2026-06-15T07:57:09.421816Z"
}
```

```json
// File: collector/news/INE455K01017/metadata.json
{
  "isin": "INE455K01017",
  "symbol": "POLYCAB",
  "name": "POLYCAB INDIA LIMITED",
  "last_fetch": "2026-06-15T07:57:11.597420Z"
}
```

```json
// File: collector/news/INE457L01029/metadata.json
{
  "isin": "INE457L01029",
  "symbol": "PGEL",
  "name": "PG ELECTROPLAST LTD",
  "last_fetch": "2026-06-15T07:57:08.608214Z"
}
```

```json
// File: collector/news/INE465A01025/metadata.json
{
  "isin": "INE465A01025",
  "symbol": "BHARATFORG",
  "name": "BHARAT FORGE LTD",
  "last_fetch": "2026-06-15T07:57:05.119314Z"
}
```

```json
// File: collector/news/INE466L01038/metadata.json
{
  "isin": "INE466L01038",
  "symbol": "360ONE",
  "name": "360 ONE WAM LIMITED",
  "last_fetch": "2026-06-15T07:57:04.574019Z"
}
```

```json
// File: collector/news/INE467B01029/metadata.json
{
  "isin": "INE467B01029",
  "symbol": "TCS",
  "name": "TATA CONSULTANCY SERV LT",
  "last_fetch": "2026-06-15T07:57:08.585419Z"
}
```

```json
// File: collector/news/INE472A01039/metadata.json
{
  "isin": "INE472A01039",
  "symbol": "BLUESTARCO",
  "name": "BLUE STAR LIMITED",
  "last_fetch": "2026-06-15T07:57:11.949655Z"
}
```

```json
// File: collector/news/INE476A01022/metadata.json
{
  "isin": "INE476A01022",
  "symbol": "CANBK",
  "name": "CANARA BANK",
  "last_fetch": "2026-06-15T07:57:05.398374Z"
}
```

```json
// File: collector/news/INE481G01011/metadata.json
{
  "isin": "INE481G01011",
  "symbol": "ULTRACEMCO",
  "name": "ULTRATECH CEMENT LIMITED",
  "last_fetch": "2026-06-15T07:57:12.470296Z"
}
```

```json
// File: collector/news/INE484J01027/metadata.json
{
  "isin": "INE484J01027",
  "symbol": "GODREJPROP",
  "name": "GODREJ PROPERTIES LTD",
  "last_fetch": "2026-06-15T07:57:06.692319Z"
}
```

```json
// File: collector/news/INE494B01023/metadata.json
{
  "isin": "INE494B01023",
  "symbol": "TVSMOTOR",
  "name": "TVS MOTOR COMPANY  LTD",
  "last_fetch": "2026-06-15T07:57:09.504351Z"
}
```

```json
// File: collector/news/INE498L01015/metadata.json
{
  "isin": "INE498L01015",
  "symbol": "LTF",
  "name": "L&T FINANCE LIMITED",
  "last_fetch": "2026-06-15T07:57:06.468853Z"
}
```

```json
// File: collector/news/INE522D01027/metadata.json
{
  "isin": "INE522D01027",
  "symbol": "MANAPPURAM",
  "name": "MANAPPURAM FINANCE LTD",
  "last_fetch": "2026-06-15T07:57:04.433111Z"
}
```

```json
// File: collector/news/INE522F01014/metadata.json
{
  "isin": "INE522F01014",
  "symbol": "COALINDIA",
  "name": "COAL INDIA LTD",
  "last_fetch": "2026-06-15T07:57:08.366610Z"
}
```

```json
// File: collector/news/INE528G01035/metadata.json
{
  "isin": "INE528G01035",
  "symbol": "YESBANK",
  "name": "YES BANK LIMITED",
  "last_fetch": "2026-06-15T07:57:05.900512Z"
}
```

```json
// File: collector/news/INE531F01023/metadata.json
{
  "isin": "INE531F01023",
  "symbol": "NUVAMA",
  "name": "NUVAMA WEALTH MANAGE LTD",
  "last_fetch": "2026-06-15T07:57:06.000517Z"
}
```

```json
// File: collector/news/INE540L01014/metadata.json
{
  "isin": "INE540L01014",
  "symbol": "ALKEM",
  "name": "ALKEM LABORATORIES LTD.",
  "last_fetch": "2026-06-15T07:57:05.130126Z"
}
```

```json
// File: collector/news/INE545U01014/metadata.json
{
  "isin": "INE545U01014",
  "symbol": "BANDHANBNK",
  "name": "BANDHAN BANK LIMITED",
  "last_fetch": "2026-06-15T07:57:11.429894Z"
}
```

```json
// File: collector/news/INE562A01011/metadata.json
{
  "isin": "INE562A01011",
  "symbol": "INDIANB",
  "name": "INDIAN BANK",
  "last_fetch": "2026-06-15T07:57:10.189173Z"
}
```

```json
// File: collector/news/INE572E01012/metadata.json
{
  "isin": "INE572E01012",
  "symbol": "PNBHOUSING",
  "name": "PNB HOUSING FIN LTD.",
  "last_fetch": "2026-06-15T07:57:10.090531Z"
}
```

```json
// File: collector/news/INE584A01023/metadata.json
{
  "isin": "INE584A01023",
  "symbol": "NMDC",
  "name": "NMDC LTD.",
  "last_fetch": "2026-06-15T07:57:07.226645Z"
}
```

```json
// File: collector/news/INE585B01010/metadata.json
{
  "isin": "INE585B01010",
  "symbol": "MARUTI",
  "name": "MARUTI SUZUKI INDIA LTD.",
  "last_fetch": "2026-06-15T07:57:04.457223Z"
}
```

```json
// File: collector/news/INE591G01025/metadata.json
{
  "isin": "INE591G01025",
  "symbol": "COFORGE",
  "name": "COFORGE LIMITED",
  "last_fetch": "2026-06-15T07:57:04.779821Z"
}
```

```json
// File: collector/news/INE596I01020/metadata.json
{
  "isin": "INE596I01020",
  "symbol": "CAMS",
  "name": "COMPUTER AGE MNGT SER LTD",
  "last_fetch": "2026-06-15T07:57:07.709761Z"
}
```

```json
// File: collector/news/INE603J01030/metadata.json
{
  "isin": "INE603J01030",
  "symbol": "PIIND",
  "name": "PI INDUSTRIES LTD",
  "last_fetch": "2026-06-15T07:57:08.992997Z"
}
```

```json
// File: collector/news/INE619A01035/metadata.json
{
  "isin": "INE619A01035",
  "symbol": "PATANJALI",
  "name": "PATANJALI FOODS LIMITED",
  "last_fetch": "2026-06-15T07:57:05.112127Z"
}
```

```json
// File: collector/news/INE628A01036/metadata.json
{
  "isin": "INE628A01036",
  "symbol": "UPL",
  "name": "UPL LIMITED",
  "last_fetch": "2026-06-15T07:57:11.949203Z"
}
```

```json
// File: collector/news/INE634S01028/metadata.json
{
  "isin": "INE634S01028",
  "symbol": "MANKIND",
  "name": "MANKIND PHARMA LIMITED",
  "last_fetch": "2026-06-15T07:57:09.616791Z"
}
```

```json
// File: collector/news/INE646L01027/metadata.json
{
  "isin": "INE646L01027",
  "symbol": "INDIGO",
  "name": "INTERGLOBE AVIATION LTD",
  "last_fetch": "2026-06-15T07:57:09.483664Z"
}
```

```json
// File: collector/news/INE647A01010/metadata.json
{
  "isin": "INE647A01010",
  "symbol": "SRF",
  "name": "SRF LTD",
  "last_fetch": "2026-06-15T07:57:10.641695Z"
}
```

```json
// File: collector/news/INE663F01032/metadata.json
{
  "isin": "INE663F01032",
  "symbol": "NAUKRI",
  "name": "INFO EDGE (I) LTD",
  "last_fetch": "2026-06-15T07:57:09.016079Z"
}
```

```json
// File: collector/news/INE669C01036/metadata.json
{
  "isin": "INE669C01036",
  "symbol": "TECHM",
  "name": "TECH MAHINDRA LIMITED",
  "last_fetch": "2026-06-15T07:57:06.200516Z"
}
```

```json
// File: collector/news/INE669E01016/metadata.json
{
  "isin": "INE669E01016",
  "symbol": "IDEA",
  "name": "VODAFONE IDEA LIMITED",
  "last_fetch": "2026-06-15T07:57:07.323022Z"
}
```

```json
// File: collector/news/INE670A01012/metadata.json
{
  "isin": "INE670A01012",
  "symbol": "TATAELXSI",
  "name": "TATA ELXSI LIMITED",
  "last_fetch": "2026-06-15T07:57:12.661938Z"
}
```

```json
// File: collector/news/INE670K01029/metadata.json
{
  "isin": "INE670K01029",
  "symbol": "LODHA",
  "name": "LODHA DEVELOPERS LIMITED",
  "last_fetch": "2026-06-15T07:57:05.526722Z"
}
```

```json
// File: collector/news/INE674K01013/metadata.json
{
  "isin": "INE674K01013",
  "symbol": "ABCAPITAL",
  "name": "ADITYA BIRLA CAPITAL LTD.",
  "last_fetch": "2026-06-15T07:57:05.850200Z"
}
```

```json
// File: collector/news/INE685A01028/metadata.json
{
  "isin": "INE685A01028",
  "symbol": "TORNTPHARM",
  "name": "TORRENT PHARMACEUTICALS L",
  "last_fetch": "2026-06-15T07:57:10.647207Z"
}
```

```json
// File: collector/news/INE692A01016/metadata.json
{
  "isin": "INE692A01016",
  "symbol": "UNIONBANK",
  "name": "UNION BANK OF INDIA",
  "last_fetch": "2026-06-15T07:57:10.509596Z"
}
```

```json
// File: collector/news/INE702C01027/metadata.json
{
  "isin": "INE702C01027",
  "symbol": "APLAPOLLO",
  "name": "APL APOLLO TUBES LTD",
  "last_fetch": "2026-06-15T07:57:07.948230Z"
}
```

```json
// File: collector/news/INE704P01025/metadata.json
{
  "isin": "INE704P01025",
  "symbol": "COCHINSHIP",
  "name": "COCHIN SHIPYARD LIMITED",
  "last_fetch": "2026-06-15T07:57:07.468429Z"
}
```

```json
// File: collector/news/INE721A01047/metadata.json
{
  "isin": "INE721A01047",
  "symbol": "SHRIRAMFIN",
  "name": "SHRIRAM FINANCE LIMITED",
  "last_fetch": "2026-06-15T07:57:05.535649Z"
}
```

```json
// File: collector/news/INE726G01019/metadata.json
{
  "isin": "INE726G01019",
  "symbol": "ICICIPRULI",
  "name": "ICICI PRU LIFE INS CO LTD",
  "last_fetch": "2026-06-15T07:57:06.138813Z"
}
```

```json
// File: collector/news/INE732I01021/metadata.json
{
  "isin": "INE732I01021",
  "symbol": "ANGELONE",
  "name": "ANGEL ONE LIMITED",
  "last_fetch": "2026-06-15T07:57:09.691693Z"
}
```

```json
// File: collector/news/INE733E01010/metadata.json
{
  "isin": "INE733E01010",
  "symbol": "NTPC",
  "name": "NTPC LTD",
  "last_fetch": "2026-06-15T07:57:07.714642Z"
}
```

```json
// File: collector/news/INE736A01011/metadata.json
{
  "isin": "INE736A01011",
  "symbol": "CDSL",
  "name": "CENTRAL DEPO SER (I) LTD",
  "last_fetch": "2026-06-15T07:57:04.839159Z"
}
```

```json
// File: collector/news/INE742F01042/metadata.json
{
  "isin": "INE742F01042",
  "symbol": "ADANIPORTS",
  "name": "ADANI PORT & SEZ LTD",
  "last_fetch": "2026-06-15T07:57:07.037104Z"
}
```

```json
// File: collector/news/INE745G01043/metadata.json
{
  "isin": "INE745G01043",
  "symbol": "MCX",
  "name": "MULTI COMMODITY EXCHANGE",
  "last_fetch": "2026-06-15T07:57:05.373515Z"
}
```

```json
// File: collector/news/INE749A01030/metadata.json
{
  "isin": "INE749A01030",
  "symbol": "JINDALSTEL",
  "name": "JINDAL STEEL LIMITED",
  "last_fetch": "2026-06-15T07:57:04.590069Z"
}
```

```json
// File: collector/news/INE752E01010/metadata.json
{
  "isin": "INE752E01010",
  "symbol": "POWERGRID",
  "name": "POWER GRID CORP. LTD.",
  "last_fetch": "2026-06-15T07:57:09.132969Z"
}
```

```json
// File: collector/news/INE758E01017/metadata.json
{
  "isin": "INE758E01017",
  "symbol": "JIOFIN",
  "name": "JIO FIN SERVICES LTD",
  "last_fetch": "2026-06-15T07:57:09.244230Z"
}
```

```json
// File: collector/news/INE758T01015/metadata.json
{
  "isin": "INE758T01015",
  "symbol": "ETERNAL",
  "name": "ETERNAL LIMITED",
  "last_fetch": "2026-06-15T07:57:11.532082Z"
}
```

```json
// File: collector/news/INE761H01022/metadata.json
{
  "isin": "INE761H01022",
  "symbol": "PAGEIND",
  "name": "PAGE INDUSTRIES LTD",
  "last_fetch": "2026-06-15T07:57:09.362326Z"
}
```

```json
// File: collector/news/INE765G01017/metadata.json
{
  "isin": "INE765G01017",
  "symbol": "ICICIGI",
  "name": "ICICI LOMBARD GIC LIMITED",
  "last_fetch": "2026-06-15T07:57:12.447143Z"
}
```

```json
// File: collector/news/INE775A01035/metadata.json
{
  "isin": "INE775A01035",
  "symbol": "MOTHERSON",
  "name": "SAMVRDHNA MTHRSN INTL LTD",
  "last_fetch": "2026-06-15T07:57:11.113441Z"
}
```

```json
// File: collector/news/INE776C01039/metadata.json
{
  "isin": "INE776C01039",
  "symbol": "GMRAIRPORT",
  "name": "GMR AIRPORTS LIMITED",
  "last_fetch": "2026-06-15T07:57:11.166848Z"
}
```

```json
// File: collector/news/INE795G01014/metadata.json
{
  "isin": "INE795G01014",
  "symbol": "HDFCLIFE",
  "name": "HDFC LIFE INS CO LTD",
  "last_fetch": "2026-06-15T07:57:09.751525Z"
}
```

```json
// File: collector/news/INE797F01020/metadata.json
{
  "isin": "INE797F01020",
  "symbol": "JUBLFOOD",
  "name": "JUBILANT FOODWORKS LTD",
  "last_fetch": "2026-06-15T07:57:09.706052Z"
}
```

```json
// File: collector/news/INE811K01011/metadata.json
{
  "isin": "INE811K01011",
  "symbol": "PRESTIGE",
  "name": "PRESTIGE ESTATE LTD",
  "last_fetch": "2026-06-15T07:57:05.307592Z"
}
```

```json
// File: collector/news/INE814H01029/metadata.json
{
  "isin": "INE814H01029",
  "symbol": "ADANIPOWER",
  "name": "ADANI POWER LTD",
  "last_fetch": "2026-06-15T07:57:04.837255Z"
}
```

```json
// File: collector/news/INE848E01016/metadata.json
{
  "isin": "INE848E01016",
  "symbol": "NHPC",
  "name": "NHPC LTD",
  "last_fetch": "2026-06-15T07:57:09.276080Z"
}
```

```json
// File: collector/news/INE849A01020/metadata.json
{
  "isin": "INE849A01020",
  "symbol": "TRENT",
  "name": "TRENT LTD",
  "last_fetch": "2026-06-15T07:57:06.189391Z"
}
```

```json
// File: collector/news/INE854D01024/metadata.json
{
  "isin": "INE854D01024",
  "symbol": "UNITDSPR",
  "name": "UNITED SPIRITS LIMITED",
  "last_fetch": "2026-06-15T07:57:07.041099Z"
}
```

```json
// File: collector/news/INE860A01027/metadata.json
{
  "isin": "INE860A01027",
  "symbol": "HCLTECH",
  "name": "HCL TECHNOLOGIES LTD",
  "last_fetch": "2026-06-15T07:57:10.751521Z"
}
```

```json
// File: collector/news/INE878B01027/metadata.json
{
  "isin": "INE878B01027",
  "symbol": "KEI",
  "name": "KEI INDUSTRIES LTD.",
  "last_fetch": "2026-06-15T07:57:11.064863Z"
}
```

```json
// File: collector/news/INE881D01027/metadata.json
{
  "isin": "INE881D01027",
  "symbol": "OFSS",
  "name": "ORACLE FIN SERV SOFT LTD.",
  "last_fetch": "2026-06-15T07:57:09.746557Z"
}
```

```json
// File: collector/news/INE917I01010/metadata.json
{
  "isin": "INE917I01010",
  "symbol": "BAJAJ-AUTO",
  "name": "BAJAJ AUTO LIMITED",
  "last_fetch": "2026-06-15T07:57:04.449455Z"
}
```

```json
// File: collector/news/INE918I01026/metadata.json
{
  "isin": "INE918I01026",
  "symbol": "BAJAJFINSV",
  "name": "BAJAJ FINSERV LTD.",
  "last_fetch": "2026-06-15T07:57:09.183293Z"
}
```

```json
// File: collector/news/INE918Z01012/metadata.json
{
  "isin": "INE918Z01012",
  "symbol": "KAYNES",
  "name": "KAYNES TECHNOLOGY IND LTD",
  "last_fetch": "2026-06-15T07:57:04.993530Z"
}
```

```json
// File: collector/news/INE931S01010/metadata.json
{
  "isin": "INE931S01010",
  "symbol": "ADANIENSOL",
  "name": "ADANI ENERGY SOLUTION LTD",
  "last_fetch": "2026-06-15T07:57:07.438409Z"
}
```

```json
// File: collector/news/INE935A01035/metadata.json
{
  "isin": "INE935A01035",
  "symbol": "GLENMARK",
  "name": "GLENMARK PHARMACEUTICALS",
  "last_fetch": "2026-06-15T07:57:12.522757Z"
}
```

```json
// File: collector/news/INE935N01020/metadata.json
{
  "isin": "INE935N01020",
  "symbol": "DIXON",
  "name": "DIXON TECHNO (INDIA) LTD",
  "last_fetch": "2026-06-15T07:57:10.365304Z"
}
```

```json
// File: collector/news/INE944F01028/metadata.json
{
  "isin": "INE944F01028",
  "symbol": "RADICO",
  "name": "RADICO KHAITAN LTD",
  "last_fetch": "2026-06-15T07:57:09.921527Z"
}
```

```json
// File: collector/news/INE947Q01028/metadata.json
{
  "isin": "INE947Q01028",
  "symbol": "LAURUSLABS",
  "name": "LAURUS LABS LIMITED",
  "last_fetch": "2026-06-15T07:57:09.171010Z"
}
```

```json
// File: collector/news/INE949L01017/metadata.json
{
  "isin": "INE949L01017",
  "symbol": "AUBANK",
  "name": "AU SMALL FINANCE BANK LTD",
  "last_fetch": "2026-06-15T07:57:09.717499Z"
}
```

```json
// File: collector/news/INE974X01010/metadata.json
{
  "isin": "INE974X01010",
  "symbol": "TIINDIA",
  "name": "TUBE INVEST OF INDIA LTD",
  "last_fetch": "2026-06-15T07:57:06.860395Z"
}
```

```json
// File: collector/news/INE976G01028/metadata.json
{
  "isin": "INE976G01028",
  "symbol": "RBLBANK",
  "name": "RBL BANK LIMITED",
  "last_fetch": "2026-06-15T07:57:10.779267Z"
}
```

```json
// File: collector/news/INE982J01020/metadata.json
{
  "isin": "INE982J01020",
  "symbol": "PAYTM",
  "name": "ONE 97 COMMUNICATIONS LTD",
  "last_fetch": "2026-06-15T07:57:09.281809Z"
}
```

```json
// File: news/state/holdings_snapshot.json
[]
```

```json
// File: news/state/positions_snapshot.json
[]
```
