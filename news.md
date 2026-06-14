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

    private final Refresh refresh = new Refresh();
    private final Retention retention = new Retention();
    private final Upstox upstox = new Upstox();
    private final Storage storage = new Storage();

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
        private String root = "storage/news";
        private String holdingsView = "storage/news/holdings.jsonl";
        private String positionsView = "storage/news/positions.jsonl";

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsRefreshScheduler {

    private final PortfolioNewsBuilderService builderService;

    // Refresh holdings every 15 minutes
    @Scheduled(fixedDelayString = "PT15M")
    public void refreshHoldingsNews() {
        log.info("Running scheduled refresh for Holdings News");
        builderService.buildHoldingsView();
    }

    // Refresh positions every 15 minutes
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public void refreshPositionsNews() {
        log.info("Running scheduled refresh for Positions News");
        builderService.buildPositionsView();
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

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsStartupRunner implements CommandLineRunner {

    private final PortfolioNewsBuilderService builderService;

    @Override
    public void run(String... args) {
        log.info("Vega News Application started. Initializing views...");
        builderService.buildHoldingsView();
        builderService.buildPositionsView();
        log.info("Initialization complete. Scheduled tasks will handle future news view generation.");
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

    public Map<String, List<NewsArticle>> getNewsForIsins(Set<String> isins) {
        Map<String, List<NewsArticle>> result = new HashMap<>();
        Map<String, String> isinToInstrumentKey = new HashMap<>();

        // Group into batches of 30
        List<String> currentBatchKeys = new ArrayList<>();
        List<String> currentBatchIsins = new ArrayList<>();

        for (String isin : isins) {
            String instrumentKey = instrumentService.getInstrumentKeyByIsin(isin);
            if (instrumentKey != null) {
                isinToInstrumentKey.put(instrumentKey, isin); // For reverse lookup
                currentBatchKeys.add(instrumentKey);
                currentBatchIsins.add(isin);

                if (currentBatchKeys.size() == 30) {
                    processBatch(currentBatchIsins, currentBatchKeys, isinToInstrumentKey);
                    currentBatchKeys.clear();
                    currentBatchIsins.clear();
                }
            } else {
                // If no key, just load archive if exists
                result.put(isin, archiveService.loadArchive(isin));
            }
        }

        // Process remainder
        if (!currentBatchKeys.isEmpty()) {
            processBatch(currentBatchIsins, currentBatchKeys, isinToInstrumentKey);
        }

        // Collect all from archives after batches are processed and appended
        for (String isin : isins) {
            List<NewsArticle> archivedArticles = archiveService.loadArchive(isin);
            archivedArticles.sort((a, b) -> Long.compare(b.getPublishedTime(), a.getPublishedTime()));
            result.put(isin, archivedArticles);
        }

        return result;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsInstrumentArchiveService {

    private final ObjectMapper objectMapper;
    private final NewsProperties properties;

    public List<NewsArticle> loadArchive(String isin) {
        Path archivePath = getArchivePath(isin);
        List<NewsArticle> articles = new ArrayList<>();
        if (Files.exists(archivePath)) {
            try (BufferedReader reader = Files.newBufferedReader(archivePath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    articles.add(objectMapper.readValue(line, NewsArticle.class));
                }
            } catch (IOException e) {
                log.error("Failed to load archive for ISIN: {}", isin, e);
            }
        }
        return articles;
    }

    public void appendNewArticles(String isin, List<NewsArticle> newArticles) {
        if (newArticles == null || newArticles.isEmpty()) return;

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
            updateMetadata(isin, existingHashes.size(), latestPublishedTime);
            log.info("Appended {} new articles for ISIN: {}", articlesToAppend.size(), isin);
        } catch (IOException e) {
            log.error("Failed to append articles for ISIN: {}", isin, e);
        }
    }

    private Set<String> getExistingHashes(String isin) {
        Set<String> hashes = new HashSet<>();
        Path archivePath = getArchivePath(isin);
        if (Files.exists(archivePath)) {
            try (BufferedReader reader = Files.newBufferedReader(archivePath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode node = objectMapper.readTree(line);
                    if (node.has("sourceHash")) {
                        hashes.add(node.get("sourceHash").asText());
                    }
                }
            } catch (IOException e) {
                log.error("Failed to read existing hashes for ISIN: {}", isin, e);
            }
        }
        return hashes;
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
    private final NewsInstrumentArchiveService archiveService;
    private final NewsMergeService mergeService;
    private final ObjectMapper objectMapper;
    private final NewsProperties properties;

    public void buildHoldingsView() {
        log.info("Building Holdings News View...");
        Set<String> isins = readerService.readHoldingsIsins();
        buildView(isins, Paths.get(properties.getStorage().getHoldingsView()));
    }

    public void buildPositionsView() {
        log.info("Building Positions News View...");
        Set<String> isins = readerService.readPositionsIsins();
        buildView(isins, Paths.get(properties.getStorage().getPositionsView()));
    }

    private void buildView(Set<String> isins, Path outputPath) {
        if (isins.isEmpty()) {
            log.info("No ISINs found for view: {}", outputPath);
            return;
        }

        List<List<NewsArticle>> allArchives = new ArrayList<>(instrumentNewsService.getNewsForIsins(isins).values());

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
  instrument-path: /root/news/data/instruments/upstox/upstox.json
  auth:
    file: /root/news/auth/upstox/auth.upstox.json

news:
  refresh:
    interval: 15m
  retention:
    days: 3650
  upstox:
    page-size: 100
  storage:
    root: /root/news/storage/news
    holdings-view: /root/news/storage/news/holdings.jsonl
    positions-view: /root/news/storage/news/positions.jsonl
```

```json
// File: collector/news/INE002A01018/metadata.json
{
  "isin": "INE002A01018",
  "symbol": "RELIANCE",
  "name": "RELIANCE INDUSTRIES LTD",
  "last_fetch": "2026-06-14T14:30:37.757859Z"
}
```

```json
// File: collector/news/INE003A01024/metadata.json
{
  "isin": "INE003A01024",
  "symbol": "SIEMENS",
  "name": "SIEMENS LTD",
  "last_fetch": "2026-06-14T14:30:43.996975Z"
}
```

```json
// File: collector/news/INE006I01046/metadata.json
{
  "isin": "INE006I01046",
  "symbol": "ASTRAL",
  "name": "ASTRAL LIMITED",
  "last_fetch": "2026-06-14T14:30:34.736094Z"
}
```

```json
// File: collector/news/INE009A01021/metadata.json
{
  "isin": "INE009A01021",
  "symbol": "INFY",
  "name": "INFOSYS LIMITED",
  "last_fetch": "2026-06-14T14:30:33.792489Z"
}
```

```json
// File: collector/news/INE00H001014/metadata.json
{
  "isin": "INE00H001014",
  "symbol": "SWIGGY",
  "name": "SWIGGY LIMITED",
  "last_fetch": "2026-06-14T14:30:41.468477Z"
}
```

```json
// File: collector/news/INE00R701025/metadata.json
{
  "isin": "INE00R701025",
  "symbol": "DALBHARAT",
  "name": "DALMIA BHARAT LIMITED",
  "last_fetch": "2026-06-14T14:30:33.091849Z"
}
```

```json
// File: collector/news/INE010B01027/metadata.json
{
  "isin": "INE010B01027",
  "symbol": "ZYDUSLIFE",
  "name": "ZYDUS LIFESCIENCES LTD",
  "last_fetch": "2026-06-14T14:30:39.694973Z"
}
```

```json
// File: collector/news/INE016A01026/metadata.json
{
  "isin": "INE016A01026",
  "symbol": "DABUR",
  "name": "DABUR INDIA LTD",
  "last_fetch": "2026-06-14T14:30:44.342876Z"
}
```

```json
// File: collector/news/INE018A01030/metadata.json
{
  "isin": "INE018A01030",
  "symbol": "LT",
  "name": "LARSEN & TOUBRO LTD.",
  "last_fetch": "2026-06-14T14:30:43.296278Z"
}
```

```json
// File: collector/news/INE018E01016/metadata.json
{
  "isin": "INE018E01016",
  "symbol": "SBICARD",
  "name": "SBI CARDS & PAY SER LTD",
  "last_fetch": "2026-06-14T14:30:41.912904Z"
}
```

```json
// File: collector/news/INE019A01038/metadata.json
{
  "isin": "INE019A01038",
  "symbol": "JSWSTEEL",
  "name": "JSW STEEL LIMITED",
  "last_fetch": "2026-06-14T14:30:37.456077Z"
}
```

```json
// File: collector/news/INE01EA01019/metadata.json
{
  "isin": "INE01EA01019",
  "symbol": "VMM",
  "name": "VISHAL MEGA MART LIMITED",
  "last_fetch": "2026-06-14T14:30:34.133736Z"
}
```

```json
// File: collector/news/INE020B01018/metadata.json
{
  "isin": "INE020B01018",
  "symbol": "RECLTD",
  "name": "REC LIMITED",
  "last_fetch": "2026-06-14T14:30:41.276721Z"
}
```

```json
// File: collector/news/INE021A01026/metadata.json
{
  "isin": "INE021A01026",
  "symbol": "ASIANPAINT",
  "name": "ASIAN PAINTS LIMITED",
  "last_fetch": "2026-06-14T14:30:36.774923Z"
}
```

```json
// File: collector/news/INE022Q01020/metadata.json
{
  "isin": "INE022Q01020",
  "symbol": "IEX",
  "name": "INDIAN ENERGY EXC LTD",
  "last_fetch": "2026-06-14T14:30:36.809207Z"
}
```

```json
// File: collector/news/INE027H01010/metadata.json
{
  "isin": "INE027H01010",
  "symbol": "MAXHEALTH",
  "name": "MAX HEALTHCARE INS LTD",
  "last_fetch": "2026-06-14T14:30:35.246838Z"
}
```

```json
// File: collector/news/INE028A01039/metadata.json
{
  "isin": "INE028A01039",
  "symbol": "BANKBARODA",
  "name": "BANK OF BARODA",
  "last_fetch": "2026-06-14T14:30:34.288520Z"
}
```

```json
// File: collector/news/INE029A01011/metadata.json
{
  "isin": "INE029A01011",
  "symbol": "BPCL",
  "name": "BHARAT PETROLEUM CORP  LT",
  "last_fetch": "2026-06-14T14:30:44.057255Z"
}
```

```json
// File: collector/news/INE030A01027/metadata.json
{
  "isin": "INE030A01027",
  "symbol": "HINDUNILVR",
  "name": "HINDUSTAN UNILEVER LTD.",
  "last_fetch": "2026-06-14T14:30:34.176301Z"
}
```

```json
// File: collector/news/INE038A01020/metadata.json
{
  "isin": "INE038A01020",
  "symbol": "HINDALCO",
  "name": "HINDALCO  INDUSTRIES  LTD",
  "last_fetch": "2026-06-14T14:30:41.021672Z"
}
```

```json
// File: collector/news/INE040A01034/metadata.json
{
  "isin": "INE040A01034",
  "symbol": "HDFCBANK",
  "name": "HDFC BANK LTD",
  "last_fetch": "2026-06-14T14:30:38.361870Z"
}
```

```json
// File: collector/news/INE040H01021/metadata.json
{
  "isin": "INE040H01021",
  "symbol": "SUZLON",
  "name": "SUZLON ENERGY LIMITED",
  "last_fetch": "2026-06-14T14:30:43.274483Z"
}
```

```json
// File: collector/news/INE044A01036/metadata.json
{
  "isin": "INE044A01036",
  "symbol": "SUNPHARMA",
  "name": "SUN PHARMACEUTICAL IND L",
  "last_fetch": "2026-06-14T14:30:40.915476Z"
}
```

```json
// File: collector/news/INE047A01021/metadata.json
{
  "isin": "INE047A01021",
  "symbol": "GRASIM",
  "name": "GRASIM INDUSTRIES LTD",
  "last_fetch": "2026-06-14T14:30:35.627392Z"
}
```

```json
// File: collector/news/INE04I401011/metadata.json
{
  "isin": "INE04I401011",
  "symbol": "KPITTECH",
  "name": "KPIT TECHNOLOGIES LIMITED",
  "last_fetch": "2026-06-14T14:30:41.143402Z"
}
```

```json
// File: collector/news/INE053A01029/metadata.json
{
  "isin": "INE053A01029",
  "symbol": "INDHOTEL",
  "name": "THE INDIAN HOTELS CO. LTD",
  "last_fetch": "2026-06-14T14:30:37.211643Z"
}
```

```json
// File: collector/news/INE053F01010/metadata.json
{
  "isin": "INE053F01010",
  "symbol": "IRFC",
  "name": "INDIAN RAILWAY FIN CORP L",
  "last_fetch": "2026-06-14T14:30:36.289487Z"
}
```

```json
// File: collector/news/INE059A01026/metadata.json
{
  "isin": "INE059A01026",
  "symbol": "CIPLA",
  "name": "CIPLA LTD",
  "last_fetch": "2026-06-14T14:30:39.888291Z"
}
```

```json
// File: collector/news/INE061F01013/metadata.json
{
  "isin": "INE061F01013",
  "symbol": "FORTIS",
  "name": "FORTIS HEALTHCARE LTD",
  "last_fetch": "2026-06-14T14:30:41.177800Z"
}
```

```json
// File: collector/news/INE062A01020/metadata.json
{
  "isin": "INE062A01020",
  "symbol": "SBIN",
  "name": "STATE BANK OF INDIA",
  "last_fetch": "2026-06-14T14:30:34.907595Z"
}
```

```json
// File: collector/news/INE066A01021/metadata.json
{
  "isin": "INE066A01021",
  "symbol": "EICHERMOT",
  "name": "EICHER MOTORS LTD",
  "last_fetch": "2026-06-14T14:30:38.779575Z"
}
```

```json
// File: collector/news/INE066F01020/metadata.json
{
  "isin": "INE066F01020",
  "symbol": "HAL",
  "name": "HINDUSTAN AERONAUTICS LTD",
  "last_fetch": "2026-06-14T14:30:37.674493Z"
}
```

```json
// File: collector/news/INE066P01011/metadata.json
{
  "isin": "INE066P01011",
  "symbol": "INOXWIND",
  "name": "INOX WIND LIMITED",
  "last_fetch": "2026-06-14T14:30:35.914535Z"
}
```

```json
// File: collector/news/INE067A01029/metadata.json
{
  "isin": "INE067A01029",
  "symbol": "CGPOWER",
  "name": "CG POWER AND IND SOL LTD",
  "last_fetch": "2026-06-14T14:30:43.504888Z"
}
```

```json
// File: collector/news/INE070A01015/metadata.json
{
  "isin": "INE070A01015",
  "symbol": "SHREECEM",
  "name": "SHREE CEMENT LIMITED",
  "last_fetch": "2026-06-14T14:30:33.181478Z"
}
```

```json
// File: collector/news/INE073K01018/metadata.json
{
  "isin": "INE073K01018",
  "symbol": "SONACOMS",
  "name": "SONA BLW PRECISION FRGS L",
  "last_fetch": "2026-06-14T14:30:34.713209Z"
}
```

```json
// File: collector/news/INE075A01022/metadata.json
{
  "isin": "INE075A01022",
  "symbol": "WIPRO",
  "name": "WIPRO LTD",
  "last_fetch": "2026-06-14T14:30:35.064042Z"
}
```

```json
// File: collector/news/INE079A01024/metadata.json
{
  "isin": "INE079A01024",
  "symbol": "AMBUJACEM",
  "name": "AMBUJA CEMENTS LTD",
  "last_fetch": "2026-06-14T14:30:37.693867Z"
}
```

```json
// File: collector/news/INE07Y701011/metadata.json
{
  "isin": "INE07Y701011",
  "symbol": "POWERINDIA",
  "name": "HITACHI ENERGY INDIA LTD",
  "last_fetch": "2026-06-14T14:30:43.648730Z"
}
```

```json
// File: collector/news/INE081A01020/metadata.json
{
  "isin": "INE081A01020",
  "symbol": "TATASTEEL",
  "name": "TATA STEEL LIMITED",
  "last_fetch": "2026-06-14T14:30:34.924062Z"
}
```

```json
// File: collector/news/INE084A01016/metadata.json
{
  "isin": "INE084A01016",
  "symbol": "BANKINDIA",
  "name": "BANK OF INDIA",
  "last_fetch": "2026-06-14T14:30:41.530132Z"
}
```

```json
// File: collector/news/INE089A01031/metadata.json
{
  "isin": "INE089A01031",
  "symbol": "DRREDDY",
  "name": "DR. REDDY S LABORATORIES",
  "last_fetch": "2026-06-14T14:30:39.240758Z"
}
```

```json
// File: collector/news/INE090A01021/metadata.json
{
  "isin": "INE090A01021",
  "symbol": "ICICIBANK",
  "name": "ICICI BANK LTD.",
  "last_fetch": "2026-06-14T14:30:43.191029Z"
}
```

```json
// File: collector/news/INE092T01019/metadata.json
{
  "isin": "INE092T01019",
  "symbol": "IDFCFIRSTB",
  "name": "IDFC FIRST BANK LIMITED",
  "last_fetch": "2026-06-14T14:30:43.363608Z"
}
```

```json
// File: collector/news/INE093I01010/metadata.json
{
  "isin": "INE093I01010",
  "symbol": "OBEROIRLTY",
  "name": "OBEROI REALTY LIMITED",
  "last_fetch": "2026-06-14T14:30:34.699098Z"
}
```

```json
// File: collector/news/INE094A01015/metadata.json
{
  "isin": "INE094A01015",
  "symbol": "HINDPETRO",
  "name": "HINDUSTAN PETROLEUM CORP",
  "last_fetch": "2026-06-14T14:30:34.524465Z"
}
```

```json
// File: collector/news/INE095A01012/metadata.json
{
  "isin": "INE095A01012",
  "symbol": "INDUSINDBK",
  "name": "INDUSIND BANK LIMITED",
  "last_fetch": "2026-06-14T14:30:40.535677Z"
}
```

```json
// File: collector/news/INE095N01031/metadata.json
{
  "isin": "INE095N01031",
  "symbol": "NBCC",
  "name": "NBCC (INDIA) LIMITED",
  "last_fetch": "2026-06-14T14:30:37.673743Z"
}
```

```json
// File: collector/news/INE0BS701011/metadata.json
{
  "isin": "INE0BS701011",
  "symbol": "PREMIERENE",
  "name": "PREMIER ENERGIES LIMITED",
  "last_fetch": "2026-06-14T14:30:41.333827Z"
}
```

```json
// File: collector/news/INE0J1Y01017/metadata.json
{
  "isin": "INE0J1Y01017",
  "symbol": "LICI",
  "name": "LIFE INSURA CORP OF INDIA",
  "last_fetch": "2026-06-14T14:30:38.283199Z"
}
```

```json
// File: collector/news/INE0V6F01027/metadata.json
{
  "isin": "INE0V6F01027",
  "symbol": "HYUNDAI",
  "name": "HYUNDAI MOTOR INDIA LTD",
  "last_fetch": "2026-06-14T14:30:39.647088Z"
}
```

```json
// File: collector/news/INE101A01026/metadata.json
{
  "isin": "INE101A01026",
  "symbol": "M&M",
  "name": "MAHINDRA & MAHINDRA LTD",
  "last_fetch": "2026-06-14T14:30:42.787996Z"
}
```

```json
// File: collector/news/INE102D01028/metadata.json
{
  "isin": "INE102D01028",
  "symbol": "GODREJCP",
  "name": "GODREJ CONSUMER PRODUCTS",
  "last_fetch": "2026-06-14T14:30:33.531904Z"
}
```

```json
// File: collector/news/INE111A01025/metadata.json
{
  "isin": "INE111A01025",
  "symbol": "CONCOR",
  "name": "CONTAINER CORP OF IND LTD",
  "last_fetch": "2026-06-14T14:30:34.908866Z"
}
```

```json
// File: collector/news/INE114A01011/metadata.json
{
  "isin": "INE114A01011",
  "symbol": "SAIL",
  "name": "STEEL AUTHORITY OF INDIA",
  "last_fetch": "2026-06-14T14:30:43.934504Z"
}
```

```json
// File: collector/news/INE115A01026/metadata.json
{
  "isin": "INE115A01026",
  "symbol": "LICHSGFIN",
  "name": "LIC HOUSING FINANCE LTD",
  "last_fetch": "2026-06-14T14:30:36.921472Z"
}
```

```json
// File: collector/news/INE117A01022/metadata.json
{
  "isin": "INE117A01022",
  "symbol": "ABB",
  "name": "ABB INDIA LIMITED",
  "last_fetch": "2026-06-14T14:30:37.003912Z"
}
```

```json
// File: collector/news/INE118A01012/metadata.json
{
  "isin": "INE118A01012",
  "symbol": "BAJAJHLDNG",
  "name": "BAJAJ HOLDINGS & INVS LTD",
  "last_fetch": "2026-06-14T14:30:34.483688Z"
}
```

```json
// File: collector/news/INE118H01025/metadata.json
{
  "isin": "INE118H01025",
  "symbol": "BSE",
  "name": "BSE LIMITED",
  "last_fetch": "2026-06-14T14:30:40.670252Z"
}
```

```json
// File: collector/news/INE121A01024/metadata.json
{
  "isin": "INE121A01024",
  "symbol": "CHOLAFIN",
  "name": "CHOLAMANDALAM IN & FIN CO",
  "last_fetch": "2026-06-14T14:30:35.313742Z"
}
```

```json
// File: collector/news/INE121E01018/metadata.json
{
  "isin": "INE121E01018",
  "symbol": "JSWENERGY",
  "name": "JSW ENERGY LIMITED",
  "last_fetch": "2026-06-14T14:30:37.435324Z"
}
```

```json
// File: collector/news/INE121J01017/metadata.json
{
  "isin": "INE121J01017",
  "symbol": "INDUSTOWER",
  "name": "INDUS TOWERS LIMITED",
  "last_fetch": "2026-06-14T14:30:37.370300Z"
}
```

```json
// File: collector/news/INE123W01016/metadata.json
{
  "isin": "INE123W01016",
  "symbol": "SBILIFE",
  "name": "SBI LIFE INSURANCE CO LTD",
  "last_fetch": "2026-06-14T14:30:34.376126Z"
}
```

```json
// File: collector/news/INE127D01025/metadata.json
{
  "isin": "INE127D01025",
  "symbol": "HDFCAMC",
  "name": "HDFC AMC LIMITED",
  "last_fetch": "2026-06-14T14:30:36.760906Z"
}
```

```json
// File: collector/news/INE129A01019/metadata.json
{
  "isin": "INE129A01019",
  "symbol": "GAIL",
  "name": "GAIL (INDIA) LTD",
  "last_fetch": "2026-06-14T14:30:43.824895Z"
}
```

```json
// File: collector/news/INE134E01011/metadata.json
{
  "isin": "INE134E01011",
  "symbol": "PFC",
  "name": "POWER FIN CORP LTD.",
  "last_fetch": "2026-06-14T14:30:33.819992Z"
}
```

```json
// File: collector/news/INE138Y01010/metadata.json
{
  "isin": "INE138Y01010",
  "symbol": "KFINTECH",
  "name": "KFIN TECHNOLOGIES LIMITED",
  "last_fetch": "2026-06-14T14:30:39.944349Z"
}
```

```json
// File: collector/news/INE139A01034/metadata.json
{
  "isin": "INE139A01034",
  "symbol": "NATIONALUM",
  "name": "NATIONAL ALUMINIUM CO LTD",
  "last_fetch": "2026-06-14T14:30:32.971486Z"
}
```

```json
// File: collector/news/INE148I01020/metadata.json
{
  "isin": "INE148I01020",
  "symbol": "SAMMAANCAP",
  "name": "SAMMAAN CAPITAL LIMITED",
  "last_fetch": "2026-06-14T14:30:40.231610Z"
}
```

```json
// File: collector/news/INE148O01028/metadata.json
{
  "isin": "INE148O01028",
  "symbol": "DELHIVERY",
  "name": "DELHIVERY LIMITED",
  "last_fetch": "2026-06-14T14:30:42.847703Z"
}
```

```json
// File: collector/news/INE154A01025/metadata.json
{
  "isin": "INE154A01025",
  "symbol": "ITC",
  "name": "ITC LTD",
  "last_fetch": "2026-06-14T14:30:42.544163Z"
}
```

```json
// File: collector/news/INE155A01022/metadata.json
{
  "isin": "INE155A01022",
  "symbol": "TMPV",
  "name": "TATA MOTORS PASS VEH LTD",
  "last_fetch": "2026-06-14T14:30:40.370568Z"
}
```

```json
// File: collector/news/INE158A01026/metadata.json
{
  "isin": "INE158A01026",
  "symbol": "HEROMOTOCO",
  "name": "HERO MOTOCORP LIMITED",
  "last_fetch": "2026-06-14T14:30:34.354689Z"
}
```

```json
// File: collector/news/INE160A01022/metadata.json
{
  "isin": "INE160A01022",
  "symbol": "PNB",
  "name": "PUNJAB NATIONAL BANK",
  "last_fetch": "2026-06-14T14:30:33.422633Z"
}
```

```json
// File: collector/news/INE171A01029/metadata.json
{
  "isin": "INE171A01029",
  "symbol": "FEDERALBNK",
  "name": "FEDERAL BANK LTD",
  "last_fetch": "2026-06-14T14:30:41.477842Z"
}
```

```json
// File: collector/news/INE171Z01026/metadata.json
{
  "isin": "INE171Z01026",
  "symbol": "BDL",
  "name": "BHARAT DYNAMICS LIMITED",
  "last_fetch": "2026-06-14T14:30:33.288286Z"
}
```

```json
// File: collector/news/INE176B01034/metadata.json
{
  "isin": "INE176B01034",
  "symbol": "HAVELLS",
  "name": "HAVELLS INDIA LIMITED",
  "last_fetch": "2026-06-14T14:30:42.250245Z"
}
```

```json
// File: collector/news/INE180A01020/metadata.json
{
  "isin": "INE180A01020",
  "symbol": "MFSL",
  "name": "MAX FINANCIAL SERV LTD",
  "last_fetch": "2026-06-14T14:30:40.105887Z"
}
```

```json
// File: collector/news/INE192A01025/metadata.json
{
  "isin": "INE192A01025",
  "symbol": "TATACONSUM",
  "name": "TATA CONSUMER PRODUCT LTD",
  "last_fetch": "2026-06-14T14:30:34.430618Z"
}
```

```json
// File: collector/news/INE192R01011/metadata.json
{
  "isin": "INE192R01011",
  "symbol": "DMART",
  "name": "AVENUE SUPERMARTS LIMITED",
  "last_fetch": "2026-06-14T14:30:39.862061Z"
}
```

```json
// File: collector/news/INE195A01028/metadata.json
{
  "isin": "INE195A01028",
  "symbol": "SUPREMEIND",
  "name": "SUPREME INDUSTRIES LTD",
  "last_fetch": "2026-06-14T14:30:40.656171Z"
}
```

```json
// File: collector/news/INE196A01026/metadata.json
{
  "isin": "INE196A01026",
  "symbol": "MARICO",
  "name": "MARICO LIMITED",
  "last_fetch": "2026-06-14T14:30:37.229703Z"
}
```

```json
// File: collector/news/INE200A01026/metadata.json
{
  "isin": "INE200A01026",
  "symbol": "GVT&D",
  "name": "GE VERNOVA T&D INDIA LTD",
  "last_fetch": "2026-06-14T14:30:38.393860Z"
}
```

```json
// File: collector/news/INE200M01039/metadata.json
{
  "isin": "INE200M01039",
  "symbol": "VBL",
  "name": "VARUN BEVERAGES LIMITED",
  "last_fetch": "2026-06-14T14:30:44.246244Z"
}
```

```json
// File: collector/news/INE202E01016/metadata.json
{
  "isin": "INE202E01016",
  "symbol": "IREDA",
  "name": "INDIAN RENEWABLE ENERGY",
  "last_fetch": "2026-06-14T14:30:38.783025Z"
}
```

```json
// File: collector/news/INE205A01025/metadata.json
{
  "isin": "INE205A01025",
  "symbol": "VEDL",
  "name": "VEDANTA LIMITED",
  "last_fetch": "2026-06-14T14:30:35.159453Z"
}
```

```json
// File: collector/news/INE208A01029/metadata.json
{
  "isin": "INE208A01029",
  "symbol": "ASHOKLEY",
  "name": "ASHOK LEYLAND LTD",
  "last_fetch": "2026-06-14T14:30:42.746765Z"
}
```

```json
// File: collector/news/INE211B01039/metadata.json
{
  "isin": "INE211B01039",
  "symbol": "PHOENIXLTD",
  "name": "THE PHOENIX MILLS LTD",
  "last_fetch": "2026-06-14T14:30:35.811323Z"
}
```

```json
// File: collector/news/INE213A01029/metadata.json
{
  "isin": "INE213A01029",
  "symbol": "ONGC",
  "name": "OIL AND NATURAL GAS CORP.",
  "last_fetch": "2026-06-14T14:30:35.934854Z"
}
```

```json
// File: collector/news/INE214T01019/metadata.json
{
  "isin": "INE214T01019",
  "symbol": "LTM",
  "name": "LTM LIMITED",
  "last_fetch": "2026-06-14T14:30:42.215415Z"
}
```

```json
// File: collector/news/INE216A01030/metadata.json
{
  "isin": "INE216A01030",
  "symbol": "BRITANNIA",
  "name": "BRITANNIA INDUSTRIES LTD",
  "last_fetch": "2026-06-14T14:30:34.901629Z"
}
```

```json
// File: collector/news/INE226A01021/metadata.json
{
  "isin": "INE226A01021",
  "symbol": "VOLTAS",
  "name": "VOLTAS LTD",
  "last_fetch": "2026-06-14T14:30:35.395328Z"
}
```

```json
// File: collector/news/INE237A01036/metadata.json
{
  "isin": "INE237A01036",
  "symbol": "KOTAKBANK",
  "name": "KOTAK MAHINDRA BANK LTD",
  "last_fetch": "2026-06-14T14:30:34.037169Z"
}
```

```json
// File: collector/news/INE238A01034/metadata.json
{
  "isin": "INE238A01034",
  "symbol": "AXISBANK",
  "name": "AXIS BANK LIMITED",
  "last_fetch": "2026-06-14T14:30:40.732858Z"
}
```

```json
// File: collector/news/INE239A01024/metadata.json
{
  "isin": "INE239A01024",
  "symbol": "NESTLEIND",
  "name": "NESTLE INDIA LIMITED",
  "last_fetch": "2026-06-14T14:30:37.149731Z"
}
```

```json
// File: collector/news/INE242A01010/metadata.json
{
  "isin": "INE242A01010",
  "symbol": "IOC",
  "name": "INDIAN OIL CORP LTD",
  "last_fetch": "2026-06-14T14:30:41.043912Z"
}
```

```json
// File: collector/news/INE245A01021/metadata.json
{
  "isin": "INE245A01021",
  "symbol": "TATAPOWER",
  "name": "TATA POWER CO LTD",
  "last_fetch": "2026-06-14T14:30:37.176491Z"
}
```

```json
// File: collector/news/INE249Z01020/metadata.json
{
  "isin": "INE249Z01020",
  "symbol": "MAZDOCK",
  "name": "MAZAGON DOCK SHIPBUIL LTD",
  "last_fetch": "2026-06-14T14:30:42.322465Z"
}
```

```json
// File: collector/news/INE257A01026/metadata.json
{
  "isin": "INE257A01026",
  "symbol": "BHEL",
  "name": "BHEL",
  "last_fetch": "2026-06-14T14:30:43.725725Z"
}
```

```json
// File: collector/news/INE259A01022/metadata.json
{
  "isin": "INE259A01022",
  "symbol": "COLPAL",
  "name": "COLGATE PALMOLIVE LTD.",
  "last_fetch": "2026-06-14T14:30:43.624036Z"
}
```

```json
// File: collector/news/INE260B01028/metadata.json
{
  "isin": "INE260B01028",
  "symbol": "GODFRYPHLP",
  "name": "GODFREY PHILLIPS INDIA LT",
  "last_fetch": "2026-06-14T14:30:38.222050Z"
}
```

```json
// File: collector/news/INE262H01021/metadata.json
{
  "isin": "INE262H01021",
  "symbol": "PERSISTENT",
  "name": "PERSISTENT SYSTEMS LTD",
  "last_fetch": "2026-06-14T14:30:41.505093Z"
}
```

```json
// File: collector/news/INE263A01024/metadata.json
{
  "isin": "INE263A01024",
  "symbol": "BEL",
  "name": "BHARAT ELECTRONICS LTD",
  "last_fetch": "2026-06-14T14:30:41.187687Z"
}
```

```json
// File: collector/news/INE267A01025/metadata.json
{
  "isin": "INE267A01025",
  "symbol": "HINDZINC",
  "name": "HINDUSTAN ZINC LIMITED",
  "last_fetch": "2026-06-14T14:30:33.179523Z"
}
```

```json
// File: collector/news/INE271C01023/metadata.json
{
  "isin": "INE271C01023",
  "symbol": "DLF",
  "name": "DLF LIMITED",
  "last_fetch": "2026-06-14T14:30:38.943228Z"
}
```

```json
// File: collector/news/INE274J01014/metadata.json
{
  "isin": "INE274J01014",
  "symbol": "OIL",
  "name": "OIL INDIA LTD",
  "last_fetch": "2026-06-14T14:30:40.461060Z"
}
```

```json
// File: collector/news/INE280A01028/metadata.json
{
  "isin": "INE280A01028",
  "symbol": "TITAN",
  "name": "TITAN COMPANY LIMITED",
  "last_fetch": "2026-06-14T14:30:40.331782Z"
}
```

```json
// File: collector/news/INE296A01032/metadata.json
{
  "isin": "INE296A01032",
  "symbol": "BAJFINANCE",
  "name": "BAJAJ FINANCE LIMITED",
  "last_fetch": "2026-06-14T14:30:37.472535Z"
}
```

```json
// File: collector/news/INE298A01020/metadata.json
{
  "isin": "INE298A01020",
  "symbol": "CUMMINSIND",
  "name": "CUMMINS INDIA LTD",
  "last_fetch": "2026-06-14T14:30:40.318254Z"
}
```

```json
// File: collector/news/INE298J01013/metadata.json
{
  "isin": "INE298J01013",
  "symbol": "NAM-INDIA",
  "name": "NIPPON L I A M LTD",
  "last_fetch": "2026-06-14T14:30:35.232956Z"
}
```

```json
// File: collector/news/INE299U01018/metadata.json
{
  "isin": "INE299U01018",
  "symbol": "CROMPTON",
  "name": "CROMPT GREA CON ELEC LTD",
  "last_fetch": "2026-06-14T14:30:38.222860Z"
}
```

```json
// File: collector/news/INE302A01020/metadata.json
{
  "isin": "INE302A01020",
  "symbol": "EXIDEIND",
  "name": "EXIDE INDUSTRIES LTD",
  "last_fetch": "2026-06-14T14:30:39.694703Z"
}
```

```json
// File: collector/news/INE303R01014/metadata.json
{
  "isin": "INE303R01014",
  "symbol": "KALYANKJIL",
  "name": "KALYAN JEWELLERS IND LTD",
  "last_fetch": "2026-06-14T14:30:42.769810Z"
}
```

```json
// File: collector/news/INE318A01026/metadata.json
{
  "isin": "INE318A01026",
  "symbol": "PIDILITIND",
  "name": "PIDILITE INDUSTRIES LTD",
  "last_fetch": "2026-06-14T14:30:39.016875Z"
}
```

```json
// File: collector/news/INE323A01026/metadata.json
{
  "isin": "INE323A01026",
  "symbol": "BOSCHLTD",
  "name": "BOSCH LIMITED",
  "last_fetch": "2026-06-14T14:30:42.063138Z"
}
```

```json
// File: collector/news/INE326A01037/metadata.json
{
  "isin": "INE326A01037",
  "symbol": "LUPIN",
  "name": "LUPIN LIMITED",
  "last_fetch": "2026-06-14T14:30:35.675953Z"
}
```

```json
// File: collector/news/INE338I01027/metadata.json
{
  "isin": "INE338I01027",
  "symbol": "MOTILALOFS",
  "name": "MOTILAL OSWAL FIN LTD",
  "last_fetch": "2026-06-14T14:30:40.943602Z"
}
```

```json
// File: collector/news/INE343H01029/metadata.json
{
  "isin": "INE343H01029",
  "symbol": "SOLARINDS",
  "name": "SOLAR INDUSTRIES (I) LTD",
  "last_fetch": "2026-06-14T14:30:42.334468Z"
}
```

```json
// File: collector/news/INE347G01014/metadata.json
{
  "isin": "INE347G01014",
  "symbol": "PETRONET",
  "name": "PETRONET LNG LIMITED",
  "last_fetch": "2026-06-14T14:30:43.401391Z"
}
```

```json
// File: collector/news/INE356A01018/metadata.json
{
  "isin": "INE356A01018",
  "symbol": "MPHASIS",
  "name": "MPHASIS LIMITED",
  "last_fetch": "2026-06-14T14:30:36.800769Z"
}
```

```json
// File: collector/news/INE361B01024/metadata.json
{
  "isin": "INE361B01024",
  "symbol": "DIVISLAB",
  "name": "DIVI S LABORATORIES LTD",
  "last_fetch": "2026-06-14T14:30:34.021958Z"
}
```

```json
// File: collector/news/INE364U01010/metadata.json
{
  "isin": "INE364U01010",
  "symbol": "ADANIGREEN",
  "name": "ADANI GREEN ENERGY LTD",
  "last_fetch": "2026-06-14T14:30:40.721996Z"
}
```

```json
// File: collector/news/INE371P01015/metadata.json
{
  "isin": "INE371P01015",
  "symbol": "AMBER",
  "name": "AMBER ENTERPRISES (I) LTD",
  "last_fetch": "2026-06-14T14:30:38.581900Z"
}
```

```json
// File: collector/news/INE376G01013/metadata.json
{
  "isin": "INE376G01013",
  "symbol": "BIOCON",
  "name": "BIOCON LIMITED.",
  "last_fetch": "2026-06-14T14:30:33.847454Z"
}
```

```json
// File: collector/news/INE377N01017/metadata.json
{
  "isin": "INE377N01017",
  "symbol": "WAAREEENER",
  "name": "WAAREE ENERGIES LIMITED",
  "last_fetch": "2026-06-14T14:30:35.158437Z"
}
```

```json
// File: collector/news/INE388Y01029/metadata.json
{
  "isin": "INE388Y01029",
  "symbol": "NYKAA",
  "name": "FSN E COMMERCE VENTURES",
  "last_fetch": "2026-06-14T14:30:36.982855Z"
}
```

```json
// File: collector/news/INE397D01024/metadata.json
{
  "isin": "INE397D01024",
  "symbol": "BHARTIARTL",
  "name": "BHARTI AIRTEL LIMITED",
  "last_fetch": "2026-06-14T14:30:39.867869Z"
}
```

```json
// File: collector/news/INE405E01023/metadata.json
{
  "isin": "INE405E01023",
  "symbol": "UNOMINDA",
  "name": "UNO MINDA LIMITED",
  "last_fetch": "2026-06-14T14:30:34.376436Z"
}
```

```json
// File: collector/news/INE406A01037/metadata.json
{
  "isin": "INE406A01037",
  "symbol": "AUROPHARMA",
  "name": "AUROBINDO PHARMA LTD",
  "last_fetch": "2026-06-14T14:30:42.730382Z"
}
```

```json
// File: collector/news/INE414G01012/metadata.json
{
  "isin": "INE414G01012",
  "symbol": "MUTHOOTFIN",
  "name": "MUTHOOT FINANCE LIMITED",
  "last_fetch": "2026-06-14T14:30:35.663732Z"
}
```

```json
// File: collector/news/INE415G01027/metadata.json
{
  "isin": "INE415G01027",
  "symbol": "RVNL",
  "name": "RAIL VIKAS NIGAM LIMITED",
  "last_fetch": "2026-06-14T14:30:42.017002Z"
}
```

```json
// File: collector/news/INE417T01026/metadata.json
{
  "isin": "INE417T01026",
  "symbol": "POLICYBZR",
  "name": "PB FINTECH LIMITED",
  "last_fetch": "2026-06-14T14:30:41.938908Z"
}
```

```json
// File: collector/news/INE423A01024/metadata.json
{
  "isin": "INE423A01024",
  "symbol": "ADANIENT",
  "name": "ADANI ENTERPRISES LIMITED",
  "last_fetch": "2026-06-14T14:30:43.646231Z"
}
```

```json
// File: collector/news/INE437A01024/metadata.json
{
  "isin": "INE437A01024",
  "symbol": "APOLLOHOSP",
  "name": "APOLLO HOSPITALS ENTER. L",
  "last_fetch": "2026-06-14T14:30:37.168057Z"
}
```

```json
// File: collector/news/INE451A01017/metadata.json
{
  "isin": "INE451A01017",
  "symbol": "FORCEMOT",
  "name": "FORCE MOTORS LIMITED",
  "last_fetch": "2026-06-14T14:30:39.520202Z"
}
```

```json
// File: collector/news/INE455K01017/metadata.json
{
  "isin": "INE455K01017",
  "symbol": "POLYCAB",
  "name": "POLYCAB INDIA LIMITED",
  "last_fetch": "2026-06-14T14:30:42.649108Z"
}
```

```json
// File: collector/news/INE457L01029/metadata.json
{
  "isin": "INE457L01029",
  "symbol": "PGEL",
  "name": "PG ELECTROPLAST LTD",
  "last_fetch": "2026-06-14T14:30:38.160006Z"
}
```

```json
// File: collector/news/INE465A01025/metadata.json
{
  "isin": "INE465A01025",
  "symbol": "BHARATFORG",
  "name": "BHARAT FORGE LTD",
  "last_fetch": "2026-06-14T14:30:33.970328Z"
}
```

```json
// File: collector/news/INE466L01038/metadata.json
{
  "isin": "INE466L01038",
  "symbol": "360ONE",
  "name": "360 ONE WAM LIMITED",
  "last_fetch": "2026-06-14T14:30:33.163751Z"
}
```

```json
// File: collector/news/INE467B01029/metadata.json
{
  "isin": "INE467B01029",
  "symbol": "TCS",
  "name": "TATA CONSULTANCY SERV LT",
  "last_fetch": "2026-06-14T14:30:37.726708Z"
}
```

```json
// File: collector/news/INE472A01039/metadata.json
{
  "isin": "INE472A01039",
  "symbol": "BLUESTARCO",
  "name": "BLUE STAR LIMITED",
  "last_fetch": "2026-06-14T14:30:43.280178Z"
}
```

```json
// File: collector/news/INE476A01022/metadata.json
{
  "isin": "INE476A01022",
  "symbol": "CANBK",
  "name": "CANARA BANK",
  "last_fetch": "2026-06-14T14:30:34.218211Z"
}
```

```json
// File: collector/news/INE481G01011/metadata.json
{
  "isin": "INE481G01011",
  "symbol": "ULTRACEMCO",
  "name": "ULTRATECH CEMENT LIMITED",
  "last_fetch": "2026-06-14T14:30:43.933552Z"
}
```

```json
// File: collector/news/INE484J01027/metadata.json
{
  "isin": "INE484J01027",
  "symbol": "GODREJPROP",
  "name": "GODREJ PROPERTIES LTD",
  "last_fetch": "2026-06-14T14:30:35.142373Z"
}
```

```json
// File: collector/news/INE494B01023/metadata.json
{
  "isin": "INE494B01023",
  "symbol": "TVSMOTOR",
  "name": "TVS MOTOR COMPANY  LTD",
  "last_fetch": "2026-06-14T14:30:39.618815Z"
}
```

```json
// File: collector/news/INE498L01015/metadata.json
{
  "isin": "INE498L01015",
  "symbol": "LTF",
  "name": "L&T FINANCE LIMITED",
  "last_fetch": "2026-06-14T14:30:35.076116Z"
}
```

```json
// File: collector/news/INE522D01027/metadata.json
{
  "isin": "INE522D01027",
  "symbol": "MANAPPURAM",
  "name": "MANAPPURAM FINANCE LTD",
  "last_fetch": "2026-06-14T14:30:32.970368Z"
}
```

```json
// File: collector/news/INE522F01014/metadata.json
{
  "isin": "INE522F01014",
  "symbol": "COALINDIA",
  "name": "COAL INDIA LTD",
  "last_fetch": "2026-06-14T14:30:37.460025Z"
}
```

```json
// File: collector/news/INE528G01035/metadata.json
{
  "isin": "INE528G01035",
  "symbol": "YESBANK",
  "name": "YES BANK LIMITED",
  "last_fetch": "2026-06-14T14:30:34.526877Z"
}
```

```json
// File: collector/news/INE531F01023/metadata.json
{
  "isin": "INE531F01023",
  "symbol": "NUVAMA",
  "name": "NUVAMA WEALTH MANAGE LTD",
  "last_fetch": "2026-06-14T14:30:34.611879Z"
}
```

```json
// File: collector/news/INE540L01014/metadata.json
{
  "isin": "INE540L01014",
  "symbol": "ALKEM",
  "name": "ALKEM LABORATORIES LTD.",
  "last_fetch": "2026-06-14T14:30:34.021304Z"
}
```

```json
// File: collector/news/INE545U01014/metadata.json
{
  "isin": "INE545U01014",
  "symbol": "BANDHANBNK",
  "name": "BANDHAN BANK LIMITED",
  "last_fetch": "2026-06-14T14:30:42.255651Z"
}
```

```json
// File: collector/news/INE562A01011/metadata.json
{
  "isin": "INE562A01011",
  "symbol": "INDIANB",
  "name": "INDIAN BANK",
  "last_fetch": "2026-06-14T14:30:40.650571Z"
}
```

```json
// File: collector/news/INE572E01012/metadata.json
{
  "isin": "INE572E01012",
  "symbol": "PNBHOUSING",
  "name": "PNB HOUSING FIN LTD.",
  "last_fetch": "2026-06-14T14:30:40.495948Z"
}
```

```json
// File: collector/news/INE584A01023/metadata.json
{
  "isin": "INE584A01023",
  "symbol": "NMDC",
  "name": "NMDC LTD.",
  "last_fetch": "2026-06-14T14:30:35.667186Z"
}
```

```json
// File: collector/news/INE585B01010/metadata.json
{
  "isin": "INE585B01010",
  "symbol": "MARUTI",
  "name": "MARUTI SUZUKI INDIA LTD.",
  "last_fetch": "2026-06-14T14:30:32.991195Z"
}
```

```json
// File: collector/news/INE591G01025/metadata.json
{
  "isin": "INE591G01025",
  "symbol": "COFORGE",
  "name": "COFORGE LIMITED",
  "last_fetch": "2026-06-14T14:30:33.412032Z"
}
```

```json
// File: collector/news/INE596I01020/metadata.json
{
  "isin": "INE596I01020",
  "symbol": "CAMS",
  "name": "COMPUTER AGE MNGT SER LTD",
  "last_fetch": "2026-06-14T14:30:36.922365Z"
}
```

```json
// File: collector/news/INE603J01030/metadata.json
{
  "isin": "INE603J01030",
  "symbol": "PIIND",
  "name": "PI INDUSTRIES LTD",
  "last_fetch": "2026-06-14T14:30:38.743892Z"
}
```

```json
// File: collector/news/INE619A01035/metadata.json
{
  "isin": "INE619A01035",
  "symbol": "PATANJALI",
  "name": "PATANJALI FOODS LIMITED",
  "last_fetch": "2026-06-14T14:30:33.850582Z"
}
```

```json
// File: collector/news/INE628A01036/metadata.json
{
  "isin": "INE628A01036",
  "symbol": "UPL",
  "name": "UPL LIMITED",
  "last_fetch": "2026-06-14T14:30:43.242688Z"
}
```

```json
// File: collector/news/INE634S01028/metadata.json
{
  "isin": "INE634S01028",
  "symbol": "MANKIND",
  "name": "MANKIND PHARMA LIMITED",
  "last_fetch": "2026-06-14T14:30:39.784765Z"
}
```

```json
// File: collector/news/INE646L01027/metadata.json
{
  "isin": "INE646L01027",
  "symbol": "INDIGO",
  "name": "INTERGLOBE AVIATION LTD",
  "last_fetch": "2026-06-14T14:30:39.667245Z"
}
```

```json
// File: collector/news/INE647A01010/metadata.json
{
  "isin": "INE647A01010",
  "symbol": "SRF",
  "name": "SRF LTD",
  "last_fetch": "2026-06-14T14:30:41.316174Z"
}
```

```json
// File: collector/news/INE663F01032/metadata.json
{
  "isin": "INE663F01032",
  "symbol": "NAUKRI",
  "name": "INFO EDGE (I) LTD",
  "last_fetch": "2026-06-14T14:30:38.793091Z"
}
```

```json
// File: collector/news/INE669C01036/metadata.json
{
  "isin": "INE669C01036",
  "symbol": "TECHM",
  "name": "TECH MAHINDRA LIMITED",
  "last_fetch": "2026-06-14T14:30:34.881322Z"
}
```

```json
// File: collector/news/INE669E01016/metadata.json
{
  "isin": "INE669E01016",
  "symbol": "IDEA",
  "name": "VODAFONE IDEA LIMITED",
  "last_fetch": "2026-06-14T14:30:35.691634Z"
}
```

```json
// File: collector/news/INE670A01012/metadata.json
{
  "isin": "INE670A01012",
  "symbol": "TATAELXSI",
  "name": "TATA ELXSI LIMITED",
  "last_fetch": "2026-06-14T14:30:44.297272Z"
}
```

```json
// File: collector/news/INE670K01029/metadata.json
{
  "isin": "INE670K01029",
  "symbol": "LODHA",
  "name": "LODHA DEVELOPERS LIMITED",
  "last_fetch": "2026-06-14T14:30:34.320262Z"
}
```

```json
// File: collector/news/INE674K01013/metadata.json
{
  "isin": "INE674K01013",
  "symbol": "ABCAPITAL",
  "name": "ADITYA BIRLA CAPITAL LTD.",
  "last_fetch": "2026-06-14T14:30:34.492360Z"
}
```

```json
// File: collector/news/INE685A01028/metadata.json
{
  "isin": "INE685A01028",
  "symbol": "TORNTPHARM",
  "name": "TORRENT PHARMACEUTICALS L",
  "last_fetch": "2026-06-14T14:30:41.256865Z"
}
```

```json
// File: collector/news/INE692A01016/metadata.json
{
  "isin": "INE692A01016",
  "symbol": "UNIONBANK",
  "name": "UNION BANK OF INDIA",
  "last_fetch": "2026-06-14T14:30:41.094211Z"
}
```

```json
// File: collector/news/INE702C01027/metadata.json
{
  "isin": "INE702C01027",
  "symbol": "APLAPOLLO",
  "name": "APL APOLLO TUBES LTD",
  "last_fetch": "2026-06-14T14:30:36.996042Z"
}
```

```json
// File: collector/news/INE704P01025/metadata.json
{
  "isin": "INE704P01025",
  "symbol": "COCHINSHIP",
  "name": "COCHIN SHIPYARD LIMITED",
  "last_fetch": "2026-06-14T14:30:35.978999Z"
}
```

```json
// File: collector/news/INE721A01047/metadata.json
{
  "isin": "INE721A01047",
  "symbol": "SHRIRAMFIN",
  "name": "SHRIRAM FINANCE LIMITED",
  "last_fetch": "2026-06-14T14:30:34.227571Z"
}
```

```json
// File: collector/news/INE726G01019/metadata.json
{
  "isin": "INE726G01019",
  "symbol": "ICICIPRULI",
  "name": "ICICI PRU LIFE INS CO LTD",
  "last_fetch": "2026-06-14T14:30:34.713970Z"
}
```

```json
// File: collector/news/INE732I01021/metadata.json
{
  "isin": "INE732I01021",
  "symbol": "ANGELONE",
  "name": "ANGEL ONE LIMITED",
  "last_fetch": "2026-06-14T14:30:39.887547Z"
}
```

```json
// File: collector/news/INE733E01010/metadata.json
{
  "isin": "INE733E01010",
  "symbol": "NTPC",
  "name": "NTPC LTD",
  "last_fetch": "2026-06-14T14:30:36.829698Z"
}
```

```json
// File: collector/news/INE736A01011/metadata.json
{
  "isin": "INE736A01011",
  "symbol": "CDSL",
  "name": "CENTRAL DEPO SER (I) LTD",
  "last_fetch": "2026-06-14T14:30:33.403820Z"
}
```

```json
// File: collector/news/INE742F01042/metadata.json
{
  "isin": "INE742F01042",
  "symbol": "ADANIPORTS",
  "name": "ADANI PORT & SEZ LTD",
  "last_fetch": "2026-06-14T14:30:35.403860Z"
}
```

```json
// File: collector/news/INE745G01043/metadata.json
{
  "isin": "INE745G01043",
  "symbol": "MCX",
  "name": "MULTI COMMODITY EXCHANGE",
  "last_fetch": "2026-06-14T14:30:34.199548Z"
}
```

```json
// File: collector/news/INE749A01030/metadata.json
{
  "isin": "INE749A01030",
  "symbol": "JINDALSTEL",
  "name": "JINDAL STEEL LIMITED",
  "last_fetch": "2026-06-14T14:30:33.275780Z"
}
```

```json
// File: collector/news/INE752E01010/metadata.json
{
  "isin": "INE752E01010",
  "symbol": "POWERGRID",
  "name": "POWER GRID CORP. LTD.",
  "last_fetch": "2026-06-14T14:30:38.790262Z"
}
```

```json
// File: collector/news/INE758E01017/metadata.json
{
  "isin": "INE758E01017",
  "symbol": "JIOFIN",
  "name": "JIO FIN SERVICES LTD",
  "last_fetch": "2026-06-14T14:30:39.149144Z"
}
```

```json
// File: collector/news/INE758T01015/metadata.json
{
  "isin": "INE758T01015",
  "symbol": "ETERNAL",
  "name": "ETERNAL LIMITED",
  "last_fetch": "2026-06-14T14:30:42.473855Z"
}
```

```json
// File: collector/news/INE761H01022/metadata.json
{
  "isin": "INE761H01022",
  "symbol": "PAGEIND",
  "name": "PAGE INDUSTRIES LTD",
  "last_fetch": "2026-06-14T14:30:39.428116Z"
}
```

```json
// File: collector/news/INE765G01017/metadata.json
{
  "isin": "INE765G01017",
  "symbol": "ICICIGI",
  "name": "ICICI LOMBARD GIC LIMITED",
  "last_fetch": "2026-06-14T14:30:43.737982Z"
}
```

```json
// File: collector/news/INE775A01035/metadata.json
{
  "isin": "INE775A01035",
  "symbol": "MOTHERSON",
  "name": "SAMVRDHNA MTHRSN INTL LTD",
  "last_fetch": "2026-06-14T14:30:41.763057Z"
}
```

```json
// File: collector/news/INE776C01039/metadata.json
{
  "isin": "INE776C01039",
  "symbol": "GMRAIRPORT",
  "name": "GMR AIRPORTS LIMITED",
  "last_fetch": "2026-06-14T14:30:41.869023Z"
}
```

```json
// File: collector/news/INE795G01014/metadata.json
{
  "isin": "INE795G01014",
  "symbol": "HDFCLIFE",
  "name": "HDFC LIFE INS CO LTD",
  "last_fetch": "2026-06-14T14:30:40.214279Z"
}
```

```json
// File: collector/news/INE797F01020/metadata.json
{
  "isin": "INE797F01020",
  "symbol": "JUBLFOOD",
  "name": "JUBILANT FOODWORKS LTD",
  "last_fetch": "2026-06-14T14:30:40.008865Z"
}
```

```json
// File: collector/news/INE811K01011/metadata.json
{
  "isin": "INE811K01011",
  "symbol": "PRESTIGE",
  "name": "PRESTIGE ESTATE LTD",
  "last_fetch": "2026-06-14T14:30:34.022913Z"
}
```

```json
// File: collector/news/INE814H01029/metadata.json
{
  "isin": "INE814H01029",
  "symbol": "ADANIPOWER",
  "name": "ADANI POWER LTD",
  "last_fetch": "2026-06-14T14:30:33.535060Z"
}
```

```json
// File: collector/news/INE848E01016/metadata.json
{
  "isin": "INE848E01016",
  "symbol": "NHPC",
  "name": "NHPC LTD",
  "last_fetch": "2026-06-14T14:30:39.273525Z"
}
```

```json
// File: collector/news/INE849A01020/metadata.json
{
  "isin": "INE849A01020",
  "symbol": "TRENT",
  "name": "TRENT LTD",
  "last_fetch": "2026-06-14T14:30:34.767952Z"
}
```

```json
// File: collector/news/INE854D01024/metadata.json
{
  "isin": "INE854D01024",
  "symbol": "UNITDSPR",
  "name": "UNITED SPIRITS LIMITED",
  "last_fetch": "2026-06-14T14:30:35.332470Z"
}
```

```json
// File: collector/news/INE860A01027/metadata.json
{
  "isin": "INE860A01027",
  "symbol": "HCLTECH",
  "name": "HCL TECHNOLOGIES LTD",
  "last_fetch": "2026-06-14T14:30:41.363729Z"
}
```

```json
// File: collector/news/INE878B01027/metadata.json
{
  "isin": "INE878B01027",
  "symbol": "KEI",
  "name": "KEI INDUSTRIES LTD.",
  "last_fetch": "2026-06-14T14:30:41.933549Z"
}
```

```json
// File: collector/news/INE881D01027/metadata.json
{
  "isin": "INE881D01027",
  "symbol": "OFSS",
  "name": "ORACLE FIN SERV SOFT LTD.",
  "last_fetch": "2026-06-14T14:30:40.143230Z"
}
```

```json
// File: collector/news/INE917I01010/metadata.json
{
  "isin": "INE917I01010",
  "symbol": "BAJAJ-AUTO",
  "name": "BAJAJ AUTO LIMITED",
  "last_fetch": "2026-06-14T14:30:33.102485Z"
}
```

```json
// File: collector/news/INE918I01026/metadata.json
{
  "isin": "INE918I01026",
  "symbol": "BAJAJFINSV",
  "name": "BAJAJ FINSERV LTD.",
  "last_fetch": "2026-06-14T14:30:39.089786Z"
}
```

```json
// File: collector/news/INE918Z01012/metadata.json
{
  "isin": "INE918Z01012",
  "symbol": "KAYNES",
  "name": "KAYNES TECHNOLOGY IND LTD",
  "last_fetch": "2026-06-14T14:30:33.840263Z"
}
```

```json
// File: collector/news/INE931S01010/metadata.json
{
  "isin": "INE931S01010",
  "symbol": "ADANIENSOL",
  "name": "ADANI ENERGY SOLUTION LTD",
  "last_fetch": "2026-06-14T14:30:36.059493Z"
}
```

```json
// File: collector/news/INE935A01035/metadata.json
{
  "isin": "INE935A01035",
  "symbol": "GLENMARK",
  "name": "GLENMARK PHARMACEUTICALS",
  "last_fetch": "2026-06-14T14:30:43.998312Z"
}
```

```json
// File: collector/news/INE935N01020/metadata.json
{
  "isin": "INE935N01020",
  "symbol": "DIXON",
  "name": "DIXON TECHNO (INDIA) LTD",
  "last_fetch": "2026-06-14T14:30:40.997542Z"
}
```

```json
// File: collector/news/INE944F01028/metadata.json
{
  "isin": "INE944F01028",
  "symbol": "RADICO",
  "name": "RADICO KHAITAN LTD",
  "last_fetch": "2026-06-14T14:30:40.443498Z"
}
```

```json
// File: collector/news/INE947Q01028/metadata.json
{
  "isin": "INE947Q01028",
  "symbol": "LAURUSLABS",
  "name": "LAURUS LABS LIMITED",
  "last_fetch": "2026-06-14T14:30:39.077105Z"
}
```

```json
// File: collector/news/INE949L01017/metadata.json
{
  "isin": "INE949L01017",
  "symbol": "AUBANK",
  "name": "AU SMALL FINANCE BANK LTD",
  "last_fetch": "2026-06-14T14:30:40.106978Z"
}
```

```json
// File: collector/news/INE974X01010/metadata.json
{
  "isin": "INE974X01010",
  "symbol": "TIINDIA",
  "name": "TUBE INVEST OF INDIA LTD",
  "last_fetch": "2026-06-14T14:30:35.340419Z"
}
```

```json
// File: collector/news/INE976G01028/metadata.json
{
  "isin": "INE976G01028",
  "symbol": "RBLBANK",
  "name": "RBL BANK LIMITED",
  "last_fetch": "2026-06-14T14:30:41.476698Z"
}
```

```json
// File: collector/news/INE982J01020/metadata.json
{
  "isin": "INE982J01020",
  "symbol": "PAYTM",
  "name": "ONE 97 COMMUNICATIONS LTD",
  "last_fetch": "2026-06-14T14:30:39.494387Z"
}
```

```jsonl
// File: news/holdings.jsonl

```

```jsonl
// File: news/instruments/INE002A01018.jsonl
{"isin":"INE002A01018","instrumentKey":"NSE_EQ|INE002A01018","heading":"SENSEX, NIFTY50 erase gains to end on a flat note dragged by Reliance, Bharti Airtel","summary":"Selling pressure was broad based as 12 of 15 major sector gauges compiled by the National Stock Exchange (NSE) ended lower led by the NIFTY Media index's over 2% fall.","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-may-20-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-nifty-50-erase-gains-to-end-on-a-flat-note-dragged-by-reliance-bharti-airtel/article-195118/","publishedTime":1781087106142,"sourceHash":"8d3ed2066d2f2e40cae3146567edf2b8cc93070a3d2551634fb7603b438a03db"}
{"isin":"INE002A01018","instrumentKey":"NSE_EQ|INE002A01018","heading":"NIFTY50 above 23,375, SENSEX up 518 pts in noon deals; RIL, Clean Max Enviro, CMR Green Tech among buzzing stocks","summary":"Shares of non-ferrous metal recycler CMR Green Technologies Ltd made a robust debut on the stock exchanges on Wednesday","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-june-10.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-above-23-375-sensex-up-518-pts-in-noon-deals-ril-clean-max-enviro-cmr-green-tech-among-buzzing-stocks/article-195104/","publishedTime":1781077374640,"sourceHash":"61fa2e668299e8d6049543c18beaeeb53b1c82a4db5bcb9b2d8dfc720ce56ca5"}
{"isin":"INE002A01018","instrumentKey":"NSE_EQ|INE002A01018","heading":"Reliance Industries shares rally on AI data centre deal with Meta; what it means for the company and India","summary":"Reliance will build a data center with 168 MW capacity in Jamnagar, Gujarat, which Meta will lease, with options to scale, news reports said.","thumbnail":"https://assets.upstox.com/content/assets/images/news/ril-data-centre.webp","articleLink":"https://upstox.com/news/market-news/stocks/reliance-industries-shares-in-focus-as-meta-partners-for-ai-enabled-data-centre-push-report/article-195069/","publishedTime":1781067052760,"sourceHash":"526176edfafb15e1e308b616a428f46ddad7091afe490b57f337ec9c77c0a733"}
{"isin":"INE002A01018","instrumentKey":"NSE_EQ|INE002A01018","heading":"Stocks to watch, June 10: Afcons Infra, NLC India, Ajanta Pharma, Emcure Pharma, Adani Green, oil-sensitives","summary":"ONGC, Oil India, and downstream companies' stocks, such as Indian Oil Corporation, HPCL, BPCL, will be in focus as oil prices rose on Wednesday after the US launched military strikes against Iran, raising concerns that renewed hostilities could threaten shipping through the Strait of Hormuz.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-wednesday.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-10-afcons-infra-nlc-india-ajanta-pharma-emcure-pharma-adani-green-oil-sensitives/article-195063/","publishedTime":1781060155207,"sourceHash":"6fd129a4155414c3e72c831eb8cdaca0e4584778c1ff93f3a8fd47fbba5eec98"}
{"isin":"INE002A01018","instrumentKey":"NSE_EQ|INE002A01018","heading":"Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE","summary":"NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.","thumbnail":"https://assets.upstox.com/content/assets/images/news/reliancetcswiprohit52weeklow.webp","articleLink":"https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/","publishedTime":1780918365081,"sourceHash":"d25a0966a57e19ddd7ff8f63cbcc3f415b032033ca306956ba9875cddb7d48c7"}
```

```jsonl
// File: news/instruments/INE006I01046.jsonl
{"isin":"INE006I01046","instrumentKey":"NSE_EQ|INE006I01046","heading":"Astral shares jump over 2% as subsidiary buys 60% in DSS worth ₹39 crore; check details","summary":"DSS is the only entity in India to possess a technology to produce a wide range of polyamines and very unique bismaleimides and benzoxazines (specialty chemicals)","thumbnail":"https://assets.upstox.com/content/assets/images/news/astral-june-11.webp","articleLink":"https://upstox.com/news/market-news/stocks/astral-shares-in-focus-as-subsidiary-buys-60-in-dss-worth-39-crore-check-details/article-195202/","publishedTime":1781239380279,"sourceHash":"3063cc3400d6f8b49896deaa30fcc99e57eb5a07504df76f031d60721acafaa1"}
```

```jsonl
// File: news/instruments/INE009A01021.jsonl
{"isin":"INE009A01021","instrumentKey":"BSE_EQ|INE009A01021","heading":"Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list","summary":"On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/market-wrap-april-9.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/","publishedTime":1781175583927,"sourceHash":"2386d018edd12f95cb9e4767657b93c1fe060b93ffefe15fa92b65cc08df1379"}
{"isin":"INE009A01021","instrumentKey":"BSE_EQ|INE009A01021","heading":"TCS, Infosys, Tech Mahindra, other IT shares fall amid AI stocks sell-off on Wall Street","summary":"Wall Street has been on shaky ground since last week, as artificial intelligence stocks have traded lower after reaching record highs.","thumbnail":"https://assets.upstox.com/content/assets/images/news/it-stocks-ai-sell-off-wall-street.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-infosys-tech-mahindra-other-it-stocks-fall-amid-ai-stocks-sell-off-on-wall-street/article-195152/","publishedTime":1781154186328,"sourceHash":"4c893884b27e1d0dec902957a3fad07dd753d4942f67c0b24a708c908570ae9a"}
{"isin":"INE009A01021","instrumentKey":"BSE_EQ|INE009A01021","heading":"TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump’s $100,000 H-1B visa fee rule","summary":"A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.","thumbnail":"https://assets.upstox.com/content/assets/images/news/it-stocks-june-09-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/","publishedTime":1780978356295,"sourceHash":"88547509c42c0805cc1222667a0e95d8f2bc1b172668b63b83291838c9450fc4"}
{"isin":"INE009A01021","instrumentKey":"BSE_EQ|INE009A01021","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
```

```jsonl
// File: news/instruments/INE00H001014.jsonl
{"isin":"INE00H001014","instrumentKey":"NSE_EQ|INE00H001014","heading":"Zepto vs Blinkit vs Instamart: Which quick commerce platform leads in revenue and profitability ahead of Zepto IPO?","summary":"Zepto IPO has moved one step closer to its market debut after the company filed its updated DRHP with SEBI. As Zepto prepares to enter the public markets, here’s a comparative look at how the company stacks up against its listed quick commerce peers, Eternal’s Blinkit and Swiggy’s Instamart, across key metrics such as revenue, profitability, and dark stores.","thumbnail":"https://assets.upstox.com/content/assets/images/news/zeptoipogmp.webp","articleLink":"https://upstox.com/news/market-news/ipo/zepto-vs-blinkit-vs-instamart-which-quick-commerce-platform-leads-in-revenue-and-profitability-ahead-of-zepto-ipo/article-195149/","publishedTime":1781153861680,"sourceHash":"e3259ceba7e3dbc624152f5be59c94abc749568aba20e0f2ba8a830d00cb981a"}
{"isin":"INE00H001014","instrumentKey":"NSE_EQ|INE00H001014","heading":"Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE","summary":"NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.","thumbnail":"https://assets.upstox.com/content/assets/images/news/reliancetcswiprohit52weeklow.webp","articleLink":"https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/","publishedTime":1780918365081,"sourceHash":"d25a0966a57e19ddd7ff8f63cbcc3f415b032033ca306956ba9875cddb7d48c7"}
```

```jsonl
// File: news/instruments/INE00R701025.jsonl
{"isin":"INE00R701025","instrumentKey":"BSE_EQ|INE00R701025","heading":"Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE","summary":"NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.","thumbnail":"https://assets.upstox.com/content/assets/images/news/reliancetcswiprohit52weeklow.webp","articleLink":"https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/","publishedTime":1780918365081,"sourceHash":"d25a0966a57e19ddd7ff8f63cbcc3f415b032033ca306956ba9875cddb7d48c7"}
```

```jsonl
// File: news/instruments/INE016A01026.jsonl
{"isin":"INE016A01026","instrumentKey":"NSE_EQ|INE016A01026","heading":"Dabur India shares in focus as US FDA issues import alert for Silvassa manufacturing plant","summary":"Dabur India, in its filing to stock exchanges on Thursday, said that the US FDA had inspected the company’s manufacturing plant situated at Silvassa, Dadra and Nagar Haveli, and had identified certain deficiencies on account of data integrity and maintenance lapses.","thumbnail":"https://assets.upstox.com/content/assets/images/news/dabur-india-shares-june-12-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/dabur-india-shares-in-focus-as-us-fda-issues-import-alert-for-silvassa-manufacturing-plant/article-195222/","publishedTime":1781237178241,"sourceHash":"28a79478b0606c73069a446836ceab8859ac2aa7327fcb5832d05508aa49450c"}
{"isin":"INE016A01026","instrumentKey":"NSE_EQ|INE016A01026","heading":"Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics","summary":"Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of ₹175 crore through open market transactions.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-june-12.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/","publishedTime":1781232986902,"sourceHash":"818d3cda34ea569ac92c98159ea06fe67e9a3a2ebc30eaae26b00e42dec46a91"}
```

```jsonl
// File: news/instruments/INE018A01030.jsonl
{"isin":"INE018A01030","instrumentKey":"BSE_EQ|INE018A01030","heading":"Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list","summary":"On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/gainers-and-losers-may-20-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/","publishedTime":1781262446006,"sourceHash":"8e20b492437a2eb98c5f2e888610a20784ad1143d869cf3cf951157774bd0672"}
{"isin":"INE018A01030","instrumentKey":"BSE_EQ|INE018A01030","heading":"SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%","summary":"NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/sterlite-techmtarstockrise.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/","publishedTime":1781264551315,"sourceHash":"362e2a33e0519980dbb144e87564af7974220addfe67b331f5319a8285f6764e"}
{"isin":"INE018A01030","instrumentKey":"BSE_EQ|INE018A01030","heading":"L&T shares rise nearly 4% amid renewed hopes of US-Iran peace deal","summary":"L&T shares had hit its 52-week high of ₹4,440 per share on February 24, 2026, while its 52-week low of ₹3,288.10 apiece was recorded on March 23, 2026.","thumbnail":"https://assets.upstox.com/content/assets/images/news/lt-share-price-possible-us-iran-deal.webp","articleLink":"https://upstox.com/news/market-news/stocks/l-and-t-shares-rise-nearly-4-amid-renewed-hopes-of-us-iran-peace-deal/article-195229/","publishedTime":1781238512275,"sourceHash":"1acc7c450d2ad1d6fb831c6acc05a0c92322b1760870f916ed167e5f827f490b"}
{"isin":"INE018A01030","instrumentKey":"BSE_EQ|INE018A01030","heading":"NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains","summary":"At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/","publishedTime":1781237907785,"sourceHash":"1772a0e37cddbc4697a01c04d8f3b11ec261b6fd87b50c367ecd617b5b741133"}
```

```jsonl
// File: news/instruments/INE019A01038.jsonl
{"isin":"INE019A01038","instrumentKey":"BSE_EQ|INE019A01038","heading":"Redington, JSW Steel, DMart among buzzing stocks as SENSEX jumps over 270 pts, NIFTY above 23,200","summary":"Grasim Industries shares gained 2% on the NSE on Tuesday, June 9, as it said it will invest ₹3,094 crore for expanding its Lyocell capacity at Harihar, Karnataka, under phase II.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/market09june4214.webp","articleLink":"https://upstox.com/news/market-news/stocks/redington-jsw-steel-d-mart-among-buzzing-stocks-as-sensex-jumps-over-170-pts-nifty-above-23-200/article-195029/","publishedTime":1780992961868,"sourceHash":"b0c5c6b5da9ed451cda7339f971761600b4954a1262222cac005051791ef3b57"}
{"isin":"INE019A01038","instrumentKey":"BSE_EQ|INE019A01038","heading":"JSW Steel jumps 1% after 15% YoY growth in steel production","summary":"Production was higher in May 2026, mainly due to full operations of the Dolvi unit (one of the blast furnaces was under planned maintenance shutdown in May 2025), and JVML operations fully ramped up. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/jsw-steelq3222.webp","articleLink":"https://upstox.com/news/market-news/stocks/jsw-steel-jumps-1-after-15-yo-y-growth-in-steel-production/article-195017/","publishedTime":1780982888844,"sourceHash":"0178f919998e6173e6fdbe8f27eb05c122c799cd3bd680466fd609d7bfbf45e4"}
```

```jsonl
// File: news/instruments/INE020B01018.jsonl
{"isin":"INE020B01018","instrumentKey":"BSE_EQ|INE020B01018","heading":"TCS, Zee Ent, DOMS Industries among buzzing stocks as SENSEX gains over 200 pts, NIFTY above 23,200","summary":"Shares of Doms Industries climbed as much as 7.33% to hit an intraday high of ₹2,273.90 per unit on the NSE on Thursday, after the company entered into an agreement to buy Reynolds Pens India Limited.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-22-may.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-zee-ent-doms-industries-among-buzzing-stocks-as-sensex-gains-over-200-pts-nifty-above-23-200/article-195172/","publishedTime":1781163081692,"sourceHash":"68a8aff833eff1e988bebb9ddc11b6faf6a0f9e509996d1b1d8b2581be8a530d"}
{"isin":"INE020B01018","instrumentKey":"BSE_EQ|INE020B01018","heading":"Stocks to watch, June 11: REC, PFC, Eternal, Power Grid, Lenskart Solutions, Meesho, Zee Entertainment","summary":" Zomato and Blinkit's parent entity, Eternal, has received a ₹9.63 crore GST demand notice, along with interest and penalty, from the Andhra Pradesh tax authorities.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-june-11-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-11-2026-rec-pfc-eternal-power-grid-lenskart-solutions-meesho-zee-entertainment/article-195142/","publishedTime":1781145776128,"sourceHash":"9e1ab0cd5f0a59961dde64c34935e52e5bf7a7d0ee1d0004138277c7ad8ae1cd"}
{"isin":"INE020B01018","instrumentKey":"BSE_EQ|INE020B01018","heading":"REC receives presidential approval for proposed merger into PFC; what investors need to know","summary":"Earlier, on February 9, PFC’s board had given its in-principle approval for the merger of the Maharatna non-banking finance company REC with itself.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/pfcrecmerger11.webp","articleLink":"https://upstox.com/news/market-news/stocks/rec-receives-presidential-approval-for-proposed-merger-into-pfc-what-investors-need-to-know/article-195133/","publishedTime":1781161079014,"sourceHash":"df1e2d9853e8edeaeb707fe1bf21d64030b737d9bab6b2c74aebd166991057fa"}
```

```jsonl
// File: news/instruments/INE027H01010.jsonl
{"isin":"INE027H01010","instrumentKey":"NSE_EQ|INE027H01010","heading":"Top gainers and losers, June 8: Wipro tumbles 8%, Jio Finserv, Eternal down 4%, Max Healthcare up 3%; check list","summary":"On June 8, the S&P BSE SENSEX closed 719.09 points, or 0.97% lower at 73,524.26. Meanwhile, NSE’s NIFTY50 ended at 23,123, reflecting a 243.70-point, or 1.04% decline.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/top-gainers-and-losers-may-29-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-8-wipro-tumbles-8-jio-finserv-eternal-down-4-max-healthcare-up-3-check-list/article-194980/","publishedTime":1780918146424,"sourceHash":"012e692bade01e45f5afa79802b9bded089e7cf7bd2a557366238dddb204c3a0"}
{"isin":"INE027H01010","instrumentKey":"NSE_EQ|INE027H01010","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
```

```jsonl
// File: news/instruments/INE028A01039.jsonl
{"isin":"INE028A01039","instrumentKey":"BSE_EQ|INE028A01039","heading":"Bank of Baroda, Canara Bank, Federal Bank shares rally; NIFTY Bank surges most in two months, here’s why","summary":"Banking stocks came under buying interest after the Reserve Bank of India (RBI) on Monday introduced a US dollar-rupee forex swap facility for fresh FCNR (B) deposits.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockpti4520180020-1.webp","articleLink":"https://upstox.com/news/market-news/stocks/bank-of-baroda-canara-bank-federal-bank-shares-rally-nifty-bank-surges-most-in-two-months-here-s-why/article-195034/","publishedTime":1780993166287,"sourceHash":"76d44e775571aac9491cc3080386657373691836b1560da32f12d64d31a64dc2"}
```

```jsonl
// File: news/instruments/INE029A01011.jsonl
{"isin":"INE029A01011","instrumentKey":"NSE_EQ|INE029A01011","heading":"Govt bars bulk industrial petrol, diesel purchases through retail fuel outlets","summary":"The order, issued under the Essential Commodities Act, aims to prevent diversion of supplies meant for retail consumers after authorities observed bulk buyers shifting to petrol pumps due to the lower retail prices compared with bulk rates.","thumbnail":"https://assets.upstox.com/content/assets/images/news/petroldieselwindfall-taxwebp.webp","articleLink":"https://upstox.com/news/business-news/latest-updates/govt-bars-bulk-industrial-petrol-diesel-purchases-through-retail-fuel-outlets/article-195216/","publishedTime":1781234130711,"sourceHash":"779baf1fbb87eba5adc849be7aa39d376d07ddf6307d24505f804264fb6f4ecb"}
```

```jsonl
// File: news/instruments/INE030A01027.jsonl
{"isin":"INE030A01027","instrumentKey":"BSE_EQ|INE030A01027","heading":"Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list","summary":"On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/top-gainers-and-losers-nifty50.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/","publishedTime":1781088957700,"sourceHash":"6218f24d6f1f4d29b3cce4b2e2f4f2c198b8528047e2aed7f8e36fee8193db3c"}
{"isin":"INE030A01027","instrumentKey":"BSE_EQ|INE030A01027","heading":"NIFTY50 above 23,375, SENSEX up 518 pts in noon deals; RIL, Clean Max Enviro, CMR Green Tech among buzzing stocks","summary":"Shares of non-ferrous metal recycler CMR Green Technologies Ltd made a robust debut on the stock exchanges on Wednesday","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-june-10.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-above-23-375-sensex-up-518-pts-in-noon-deals-ril-clean-max-enviro-cmr-green-tech-among-buzzing-stocks/article-195104/","publishedTime":1781077374640,"sourceHash":"61fa2e668299e8d6049543c18beaeeb53b1c82a4db5bcb9b2d8dfc720ce56ca5"}
{"isin":"INE030A01027","instrumentKey":"BSE_EQ|INE030A01027","heading":"Why did HUL share price jump over 3% today? Check JP Morgan and other analysts' views","summary":"Hindustan Unilever shares jumped more than 3% on June 10 as investors were shifting towards defensive stocks amid escalating geopolitical tensions and FMCG sectors' stable outlook for FY2027. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/hulq2resultupdate.webp","articleLink":"https://upstox.com/news/market-news/stocks/why-did-hul-share-price-jump-over-3-today-check-jp-morgan-and-other-analysts-views/article-195090/","publishedTime":1781071445298,"sourceHash":"bf2903fd66c7077b5aa260bf014f584a2aa4cfd845669fb1bed03bdbfc0e8efa"}
```

```jsonl
// File: news/instruments/INE038A01020.jsonl
{"isin":"INE038A01020","instrumentKey":"NSE_EQ|INE038A01020","heading":"Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list","summary":"On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/top-gainers-and-losers-nifty50.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/","publishedTime":1781088957700,"sourceHash":"6218f24d6f1f4d29b3cce4b2e2f4f2c198b8528047e2aed7f8e36fee8193db3c"}
{"isin":"INE038A01020","instrumentKey":"NSE_EQ|INE038A01020","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
{"isin":"INE038A01020","instrumentKey":"NSE_EQ|INE038A01020","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE040A01034.jsonl
{"isin":"INE040A01034","instrumentKey":"NSE_EQ|INE040A01034","heading":"SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%","summary":"NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/sterlite-techmtarstockrise.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/","publishedTime":1781264551315,"sourceHash":"362e2a33e0519980dbb144e87564af7974220addfe67b331f5319a8285f6764e"}
{"isin":"INE040A01034","instrumentKey":"NSE_EQ|INE040A01034","heading":"Dividend, bonus issue, stock splits next week: HDFC Bank, Tata Motors PV, Tata Tech, Torrent Power, among others in focus","summary":"Shares of HDFC Bank will trade ex-record date on Friday, June 19, for its final dividend. On April 18, the country's largest private sector bank had recommended a final dividend of ₹13 per equity share, with a face value of ₹1 each for FY26. \n","thumbnail":"https://assets.upstox.com/content/assets/images/news/dividend-alert-news-adani-enterprises-ports-tata-steel-motors-acc-trent-last-date.webp","articleLink":"https://upstox.com/news/market-news/stocks/dividend-bonus-issue-stock-splits-next-week-hdfc-bank-tata-motors-pv-tata-tech-torrent-power-among-others-in-focus/article-195253/","publishedTime":1781262401321,"sourceHash":"0a967c4f68f67b3e062bfa401d09219c73676ad5d57f56881ff3aa0652a58bc8"}
{"isin":"INE040A01034","instrumentKey":"NSE_EQ|INE040A01034","heading":"From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter","summary":"HDFC Bank reported net profit of ₹19,221 crore in Q4FY26, marking an increase of 8% from ₹17,616.14 crore in the year-ago period.","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-june-5.webp","articleLink":"https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/","publishedTime":1780987251866,"sourceHash":"f6c2d4f07fabca2511fa4579e91a56ec891aa83da0622ee38c6a14a8eb01cfbc"}
```

```jsonl
// File: news/instruments/INE040H01021.jsonl
{"isin":"INE040H01021","instrumentKey":"NSE_EQ|INE040H01021","heading":"Vikram Solar, Suzlon Energy, Waaree Energies: How renewable energy stocks are performing on June 9","summary":"In May 2026, Colliers India released a report, 'The Green Shift: Renewable Prioritisation Reshaping Indian Real Estate', wherein it said that India will need around 7 lakh acres of land parcels, estimated to cost ₹10-₹15 billion, in five years to set up solar and wind energy projects.","thumbnail":"https://assets.upstox.com/content/assets/images/news/green-energy-shares-june-9-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/vikram-solar-suzlon-energy-waaree-energies-how-renewable-energy-stocks-are-performing-on-june-9/article-195036/","publishedTime":1780994528822,"sourceHash":"5ec698c463d2a46f1154524a0515d49bc4520bb606b99b14b471c69b0b853ae2"}
```

```jsonl
// File: news/instruments/INE044A01036.jsonl
{"isin":"INE044A01036","instrumentKey":"NSE_EQ|INE044A01036","heading":"Sun Pharma dividend schedule: Board announces record date for ₹5/share final dividend issue","summary":"Sun Pharma announced the record date for its ₹5 per share final dividend issue for the financial year ended 2025-26. Here's what investors should know.","thumbnail":"https://assets.upstox.com/content/assets/images/news/sun-pharma-feb-5.webp","articleLink":"https://upstox.com/news/market-news/stocks/sun-pharma-dividend-schedule-board-announces-record-date-for-5-share-final-dividend-issue/article-195220/","publishedTime":1781233777283,"sourceHash":"ae78ad2c6f548ae48b607d74f829811253963311318b29e573d53ba43f372130"}
```

```jsonl
// File: news/instruments/INE047A01021.jsonl
{"isin":"INE047A01021","instrumentKey":"NSE_EQ|INE047A01021","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
```

```jsonl
// File: news/instruments/INE061F01013.jsonl
{"isin":"INE061F01013","instrumentKey":"NSE_EQ|INE061F01013","heading":"22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround","summary":"Vodafone Idea reported a net profit of ₹52,022 crore in March quarter compared with a loss of ₹7,268 crore in the year-ago period and loss of ₹5,324 crore in the previous quarter due to relief in statutory liabilities.","thumbnail":"https://assets.upstox.com/content/assets/images/news/market09june4214.webp","articleLink":"https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/","publishedTime":1781075957852,"sourceHash":"596ebd8b82c9627af55e917aeaf5c53eb7215884b5c5abd9372862ede2e13533"}
```

```jsonl
// File: news/instruments/INE062A01020.jsonl
{"isin":"INE062A01020","instrumentKey":"BSE_EQ|INE062A01020","heading":"BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details","summary":"Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-june-4.webp","articleLink":"https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/","publishedTime":1780906190587,"sourceHash":"9721ce6fd114c5ea2c24f25b6a1fdaf0d703f67a9986c8b14e9341816f1f772d"}
{"isin":"INE062A01020","instrumentKey":"BSE_EQ|INE062A01020","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE075A01022.jsonl
{"isin":"INE075A01022","instrumentKey":"BSE_EQ|INE075A01022","heading":"Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads","summary":"A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran","thumbnail":"https://assets.upstox.com/content/assets/images/news/weekly-market-wrap-june-13.webp","articleLink":"https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/","publishedTime":1781332567326,"sourceHash":"226915bb315e42261997c4820bb093ee77d055cba9dcff5b26b427f9ca61d1ea"}
{"isin":"INE075A01022","instrumentKey":"BSE_EQ|INE075A01022","heading":"Wipro buyback update: ₹15,000 crore share repurchase programme opens June 11; details here","summary":"June 4 was the last day for market participants to buy Wipro shares to be eligible for the share buyback programme.","thumbnail":"https://assets.upstox.com/content/assets/images/news/wipro-buyback-update-june-9-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/wipro-buyback-update-15-000-crore-share-repurchase-programme-opens-june-11-details-here/article-195016/","publishedTime":1780986966340,"sourceHash":"50eafd30ab7b5fa66c8325bc76d42bf97e0e95c1c83095e44f3b36004cf7bfe1"}
{"isin":"INE075A01022","instrumentKey":"BSE_EQ|INE075A01022","heading":"TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump’s $100,000 H-1B visa fee rule","summary":"A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.","thumbnail":"https://assets.upstox.com/content/assets/images/news/it-stocks-june-09-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/","publishedTime":1780978356295,"sourceHash":"88547509c42c0805cc1222667a0e95d8f2bc1b172668b63b83291838c9450fc4"}
{"isin":"INE075A01022","instrumentKey":"BSE_EQ|INE075A01022","heading":"Top gainers and losers, June 8: Wipro tumbles 8%, Jio Finserv, Eternal down 4%, Max Healthcare up 3%; check list","summary":"On June 8, the S&P BSE SENSEX closed 719.09 points, or 0.97% lower at 73,524.26. Meanwhile, NSE’s NIFTY50 ended at 23,123, reflecting a 243.70-point, or 1.04% decline.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/top-gainers-and-losers-may-29-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-8-wipro-tumbles-8-jio-finserv-eternal-down-4-max-healthcare-up-3-check-list/article-194980/","publishedTime":1780918146424,"sourceHash":"012e692bade01e45f5afa79802b9bded089e7cf7bd2a557366238dddb204c3a0"}
{"isin":"INE075A01022","instrumentKey":"BSE_EQ|INE075A01022","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
{"isin":"INE075A01022","instrumentKey":"BSE_EQ|INE075A01022","heading":"Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE","summary":"NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.","thumbnail":"https://assets.upstox.com/content/assets/images/news/reliancetcswiprohit52weeklow.webp","articleLink":"https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/","publishedTime":1780918365081,"sourceHash":"d25a0966a57e19ddd7ff8f63cbcc3f415b032033ca306956ba9875cddb7d48c7"}
{"isin":"INE075A01022","instrumentKey":"BSE_EQ|INE075A01022","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE07Y701011.jsonl
{"isin":"INE07Y701011","instrumentKey":"BSE_EQ|INE07Y701011","heading":"Hitachi Energy shares rise 4% on plans to invest ₹2,000 crore for Vadodara power transformer factory","summary":"The factory is scheduled for completion in FY28 and will manufacture a significant volume of power transformers annually to enable faster delivery of mission-critical grid equipment, Hitachi Energy India said. \n","thumbnail":"https://assets.upstox.com/content/assets/images/news/shutterstockhitachienergyindia.webp","articleLink":"https://upstox.com/news/market-news/stocks/hitachi-energy-shares-rise-4-on-plans-to-invest-2-000-crore-for-vadodara-power-transformer-factory/article-195246/","publishedTime":1781248424220,"sourceHash":"f0c3923c82b2afaeba241ea9c2c1cd5f01a701c3f6f36a2b10c7b00c4565b3c0"}
```

```jsonl
// File: news/instruments/INE081A01020.jsonl
{"isin":"INE081A01020","instrumentKey":"BSE_EQ|INE081A01020","heading":"Adani Enterprises, Tata Steel, PNB, Tata Motors, Trent dividend record date on June 12: Last day to buy for payout","summary":"Tata Steel has recommended a dividend of ₹4 per share of face value of ₹1 each to the shareholders of the company for FY2025-26. The record date is June 12.","thumbnail":"https://assets.upstox.com/content/assets/images/news/dividend-alert-news-adani-enterprises-ports-tata-steel-motors-acc-trent-last-date.webp","articleLink":"https://upstox.com/news/market-news/stocks/adani-enterprises-tata-steel-pnb-tata-motors-trent-dividend-record-date-on-june-12-last-day-to-buy-for-payout/article-195146/","publishedTime":1781166173232,"sourceHash":"c51620c9dab0825e3f1f2c967ab56045f3500f68a02984a0a6af60b82ba6c52a"}
{"isin":"INE081A01020","instrumentKey":"BSE_EQ|INE081A01020","heading":"HG Infra, Zee Ent, Rajesh Exports among buzzing stocks as SENSEX falls over 400 pts, NIFTY below 23,300","summary":"Shares of H.G. Infra Engineering rallied 10% on Monday, June 8, after it received the provisional completion certificate for executing the ₹4,970.99 crore Ganga Expressway project in Uttar Pradesh.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-june-8.webp","articleLink":"https://upstox.com/news/market-news/stocks/hg-infra-zee-ent-rajesh-exports-among-buzzing-stocks-as-sensex-falls-over-400-pts-nifty-below-23-300/article-194956/","publishedTime":1780903580292,"sourceHash":"e5c893c5612c087c20ed5b936fa4cd8048b8942ecb572c4cb950e3f45dbd312c"}
{"isin":"INE081A01020","instrumentKey":"BSE_EQ|INE081A01020","heading":"Tata Steel may defer UK low-carbon steel project by up to 8 months; shares slip 2%","summary":"As part of its decarbonisation plan, Tata Steel is setting up the UK's largest low-carbon EAF (electric arc furnace) project of 3.2 million tonnes capacity at Port Talbot with 1.25 billion pounds of investment to replace its now-shut blast furnace plant of similar capacity.","thumbnail":"https://assets.upstox.com/content/assets/images/news/tata-steel-shares-june-8-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/tata-steel-may-defer-uk-low-carbon-steel-project-by-up-to-8-months-amid-power-access-delays-shares-slip-2/article-194938/","publishedTime":1780891632666,"sourceHash":"a737a24d79a0105319b78fbf5e075c75c37c3480dc3d63d99129efa83e09d8e4"}
{"isin":"INE081A01020","instrumentKey":"BSE_EQ|INE081A01020","heading":"Stocks to watch, June 8: Rajesh Exports, OMCs, LIC, Avanti Feeds, Apex Frozen, ixigo, Tata Steel, ZEEL","summary":"Tata Steel may have to defer the timeline of its 1.25-billion-pound UK project for transitioning to a low-carbon steel-making process by six to eight months, as the company is facing delays in \"securing access to electricity\".","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-monday.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-8-rajesh-exports-om-cs-lic-avanti-feeds-apex-frozen-ixigo-tata-steel-zeel/article-194924/","publishedTime":1780886006796,"sourceHash":"d6e164241adc5518874be05e6de28cc8130418926ebbb1289a8d4ab840bf3098"}
```

```jsonl
// File: news/instruments/INE090A01021.jsonl
{"isin":"INE090A01021","instrumentKey":"NSE_EQ|INE090A01021","heading":"Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads","summary":"A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran","thumbnail":"https://assets.upstox.com/content/assets/images/news/weekly-market-wrap-june-13.webp","articleLink":"https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/","publishedTime":1781332567326,"sourceHash":"226915bb315e42261997c4820bb093ee77d055cba9dcff5b26b427f9ca61d1ea"}
{"isin":"INE090A01021","instrumentKey":"NSE_EQ|INE090A01021","heading":"Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list","summary":"On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/market-wrap-april-9.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/","publishedTime":1781175583927,"sourceHash":"2386d018edd12f95cb9e4767657b93c1fe060b93ffefe15fa92b65cc08df1379"}
```

```jsonl
// File: news/instruments/INE095A01012.jsonl
{"isin":"INE095A01012","instrumentKey":"NSE_EQ|INE095A01012","heading":"22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround","summary":"Vodafone Idea reported a net profit of ₹52,022 crore in March quarter compared with a loss of ₹7,268 crore in the year-ago period and loss of ₹5,324 crore in the previous quarter due to relief in statutory liabilities.","thumbnail":"https://assets.upstox.com/content/assets/images/news/market09june4214.webp","articleLink":"https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/","publishedTime":1781075957852,"sourceHash":"596ebd8b82c9627af55e917aeaf5c53eb7215884b5c5abd9372862ede2e13533"}
```

```jsonl
// File: news/instruments/INE0J1Y01017.jsonl
{"isin":"INE0J1Y01017","instrumentKey":"NSE_EQ|INE0J1Y01017","heading":"Stocks to watch, June 8: Rajesh Exports, OMCs, LIC, Avanti Feeds, Apex Frozen, ixigo, Tata Steel, ZEEL","summary":"Tata Steel may have to defer the timeline of its 1.25-billion-pound UK project for transitioning to a low-carbon steel-making process by six to eight months, as the company is facing delays in \"securing access to electricity\".","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-monday.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-8-rajesh-exports-om-cs-lic-avanti-feeds-apex-frozen-ixigo-tata-steel-zeel/article-194924/","publishedTime":1780886006796,"sourceHash":"d6e164241adc5518874be05e6de28cc8130418926ebbb1289a8d4ab840bf3098"}
```

```jsonl
// File: news/instruments/INE0V6F01027.jsonl
{"isin":"INE0V6F01027","instrumentKey":"BSE_EQ|INE0V6F01027","heading":"NIFTY50 above 23,375, SENSEX up 518 pts in noon deals; RIL, Clean Max Enviro, CMR Green Tech among buzzing stocks","summary":"Shares of non-ferrous metal recycler CMR Green Technologies Ltd made a robust debut on the stock exchanges on Wednesday","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-june-10.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-above-23-375-sensex-up-518-pts-in-noon-deals-ril-clean-max-enviro-cmr-green-tech-among-buzzing-stocks/article-195104/","publishedTime":1781077374640,"sourceHash":"61fa2e668299e8d6049543c18beaeeb53b1c82a4db5bcb9b2d8dfc720ce56ca5"}
{"isin":"INE0V6F01027","instrumentKey":"BSE_EQ|INE0V6F01027","heading":"Hyundai Motor India expects production at Chennai plant to normalise by June 22 after fire incident; here’s how the stock is faring","summary":"The carmaker expects that any loss of production arising due to the fire incident shall be mostly recovered within the next quarter itself","thumbnail":"https://assets.upstox.com/content/assets/images/news/hyundai-motor-india.webp","articleLink":"https://upstox.com/news/market-news/stocks/hyundai-motor-india-expects-production-at-chennai-plant-to-normalise-by-june-22-after-fire-incident-here-s-how-the-stock-in-faring/article-195095/","publishedTime":1781072929036,"sourceHash":"25f0dad1baa92ae82301115e05a670aa77a3d1f50be91d07fab8075ac6dd316e"}
{"isin":"INE0V6F01027","instrumentKey":"BSE_EQ|INE0V6F01027","heading":"Hyundai Motor India expects Chennai plant output to normalise by June 22 after supplier fire","summary":"Hyundai expects to recover most of the production losses within the next quarter and does not anticipate any significant impact on June retail sales due to adequate inventory across its dealer network.","thumbnail":"https://assets.upstox.com/content/assets/images/news/hyundai-motor-india.webp","articleLink":"https://upstox.com/news/business-news/latest-updates/hyundai-motor-india-expects-chennai-plant-output-to-normalise-by-june-22-after-supplier-fire/article-195091/","publishedTime":1781069990581,"sourceHash":"27684b316677a5de1c5a8cae46903eb71304ad1ecd4270c86fad203cd588dc96"}
```

```jsonl
// File: news/instruments/INE101A01026.jsonl
{"isin":"INE101A01026","instrumentKey":"NSE_EQ|INE101A01026","heading":"Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list","summary":"On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/market-wrap-april-9.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/","publishedTime":1781175583927,"sourceHash":"2386d018edd12f95cb9e4767657b93c1fe060b93ffefe15fa92b65cc08df1379"}
{"isin":"INE101A01026","instrumentKey":"NSE_EQ|INE101A01026","heading":"Mahindra & Mahindra shares recover 4% from intraday low; here's why the stock is buzzing on Thursday","summary":"After opening at ₹2,930 apiece, M&M shares had slipped nearly 2% to an intraday low of ₹2,900.40 apiece from its previous close","thumbnail":"https://assets.upstox.com/content/assets/images/news/mahindra-mahindra-june-11.webp","articleLink":"https://upstox.com/news/market-news/stocks/mahindra-and-mahindra-m-and-m-shares-recover-4-from-intraday-low-here-s-why-the-stock-is-buzzing-on-thursday/article-195183/","publishedTime":1781169153685,"sourceHash":"607ab5f442589b2a9673bca5a403376ef00b008bb5e8dbe088887f22ef8c0ee4"}
{"isin":"INE101A01026","instrumentKey":"NSE_EQ|INE101A01026","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE121E01018.jsonl
{"isin":"INE121E01018","instrumentKey":"NSE_EQ|INE121E01018","heading":"Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL","summary":"Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of ₹303 per share. The OFS opens for non-retail investors on Tuesday.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-tuesday.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/","publishedTime":1780975699014,"sourceHash":"7cb3e9365ebcc8ea852f5f8b1c759e8a689808885338130bb2404eb3120cb31d"}
```

```jsonl
// File: news/instruments/INE134E01011.jsonl
{"isin":"INE134E01011","instrumentKey":"BSE_EQ|INE134E01011","heading":"TCS, Zee Ent, DOMS Industries among buzzing stocks as SENSEX gains over 200 pts, NIFTY above 23,200","summary":"Shares of Doms Industries climbed as much as 7.33% to hit an intraday high of ₹2,273.90 per unit on the NSE on Thursday, after the company entered into an agreement to buy Reynolds Pens India Limited.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-22-may.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-zee-ent-doms-industries-among-buzzing-stocks-as-sensex-gains-over-200-pts-nifty-above-23-200/article-195172/","publishedTime":1781163081692,"sourceHash":"68a8aff833eff1e988bebb9ddc11b6faf6a0f9e509996d1b1d8b2581be8a530d"}
{"isin":"INE134E01011","instrumentKey":"BSE_EQ|INE134E01011","heading":"Stocks to watch, June 11: REC, PFC, Eternal, Power Grid, Lenskart Solutions, Meesho, Zee Entertainment","summary":" Zomato and Blinkit's parent entity, Eternal, has received a ₹9.63 crore GST demand notice, along with interest and penalty, from the Andhra Pradesh tax authorities.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-june-11-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-11-2026-rec-pfc-eternal-power-grid-lenskart-solutions-meesho-zee-entertainment/article-195142/","publishedTime":1781145776128,"sourceHash":"9e1ab0cd5f0a59961dde64c34935e52e5bf7a7d0ee1d0004138277c7ad8ae1cd"}
{"isin":"INE134E01011","instrumentKey":"BSE_EQ|INE134E01011","heading":"REC receives presidential approval for proposed merger into PFC; what investors need to know","summary":"Earlier, on February 9, PFC’s board had given its in-principle approval for the merger of the Maharatna non-banking finance company REC with itself.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/pfcrecmerger11.webp","articleLink":"https://upstox.com/news/market-news/stocks/rec-receives-presidential-approval-for-proposed-merger-into-pfc-what-investors-need-to-know/article-195133/","publishedTime":1781161079014,"sourceHash":"df1e2d9853e8edeaeb707fe1bf21d64030b737d9bab6b2c74aebd166991057fa"}
```

```jsonl
// File: news/instruments/INE155A01022.jsonl
{"isin":"INE155A01022","instrumentKey":"NSE_EQ|INE155A01022","heading":"SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%","summary":"NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/sterlite-techmtarstockrise.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/","publishedTime":1781264551315,"sourceHash":"362e2a33e0519980dbb144e87564af7974220addfe67b331f5319a8285f6764e"}
{"isin":"INE155A01022","instrumentKey":"NSE_EQ|INE155A01022","heading":"Dividend, bonus issue, stock splits next week: HDFC Bank, Tata Motors PV, Tata Tech, Torrent Power, among others in focus","summary":"Shares of HDFC Bank will trade ex-record date on Friday, June 19, for its final dividend. On April 18, the country's largest private sector bank had recommended a final dividend of ₹13 per equity share, with a face value of ₹1 each for FY26. \n","thumbnail":"https://assets.upstox.com/content/assets/images/news/dividend-alert-news-adani-enterprises-ports-tata-steel-motors-acc-trent-last-date.webp","articleLink":"https://upstox.com/news/market-news/stocks/dividend-bonus-issue-stock-splits-next-week-hdfc-bank-tata-motors-pv-tata-tech-torrent-power-among-others-in-focus/article-195253/","publishedTime":1781262401321,"sourceHash":"0a967c4f68f67b3e062bfa401d09219c73676ad5d57f56881ff3aa0652a58bc8"}
{"isin":"INE155A01022","instrumentKey":"NSE_EQ|INE155A01022","heading":"OMCs, Tata Motors PV, TCS among buzzing stocks as NIFTY up 1.4%, SENSEX climbs 1,202 pts in noon deals","summary":"From the NIFTY firms, Shriram Finance, InterGlobe Aviation, Bajaj Finance, L&T, HDFC Bank, Jio Financial Services Eternal, Titan, Tata Motors Passenger Vehicles and were among the biggest winners.","thumbnail":"https://assets.upstox.com/content/assets/images/news/nifty-sensex-buzzing-stocks-june-12-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/om-cs-tata-motors-pv-tcs-among-buzzing-stocks-as-nifty-up-0-77-sensex-climbs-729-pts-in-noon-deals/article-195250/","publishedTime":1781253125381,"sourceHash":"419f48ffbdd77b2da3a9041a50de9947223b29588088b8de094c60007c64d549"}
{"isin":"INE155A01022","instrumentKey":"NSE_EQ|INE155A01022","heading":"Tata Motors to hike passenger vehicle prices by up to 1.5% from July 1","summary":"Tata Motors Passenger Vehicles Ltd (TMPV) will increase prices of its passenger vehicle lineup, including both internal combustion engine (ICE) models and electric vehicles (EVs), by up to 1.5% from July 1, 2026.","thumbnail":"https://assets.upstox.com/content/assets/images/news/tata-motors-pv.webp","articleLink":"https://upstox.com/news/business-news/latest-updates/tata-motors-to-hike-passenger-vehicle-prices-by-up-to-1-5-from-july-1/article-195242/","publishedTime":1781248971838,"sourceHash":"99e63725344f594175b435341c527736f95033fed78e14aa82bcd930f144455e"}
{"isin":"INE155A01022","instrumentKey":"NSE_EQ|INE155A01022","heading":"NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains","summary":"At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/","publishedTime":1781237907785,"sourceHash":"1772a0e37cddbc4697a01c04d8f3b11ec261b6fd87b50c367ecd617b5b741133"}
```

```jsonl
// File: news/instruments/INE171A01029.jsonl
{"isin":"INE171A01029","instrumentKey":"NSE_EQ|INE171A01029","heading":"Stock market rally: Aegis Logistics, Cupid, Akums Drugs, JB Chemicals, among 47 stocks that hit 52-week high on NSE","summary":"Prominent stocks like Aegis Logistics, Cupid, and Akums Drugs were among several others which surged to their 52-week high amid the broader market rally on Friday, June 12. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/gmr-airports-share-price-jump-promoter.webp","articleLink":"https://upstox.com/news/market-news/stocks/stock-market-rally-aegis-logistics-cupid-akums-drugs-jb-chemicals-among-47-stocks-that-hit-52-week-high-on-nse/article-195245/","publishedTime":1781252951486,"sourceHash":"f18840576270a7f9035524082458f7e9a867d3f5bf3b0a7a5ec9df010c6ec5e8"}
{"isin":"INE171A01029","instrumentKey":"NSE_EQ|INE171A01029","heading":"Bank of Baroda, Canara Bank, Federal Bank shares rally; NIFTY Bank surges most in two months, here’s why","summary":"Banking stocks came under buying interest after the Reserve Bank of India (RBI) on Monday introduced a US dollar-rupee forex swap facility for fresh FCNR (B) deposits.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockpti4520180020-1.webp","articleLink":"https://upstox.com/news/market-news/stocks/bank-of-baroda-canara-bank-federal-bank-shares-rally-nifty-bank-surges-most-in-two-months-here-s-why/article-195034/","publishedTime":1780993166287,"sourceHash":"76d44e775571aac9491cc3080386657373691836b1560da32f12d64d31a64dc2"}
{"isin":"INE171A01029","instrumentKey":"NSE_EQ|INE171A01029","heading":"Cupid, Aster DM, Data Patterns, Belrise and Federal Bank among top stocks that made fresh record highs on Tuesday; here's why","summary":"Indian benchmark indices continued to hover near the neutral lines on Tuesday as the NIFTY50 jumped 30 points and the SENSEX traded near the 73,700 levels. Despite the broader consolidation in the markets, few stocks hit fresh record high levels.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-may-26-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/cupid-aster-dm-data-patterns-belrise-and-federal-bank-among-top-stocks-that-made-fresh-record-highs-on-tuesday-here-s-why/article-195033/","publishedTime":1780996523609,"sourceHash":"3829677898247e8462137360ee679e91fca7e1143f077c1e80b23aa8b0154d37"}
```

```jsonl
// File: news/instruments/INE192R01011.jsonl
{"isin":"INE192R01011","instrumentKey":"NSE_EQ|INE192R01011","heading":"Redington, JSW Steel, DMart among buzzing stocks as SENSEX jumps over 270 pts, NIFTY above 23,200","summary":"Grasim Industries shares gained 2% on the NSE on Tuesday, June 9, as it said it will invest ₹3,094 crore for expanding its Lyocell capacity at Harihar, Karnataka, under phase II.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/market09june4214.webp","articleLink":"https://upstox.com/news/market-news/stocks/redington-jsw-steel-d-mart-among-buzzing-stocks-as-sensex-jumps-over-170-pts-nifty-above-23-200/article-195029/","publishedTime":1780992961868,"sourceHash":"b0c5c6b5da9ed451cda7339f971761600b4954a1262222cac005051791ef3b57"}
{"isin":"INE192R01011","instrumentKey":"NSE_EQ|INE192R01011","heading":"Avenue Supermarts shares rise over 1% as company invests ₹150 crore in e-commerce arm","summary":"Avenue Supermarts reported a 19.18% year-on-year (YoY) surge in its consolidated net profit at ₹656.59 crore in the March quarter of the 2025-26 financial year (Q4 FY26).","thumbnail":"https://assets.upstox.com/content/assets/images/news/avenue-supermarts-dmart-q4-results-date-time-share-price.webp","articleLink":"https://upstox.com/news/market-news/stocks/avenue-supermarts-shares-in-focus-as-company-invests-150-crore-e-commerce-arm/article-195002/","publishedTime":1781006386749,"sourceHash":"ac8aa13bc7a5cb5cb100a0ae6c88c223d870c265e2911ba866780c2c568e55ce"}
{"isin":"INE192R01011","instrumentKey":"NSE_EQ|INE192R01011","heading":"Avenue Supermarts shares in focus after ₹150 crore investment in DMart Ready","summary":"DMart Ready operated in 18 cities as of FY26, as compared to 25 cities in the previous year. Despite the drop in operational cities and regions, the subsidiary’s revenue for the FY26 stood at ₹4,093 crore as compared to ₹3,502 crore in FY25 and ₹2,899 crore in FY24. The company aims to utilise the funds for working capital requirements and capex expenditure.","thumbnail":"https://assets.upstox.com/content/assets/images/news/avenue-supermarts-dmart-q4-results-date-time-share-price.webp","articleLink":"https://upstox.com/news/market-news/stocks/avenue-supermart-s-shares-in-focus-after-149-crore-investment-in-its-subsidiary-d-mart-ready/article-194992/","publishedTime":1780937205485,"sourceHash":"17168f4a11e53c15e5f2d6b5780eeb61cf32fe063ce4aa4773a2777df0a91b9e"}
```

```jsonl
// File: news/instruments/INE205A01025.jsonl
{"isin":"INE205A01025","instrumentKey":"NSE_EQ|INE205A01025","heading":"Vedanta demerger: Vedanta Oil and Gas to list on June 15; key details shareholders need to know","summary":"The equity shares of Vedanta Oil and Gas Ltd will begin trading on the NSE and BSE on June 15, 2026, as part of Vedanta Ltd's ongoing demerger exercise. The listing will create a separately traded oil and gas company, allowing investors to independently assess the business's operations, growth prospects, and valuation.","thumbnail":"https://assets.upstox.com/content/assets/images/news/vedanta-oil.webp","articleLink":"https://upstox.com/news/market-news/stocks/vedanta-demerger-vedanta-oil-and-gas-to-list-on-june-15-key-details-shareholders-need-to-know/article-195281/","publishedTime":1781409053613,"sourceHash":"f57fb15abeeb4205dd8dc300e1e0afaa94f1369cb6fee61f67f65a6ef68b52d8"}
{"isin":"INE205A01025","instrumentKey":"NSE_EQ|INE205A01025","heading":"Vedanta demerger: Four newly carved-out companies set for market debut on June 15; details to know","summary":"In their notices and circulars, both BSE and NSE stated on Thursday, that the four demerged arms, namely, Vedanta Oil and Gas Limited, Vedanta Power Limited, Vedanta Aluminium Metal Limited, and Vedanta Iron And Steel Limited, shall be listed on June 15, 2026.","thumbnail":"https://assets.upstox.com/content/assets/images/news/vedantasharejune-11.webp","articleLink":"https://upstox.com/news/market-news/stocks/vedanta-demerger-four-entities-to-list-on-june-15-what-shareholders-need-to-know/article-195209/","publishedTime":1781419291042,"sourceHash":"1a31f1ea78ac653a78b42220162a5b26716133531d588d660b4abbd30894a02e"}
{"isin":"INE205A01025","instrumentKey":"NSE_EQ|INE205A01025","heading":"Vedanta demerger: What shareholders should know before Vedanta Power starts trading","summary":"Vedanta demerger is aimed at unlocking shareholder value by creating pure-play businesses, allowing investors to gain targeted exposure to individual sectors such as power, aluminium, and other metals, instead of investing in Vedanta's diversified portfolio","thumbnail":"https://assets.upstox.com/content/assets/images/news/vedanta-power-site.webp","articleLink":"https://upstox.com/news/market-news/stocks/vedanta-demerger-what-shareholders-should-know-before-vedanta-power-starts-trading/article-195136/","publishedTime":1781108250005,"sourceHash":"4ce8ca562f81c620b46246272fbddc9c8689acf95154b2cb6874dbf80ccf4c7e"}
{"isin":"INE205A01025","instrumentKey":"NSE_EQ|INE205A01025","heading":"Vedanta demerger update: Oil & gas arm renamed Vedanta Oil and Gas from Malco Energy","summary":"On Tuesday, June 9, Vedanta, in its filing to stock exchanges, said that the Registrar of Companies under the Ministry of Corporate Affairs (MCA) has approved the change in name of its oil & gas business from “Malco Energy Limited” to “Vedanta Oil and Gas Limited”, with effect from June 9, 2026.","thumbnail":"https://assets.upstox.com/content/assets/images/news/vedanta-oil.webp","articleLink":"https://upstox.com/news/market-news/stocks/vedanta-demerger-update-oil-and-gas-arm-renamed-vedanta-oil-and-gas-from-malco-energy/article-195058/","publishedTime":1781057586585,"sourceHash":"fec1dee281209f150675018719b44ae25414240b04b075ebd2054c75492e2f3d"}
{"isin":"INE205A01025","instrumentKey":"NSE_EQ|INE205A01025","heading":"Vedanta shares in focus on rebranding its copper and nickel businesses; key details to know","summary":"Vedanta Nico, renamed as Vedanta Nickel, will sharpen its focus on building a domestic nickel ecosystem and supporting the country's demand for critical minerals.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/vedantasharejune-1.webp","articleLink":"https://upstox.com/news/market-news/stocks/vedanta-shares-in-focus-on-rebranding-its-copper-and-nickel-businesses-key-details-to-know/article-194982/","publishedTime":1780920773195,"sourceHash":"7bb074c384a054f6f4a1974aa6e3975cfe512bcce81be0e2d1a7e359d888e0ab"}
```

```jsonl
// File: news/instruments/INE213A01029.jsonl
{"isin":"INE213A01029","instrumentKey":"NSE_EQ|INE213A01029","heading":"Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads","summary":"A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran","thumbnail":"https://assets.upstox.com/content/assets/images/news/weekly-market-wrap-june-13.webp","articleLink":"https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/","publishedTime":1781332567326,"sourceHash":"226915bb315e42261997c4820bb093ee77d055cba9dcff5b26b427f9ca61d1ea"}
{"isin":"INE213A01029","instrumentKey":"NSE_EQ|INE213A01029","heading":"Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics","summary":"Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of ₹175 crore through open market transactions.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-june-12.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/","publishedTime":1781232986902,"sourceHash":"818d3cda34ea569ac92c98159ea06fe67e9a3a2ebc30eaae26b00e42dec46a91"}
{"isin":"INE213A01029","instrumentKey":"NSE_EQ|INE213A01029","heading":"Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list","summary":"On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/top-gainers-and-losers-nifty50.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/","publishedTime":1781088957700,"sourceHash":"6218f24d6f1f4d29b3cce4b2e2f4f2c198b8528047e2aed7f8e36fee8193db3c"}
```

```jsonl
// File: news/instruments/INE237A01036.jsonl
{"isin":"INE237A01036","instrumentKey":"BSE_EQ|INE237A01036","heading":"Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads","summary":"A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran","thumbnail":"https://assets.upstox.com/content/assets/images/news/weekly-market-wrap-june-13.webp","articleLink":"https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/","publishedTime":1781332567326,"sourceHash":"226915bb315e42261997c4820bb093ee77d055cba9dcff5b26b427f9ca61d1ea"}
```

```jsonl
// File: news/instruments/INE238A01034.jsonl
{"isin":"INE238A01034","instrumentKey":"NSE_EQ|INE238A01034","heading":"Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads","summary":"A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran","thumbnail":"https://assets.upstox.com/content/assets/images/news/weekly-market-wrap-june-13.webp","articleLink":"https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/","publishedTime":1781332567326,"sourceHash":"226915bb315e42261997c4820bb093ee77d055cba9dcff5b26b427f9ca61d1ea"}
{"isin":"INE238A01034","instrumentKey":"NSE_EQ|INE238A01034","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
```

```jsonl
// File: news/instruments/INE239A01024.jsonl
{"isin":"INE239A01024","instrumentKey":"NSE_EQ|INE239A01024","heading":"Nestle India shares in spotlight on rejecting claims of infestation in MAGGI noodles; key details","summary":"Nestle said that it has already submitted a detailed representation, supported by all relevant facts, quality records from batch and market samples, and test reports to the competent authorities.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/nestleindiamaggijune12.webp","articleLink":"https://upstox.com/news/market-news/stocks/nestle-india-shares-in-spotlight-on-rejecting-claims-of-infestation-in-maggi-noodles-key-details/article-195274/","publishedTime":1781273568006,"sourceHash":"ec2fd967242ee0b054604a08f847abc37d9e579df62797745a1ae250f9dff9da"}
{"isin":"INE239A01024","instrumentKey":"NSE_EQ|INE239A01024","heading":"Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list","summary":"On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/gainers-and-losers-may-20-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/","publishedTime":1781262446006,"sourceHash":"8e20b492437a2eb98c5f2e888610a20784ad1143d869cf3cf951157774bd0672"}
{"isin":"INE239A01024","instrumentKey":"NSE_EQ|INE239A01024","heading":"Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list","summary":"On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/top-gainers-and-losers-nifty50.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/","publishedTime":1781088957700,"sourceHash":"6218f24d6f1f4d29b3cce4b2e2f4f2c198b8528047e2aed7f8e36fee8193db3c"}
{"isin":"INE239A01024","instrumentKey":"NSE_EQ|INE239A01024","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE242A01010.jsonl
{"isin":"INE242A01010","instrumentKey":"NSE_EQ|INE242A01010","heading":"Govt bars bulk industrial petrol, diesel purchases through retail fuel outlets","summary":"The order, issued under the Essential Commodities Act, aims to prevent diversion of supplies meant for retail consumers after authorities observed bulk buyers shifting to petrol pumps due to the lower retail prices compared with bulk rates.","thumbnail":"https://assets.upstox.com/content/assets/images/news/petroldieselwindfall-taxwebp.webp","articleLink":"https://upstox.com/news/business-news/latest-updates/govt-bars-bulk-industrial-petrol-diesel-purchases-through-retail-fuel-outlets/article-195216/","publishedTime":1781234130711,"sourceHash":"779baf1fbb87eba5adc849be7aa39d376d07ddf6307d24505f804264fb6f4ecb"}
{"isin":"INE242A01010","instrumentKey":"NSE_EQ|INE242A01010","heading":"Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics","summary":"Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of ₹175 crore through open market transactions.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-june-12.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/","publishedTime":1781232986902,"sourceHash":"818d3cda34ea569ac92c98159ea06fe67e9a3a2ebc30eaae26b00e42dec46a91"}
```

```jsonl
// File: news/instruments/INE263A01024.jsonl
{"isin":"INE263A01024","instrumentKey":"NSE_EQ|INE263A01024","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
{"isin":"INE263A01024","instrumentKey":"NSE_EQ|INE263A01024","heading":"BEL shares jump 2% amid weak market; latest buzz you need to know","summary":"In November 2025, BEL had signed a Joint Venture Cooperation Agreement (JVCA) with France’s Safran Electronics and Defence (SED) to produce the HAMMER precision-guided air-to-ground weapon in India.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/belsharesq4.webp","articleLink":"https://upstox.com/news/market-news/stocks/bel-shares-jump-2-on-reports-of-iaf-eyeing-large-procurement-of-hammer-precision-weapon-key-details/article-194970/","publishedTime":1780913679174,"sourceHash":"1608bd364469276e7964bc9c4e27845cd15ca34aa425f8f9968b8df2cfc38930"}
{"isin":"INE263A01024","instrumentKey":"NSE_EQ|INE263A01024","heading":"BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details","summary":"Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-june-4.webp","articleLink":"https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/","publishedTime":1780906190587,"sourceHash":"9721ce6fd114c5ea2c24f25b6a1fdaf0d703f67a9986c8b14e9341816f1f772d"}
```

```jsonl
// File: news/instruments/INE267A01025.jsonl
{"isin":"INE267A01025","instrumentKey":"BSE_EQ|INE267A01025","heading":"Hindustan Zinc signs MoU with Sulfozyme Agro to advance sustainable metal recovery; stock falls","summary":"Hindustan Zinc said that it entered into a partnership with Sulfozyme Agro India under its flagship Zinc Industrial Park initiative, set up at the Bhilwara district in Rajasthan. \n","thumbnail":"https://assets.upstox.com/content/assets/images/news/hindustan-zinc-share-price-may-13-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/hindustan-zinc-signs-mo-u-with-sulfozyme-agro-to-advance-sustainable-metal-recovery-stock-falls/article-195100/","publishedTime":1781076230013,"sourceHash":"81ffe2546697b638a9e02434853a739b9ec762da7bc61db8fc165b0ad6d434b3"}
```

```jsonl
// File: news/instruments/INE274J01014.jsonl
{"isin":"INE274J01014","instrumentKey":"NSE_EQ|INE274J01014","heading":"Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics","summary":"Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of ₹175 crore through open market transactions.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-june-12.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/","publishedTime":1781232986902,"sourceHash":"818d3cda34ea569ac92c98159ea06fe67e9a3a2ebc30eaae26b00e42dec46a91"}
```

```jsonl
// File: news/instruments/INE280A01028.jsonl
{"isin":"INE280A01028","instrumentKey":"BSE_EQ|INE280A01028","heading":"SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%","summary":"NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/sterlite-techmtarstockrise.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/","publishedTime":1781264551315,"sourceHash":"362e2a33e0519980dbb144e87564af7974220addfe67b331f5319a8285f6764e"}
{"isin":"INE280A01028","instrumentKey":"BSE_EQ|INE280A01028","heading":"Top gainers and losers, June 9: IndiGo gains 4%, Jio Finserv up 2%, Titan Company down 2%; check list","summary":"On June 9, the SENSEX surged by 394.50 points or 0.54% to close at 73,918.76, while the NIFTY50 ended higher by 119.10 points or 0.52% at 23,242.10.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/gainers-and-losers-may-20-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-9-indi-go-gains-4-jio-finserv-up-2-titan-company-down-2-check-list/article-195047/","publishedTime":1781002812449,"sourceHash":"7399f00a68e77ef551fbaaa18ba2fd3e924d9a7741638a3fe7aaf6d6c00d9a96"}
```

```jsonl
// File: news/instruments/INE296A01032.jsonl
{"isin":"INE296A01032","instrumentKey":"NSE_EQ|INE296A01032","heading":"Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list","summary":"On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/gainers-and-losers-may-20-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/","publishedTime":1781262446006,"sourceHash":"8e20b492437a2eb98c5f2e888610a20784ad1143d869cf3cf951157774bd0672"}
{"isin":"INE296A01032","instrumentKey":"NSE_EQ|INE296A01032","heading":"SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%","summary":"NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/sterlite-techmtarstockrise.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/","publishedTime":1781264551315,"sourceHash":"362e2a33e0519980dbb144e87564af7974220addfe67b331f5319a8285f6764e"}
{"isin":"INE296A01032","instrumentKey":"NSE_EQ|INE296A01032","heading":"NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains","summary":"At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/","publishedTime":1781237907785,"sourceHash":"1772a0e37cddbc4697a01c04d8f3b11ec261b6fd87b50c367ecd617b5b741133"}
{"isin":"INE296A01032","instrumentKey":"NSE_EQ|INE296A01032","heading":"From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter","summary":"HDFC Bank reported net profit of ₹19,221 crore in Q4FY26, marking an increase of 8% from ₹17,616.14 crore in the year-ago period.","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-june-5.webp","articleLink":"https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/","publishedTime":1780987251866,"sourceHash":"f6c2d4f07fabca2511fa4579e91a56ec891aa83da0622ee38c6a14a8eb01cfbc"}
{"isin":"INE296A01032","instrumentKey":"NSE_EQ|INE296A01032","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
```

```jsonl
// File: news/instruments/INE326A01037.jsonl
{"isin":"INE326A01037","instrumentKey":"NSE_EQ|INE326A01037","heading":"Lupin partners with Spanish pharma company for launch of Luforbec inhalers in Spain; check stock performance","summary":"Luforbec (beclometasone/formoterol) 100/6, which will be launched in Spain, is a fixed-dose combination in a pressurised metered-dose inhaler, Lupin said.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/lupin-share-price-renascience.webp","articleLink":"https://upstox.com/news/market-news/stocks/lupin-partners-with-spanish-pharma-company-for-launch-of-luforbec-inhalers-in-spain-stock-falls/article-194966/","publishedTime":1780909010412,"sourceHash":"9b15edf249bcd92fce72d2673438b32a68990c9dec413e34d8262c6570637ad1"}
```

```jsonl
// File: news/instruments/INE377N01017.jsonl
{"isin":"INE377N01017","instrumentKey":"NSE_EQ|INE377N01017","heading":"Vikram Solar, Suzlon Energy, Waaree Energies: How renewable energy stocks are performing on June 9","summary":"In May 2026, Colliers India released a report, 'The Green Shift: Renewable Prioritisation Reshaping Indian Real Estate', wherein it said that India will need around 7 lakh acres of land parcels, estimated to cost ₹10-₹15 billion, in five years to set up solar and wind energy projects.","thumbnail":"https://assets.upstox.com/content/assets/images/news/green-energy-shares-june-9-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/vikram-solar-suzlon-energy-waaree-energies-how-renewable-energy-stocks-are-performing-on-june-9/article-195036/","publishedTime":1780994528822,"sourceHash":"5ec698c463d2a46f1154524a0515d49bc4520bb606b99b14b471c69b0b853ae2"}
```

```jsonl
// File: news/instruments/INE397D01024.jsonl
{"isin":"INE397D01024","instrumentKey":"BSE_EQ|INE397D01024","heading":"SENSEX, NIFTY50 erase gains to end on a flat note dragged by Reliance, Bharti Airtel","summary":"Selling pressure was broad based as 12 of 15 major sector gauges compiled by the National Stock Exchange (NSE) ended lower led by the NIFTY Media index's over 2% fall.","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-may-20-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-nifty-50-erase-gains-to-end-on-a-flat-note-dragged-by-reliance-bharti-airtel/article-195118/","publishedTime":1781087106142,"sourceHash":"8d3ed2066d2f2e40cae3146567edf2b8cc93070a3d2551634fb7603b438a03db"}
{"isin":"INE397D01024","instrumentKey":"BSE_EQ|INE397D01024","heading":"Bharti Airtel announces deployment of over 2,900 new 5G sites across 77 districts in North India; check stock performance","summary":"In the past 12 months, Bharti Airtel deployed more than 1,066 new 5G sites in Punjab, over 954 in Haryana, more than 276 in Himachal Pradesh, and over 619 in Jammu & Kashmir.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/airtelsubscriber-addition.webp","articleLink":"https://upstox.com/news/market-news/stocks/bharti-airtel-announces-deployment-of-over-2-900-new-5-g-sites-across-77-districts-in-north-india-check-stock-performance/article-195107/","publishedTime":1781078872635,"sourceHash":"8fb8a5bb92e0287cb4cdca2c27820e1e12f0dc842c03a27f88ad984255d7a498"}
{"isin":"INE397D01024","instrumentKey":"BSE_EQ|INE397D01024","heading":"Bharti Airtel shares trade higher amid TRAI penalties, latest buzz around Starlink launch","summary":"Bharti Airtel shares were trading higher on June 10, amid the latest set of penalties imposed by TRAI, likely due to the latest buzz around Starlink India's launch. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/airtelmarketcap.webp","articleLink":"https://upstox.com/news/market-news/stocks/bharti-airtel-share-price-in-focus-after-trai-penalties-latest-buzz-around-starlink-launch/article-195070/","publishedTime":1781063730123,"sourceHash":"7b53263521c675e1e2957c89ebe14b5c7fe17edfe6792060eda1fe9f6d6a4449"}
{"isin":"INE397D01024","instrumentKey":"BSE_EQ|INE397D01024","heading":"Bharti Airtel, Vodafone Idea shares rise as Bombay HC quashes govt decision to impose 1-time spectrum charge","summary":"In November 2012, the Union Cabinet took a decision that a one-time charge would be imposed for spectrum held beyond 6.2 MHz from July 2008 onwards. Following this, demand notices were issued to the petitioners (Bharti Airtel Ltd and Vodafone Idea Ltd) specifying the amounts payable by them towards one-time spectrum charge.","thumbnail":"https://assets.upstox.com/content/assets/images/news/bhart-airtel-vodafone-idea-hc-spectrum-relief.webp","articleLink":"https://upstox.com/news/market-news/stocks/bharti-airtel-vodafone-idea-shares-rise-as-bombay-hc-quashes-govt-decision-to-impose-1-time-spectrum-charge/article-195011/","publishedTime":1780978490137,"sourceHash":"b735626349b5b93b1de88ec6cad0d4986d1c565c70f3d9d6a6833359bd949beb"}
{"isin":"INE397D01024","instrumentKey":"BSE_EQ|INE397D01024","heading":"Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL","summary":"Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of ₹303 per share. The OFS opens for non-retail investors on Tuesday.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-tuesday.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/","publishedTime":1780975699014,"sourceHash":"7cb3e9365ebcc8ea852f5f8b1c759e8a689808885338130bb2404eb3120cb31d"}
{"isin":"INE397D01024","instrumentKey":"BSE_EQ|INE397D01024","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
```

```jsonl
// File: news/instruments/INE415G01027.jsonl
{"isin":"INE415G01027","instrumentKey":"NSE_EQ|INE415G01027","heading":"Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE","summary":"NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.","thumbnail":"https://assets.upstox.com/content/assets/images/news/reliancetcswiprohit52weeklow.webp","articleLink":"https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/","publishedTime":1780918365081,"sourceHash":"d25a0966a57e19ddd7ff8f63cbcc3f415b032033ca306956ba9875cddb7d48c7"}
```

```jsonl
// File: news/instruments/INE423A01024.jsonl
{"isin":"INE423A01024","instrumentKey":"NSE_EQ|INE423A01024","heading":"Adani Enterprises, Adani Energy: Adani Group shares trade mixed as SBI MF buys stake worth ₹5,747 crore","summary":"The stake buy comes after SBI Mutual Fund last month acquired a 0.45% stake in Adani's flagship firm, Adani Enterprises, for ₹1,435 crore","thumbnail":"https://assets.upstox.com/content/assets/images/news/adanigroupwebp.webp","articleLink":"https://upstox.com/news/market-news/stocks/adani-enterprises-adani-energy-adani-group-shares-in-focus-as-sbi-mf-buys-stake-worth-5-747-crore-from-gqg-partners/article-194917/","publishedTime":1780895617577,"sourceHash":"f318c0c5c4169d92bedb03bd7409c3a7113386f21e1e5c6cfec420ec2428fbe2"}
```

```jsonl
// File: news/instruments/INE437A01024.jsonl
{"isin":"INE437A01024","instrumentKey":"NSE_EQ|INE437A01024","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE455K01017.jsonl
{"isin":"INE455K01017","instrumentKey":"BSE_EQ|INE455K01017","heading":"Redington, JSW Steel, DMart among buzzing stocks as SENSEX jumps over 270 pts, NIFTY above 23,200","summary":"Grasim Industries shares gained 2% on the NSE on Tuesday, June 9, as it said it will invest ₹3,094 crore for expanding its Lyocell capacity at Harihar, Karnataka, under phase II.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/market09june4214.webp","articleLink":"https://upstox.com/news/market-news/stocks/redington-jsw-steel-d-mart-among-buzzing-stocks-as-sensex-jumps-over-170-pts-nifty-above-23-200/article-195029/","publishedTime":1780992961868,"sourceHash":"b0c5c6b5da9ed451cda7339f971761600b4954a1262222cac005051791ef3b57"}
{"isin":"INE455K01017","instrumentKey":"BSE_EQ|INE455K01017","heading":"Polycab India dividend schedule: Board announces record date for ₹47/share dividend issue","summary":"Polycab India announced the record date for its ₹47 per share dividend issue for the financial year ended 2025-26. Here's what investors should know. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/polycab-share-price.webp","articleLink":"https://upstox.com/news/market-news/stocks/polycab-india-dividend-schedule-board-announces-record-date-for-47-share-dividend-issue/article-195003/","publishedTime":1780976632350,"sourceHash":"4cbaa8df6295ca85f1104c3a6514998c3d9ec3e9e5f496c1952696b0398a4f2e"}
```

```jsonl
// File: news/instruments/INE467B01029.jsonl
{"isin":"INE467B01029","instrumentKey":"BSE_EQ|INE467B01029","heading":"OMCs, Tata Motors PV, TCS among buzzing stocks as NIFTY up 1.4%, SENSEX climbs 1,202 pts in noon deals","summary":"From the NIFTY firms, Shriram Finance, InterGlobe Aviation, Bajaj Finance, L&T, HDFC Bank, Jio Financial Services Eternal, Titan, Tata Motors Passenger Vehicles and were among the biggest winners.","thumbnail":"https://assets.upstox.com/content/assets/images/news/nifty-sensex-buzzing-stocks-june-12-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/om-cs-tata-motors-pv-tcs-among-buzzing-stocks-as-nifty-up-0-77-sensex-climbs-729-pts-in-noon-deals/article-195250/","publishedTime":1781253125381,"sourceHash":"419f48ffbdd77b2da3a9041a50de9947223b29588088b8de094c60007c64d549"}
{"isin":"INE467B01029","instrumentKey":"BSE_EQ|INE467B01029","heading":"TCS shares rise 2% as it sets up country’s first Oracle AI data platform lab, CoE in Kolkata","summary":"In a statement, TCS said it also plans to roll out the Oracle AI Data Platform Labs and CoEs across four additional cities in India over the next three years","thumbnail":"https://assets.upstox.com/content/assets/images/news/tcsanthropicdealwr3.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-shares-rise-2-as-it-sets-up-country-s-first-oracle-ai-data-platform-lab-co-e-in-kolkata-1/article-195244/","publishedTime":1781247769546,"sourceHash":"caa5bedd8b1e47f8b7916507a58c5c628991526442b6ba07c2b33ef500703b29"}
{"isin":"INE467B01029","instrumentKey":"BSE_EQ|INE467B01029","heading":"TCS ties up with Anthropic to equip 50,000 employees with Claude; stock hits 52-week low","summary":"TCS and Anthropic will jointly go to market with AI solutions and services across industries","thumbnail":"https://assets.upstox.com/content/assets/images/news/tcs-share-price-canada-life-deal.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-ties-up-with-anthropic-to-equip-50-000-employees-with-claude-stock-hits-52-week-low/article-195164/","publishedTime":1781160026627,"sourceHash":"78941852b91a7478fda8c8f21819727d7c12d1dfe6533ae59f5c57e6bc7d52c6"}
{"isin":"INE467B01029","instrumentKey":"BSE_EQ|INE467B01029","heading":"TCS, Infosys, Tech Mahindra, other IT shares fall amid AI stocks sell-off on Wall Street","summary":"Wall Street has been on shaky ground since last week, as artificial intelligence stocks have traded lower after reaching record highs.","thumbnail":"https://assets.upstox.com/content/assets/images/news/it-stocks-ai-sell-off-wall-street.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-infosys-tech-mahindra-other-it-stocks-fall-amid-ai-stocks-sell-off-on-wall-street/article-195152/","publishedTime":1781154186328,"sourceHash":"4c893884b27e1d0dec902957a3fad07dd753d4942f67c0b24a708c908570ae9a"}
{"isin":"INE467B01029","instrumentKey":"BSE_EQ|INE467B01029","heading":"TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump’s $100,000 H-1B visa fee rule","summary":"A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.","thumbnail":"https://assets.upstox.com/content/assets/images/news/it-stocks-june-09-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/","publishedTime":1780978356295,"sourceHash":"88547509c42c0805cc1222667a0e95d8f2bc1b172668b63b83291838c9450fc4"}
{"isin":"INE467B01029","instrumentKey":"BSE_EQ|INE467B01029","heading":"Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE","summary":"NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.","thumbnail":"https://assets.upstox.com/content/assets/images/news/reliancetcswiprohit52weeklow.webp","articleLink":"https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/","publishedTime":1780918365081,"sourceHash":"d25a0966a57e19ddd7ff8f63cbcc3f415b032033ca306956ba9875cddb7d48c7"}
{"isin":"INE467B01029","instrumentKey":"BSE_EQ|INE467B01029","heading":"TCS secures multimillion-euro AI deal from Canada Life, shares down","summary":"The overhaul will help upgrade operational resilience, increase automation, and boost user experience for Canada Life's customers, TCS said in an exchange filing.","thumbnail":"https://assets.upstox.com/content/assets/images/news/tcs-share-price-canada-life-deal.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-secures-multimillion-euro-ai-deal-with-canada-life-shares-down/article-194957/","publishedTime":1780907036882,"sourceHash":"23d5fc4e4652a23a9a2d2eee6fb3661e65af83d884fd2e509b33947dfb1d8c31"}
{"isin":"INE467B01029","instrumentKey":"BSE_EQ|INE467B01029","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE476A01022.jsonl
{"isin":"INE476A01022","instrumentKey":"BSE_EQ|INE476A01022","heading":"Bank of Baroda, Canara Bank, Federal Bank shares rally; NIFTY Bank surges most in two months, here’s why","summary":"Banking stocks came under buying interest after the Reserve Bank of India (RBI) on Monday introduced a US dollar-rupee forex swap facility for fresh FCNR (B) deposits.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockpti4520180020-1.webp","articleLink":"https://upstox.com/news/market-news/stocks/bank-of-baroda-canara-bank-federal-bank-shares-rally-nifty-bank-surges-most-in-two-months-here-s-why/article-195034/","publishedTime":1780993166287,"sourceHash":"76d44e775571aac9491cc3080386657373691836b1560da32f12d64d31a64dc2"}
```

```jsonl
// File: news/instruments/INE522F01014.jsonl
{"isin":"INE522F01014","instrumentKey":"BSE_EQ|INE522F01014","heading":"Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list","summary":"On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/top-gainers-and-losers-nifty50.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/","publishedTime":1781088957700,"sourceHash":"6218f24d6f1f4d29b3cce4b2e2f4f2c198b8528047e2aed7f8e36fee8193db3c"}
{"isin":"INE522F01014","instrumentKey":"BSE_EQ|INE522F01014","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
```

```jsonl
// File: news/instruments/INE646L01027.jsonl
{"isin":"INE646L01027","instrumentKey":"BSE_EQ|INE646L01027","heading":"SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%","summary":"NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/sterlite-techmtarstockrise.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/","publishedTime":1781264551315,"sourceHash":"362e2a33e0519980dbb144e87564af7974220addfe67b331f5319a8285f6764e"}
{"isin":"INE646L01027","instrumentKey":"BSE_EQ|INE646L01027","heading":"NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains","summary":"At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/","publishedTime":1781237907785,"sourceHash":"1772a0e37cddbc4697a01c04d8f3b11ec261b6fd87b50c367ecd617b5b741133"}
{"isin":"INE646L01027","instrumentKey":"BSE_EQ|INE646L01027","heading":"Can IndiGo navigate its near-term cost headwinds? Experts weigh in","summary":"IndiGo is facing several cost pressures with the rising energy prices and expansion plans, as experts predict the cost pressures are likely to normalise as services return to normalcy.","thumbnail":"https://assets.upstox.com/content/assets/images/news/indigoshare-after-q4-result.webp","articleLink":"https://upstox.com/news/market-news/stocks/can-indi-go-navigate-its-near-term-cost-headwinds-experts-weigh-in/article-195054/","publishedTime":1781018309062,"sourceHash":"c9cdebb974fdf1428d5026e73f7d039ce757cb131fa44c1d63796983e8a11404"}
{"isin":"INE646L01027","instrumentKey":"BSE_EQ|INE646L01027","heading":"Top gainers and losers, June 9: IndiGo gains 4%, Jio Finserv up 2%, Titan Company down 2%; check list","summary":"On June 9, the SENSEX surged by 394.50 points or 0.54% to close at 73,918.76, while the NIFTY50 ended higher by 119.10 points or 0.52% at 23,242.10.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/gainers-and-losers-may-20-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-9-indi-go-gains-4-jio-finserv-up-2-titan-company-down-2-check-list/article-195047/","publishedTime":1781002812449,"sourceHash":"7399f00a68e77ef551fbaaa18ba2fd3e924d9a7741638a3fe7aaf6d6c00d9a96"}
{"isin":"INE646L01027","instrumentKey":"BSE_EQ|INE646L01027","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
{"isin":"INE646L01027","instrumentKey":"BSE_EQ|INE646L01027","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE669C01036.jsonl
{"isin":"INE669C01036","instrumentKey":"BSE_EQ|INE669C01036","heading":"TCS, Infosys, Tech Mahindra, other IT shares fall amid AI stocks sell-off on Wall Street","summary":"Wall Street has been on shaky ground since last week, as artificial intelligence stocks have traded lower after reaching record highs.","thumbnail":"https://assets.upstox.com/content/assets/images/news/it-stocks-ai-sell-off-wall-street.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-infosys-tech-mahindra-other-it-stocks-fall-amid-ai-stocks-sell-off-on-wall-street/article-195152/","publishedTime":1781154186328,"sourceHash":"4c893884b27e1d0dec902957a3fad07dd753d4942f67c0b24a708c908570ae9a"}
{"isin":"INE669C01036","instrumentKey":"BSE_EQ|INE669C01036","heading":"TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump’s $100,000 H-1B visa fee rule","summary":"A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.","thumbnail":"https://assets.upstox.com/content/assets/images/news/it-stocks-june-09-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/","publishedTime":1780978356295,"sourceHash":"88547509c42c0805cc1222667a0e95d8f2bc1b172668b63b83291838c9450fc4"}
{"isin":"INE669C01036","instrumentKey":"BSE_EQ|INE669C01036","heading":"BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details","summary":"Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-june-4.webp","articleLink":"https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/","publishedTime":1780906190587,"sourceHash":"9721ce6fd114c5ea2c24f25b6a1fdaf0d703f67a9986c8b14e9341816f1f772d"}
```

```jsonl
// File: news/instruments/INE669E01016.jsonl
{"isin":"INE669E01016","instrumentKey":"BSE_EQ|INE669E01016","heading":"OMCs, Tata Motors PV, TCS among buzzing stocks as NIFTY up 1.4%, SENSEX climbs 1,202 pts in noon deals","summary":"From the NIFTY firms, Shriram Finance, InterGlobe Aviation, Bajaj Finance, L&T, HDFC Bank, Jio Financial Services Eternal, Titan, Tata Motors Passenger Vehicles and were among the biggest winners.","thumbnail":"https://assets.upstox.com/content/assets/images/news/nifty-sensex-buzzing-stocks-june-12-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/om-cs-tata-motors-pv-tcs-among-buzzing-stocks-as-nifty-up-0-77-sensex-climbs-729-pts-in-noon-deals/article-195250/","publishedTime":1781253125381,"sourceHash":"419f48ffbdd77b2da3a9041a50de9947223b29588088b8de094c60007c64d549"}
{"isin":"INE669E01016","instrumentKey":"BSE_EQ|INE669E01016","heading":"22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround","summary":"Vodafone Idea reported a net profit of ₹52,022 crore in March quarter compared with a loss of ₹7,268 crore in the year-ago period and loss of ₹5,324 crore in the previous quarter due to relief in statutory liabilities.","thumbnail":"https://assets.upstox.com/content/assets/images/news/market09june4214.webp","articleLink":"https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/","publishedTime":1781075957852,"sourceHash":"596ebd8b82c9627af55e917aeaf5c53eb7215884b5c5abd9372862ede2e13533"}
{"isin":"INE669E01016","instrumentKey":"BSE_EQ|INE669E01016","heading":"Bharti Airtel, Vodafone Idea shares rise as Bombay HC quashes govt decision to impose 1-time spectrum charge","summary":"In November 2012, the Union Cabinet took a decision that a one-time charge would be imposed for spectrum held beyond 6.2 MHz from July 2008 onwards. Following this, demand notices were issued to the petitioners (Bharti Airtel Ltd and Vodafone Idea Ltd) specifying the amounts payable by them towards one-time spectrum charge.","thumbnail":"https://assets.upstox.com/content/assets/images/news/bhart-airtel-vodafone-idea-hc-spectrum-relief.webp","articleLink":"https://upstox.com/news/market-news/stocks/bharti-airtel-vodafone-idea-shares-rise-as-bombay-hc-quashes-govt-decision-to-impose-1-time-spectrum-charge/article-195011/","publishedTime":1780978490137,"sourceHash":"b735626349b5b93b1de88ec6cad0d4986d1c565c70f3d9d6a6833359bd949beb"}
{"isin":"INE669E01016","instrumentKey":"BSE_EQ|INE669E01016","heading":"Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL","summary":"Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of ₹303 per share. The OFS opens for non-retail investors on Tuesday.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-tuesday.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/","publishedTime":1780975699014,"sourceHash":"7cb3e9365ebcc8ea852f5f8b1c759e8a689808885338130bb2404eb3120cb31d"}
```

```jsonl
// File: news/instruments/INE721A01047.jsonl
{"isin":"INE721A01047","instrumentKey":"BSE_EQ|INE721A01047","heading":"Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list","summary":"On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/gainers-and-losers-may-20-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/","publishedTime":1781262446006,"sourceHash":"8e20b492437a2eb98c5f2e888610a20784ad1143d869cf3cf951157774bd0672"}
{"isin":"INE721A01047","instrumentKey":"BSE_EQ|INE721A01047","heading":"SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%","summary":"NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/sterlite-techmtarstockrise.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/","publishedTime":1781264551315,"sourceHash":"362e2a33e0519980dbb144e87564af7974220addfe67b331f5319a8285f6764e"}
{"isin":"INE721A01047","instrumentKey":"BSE_EQ|INE721A01047","heading":"NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains","summary":"At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/","publishedTime":1781237907785,"sourceHash":"1772a0e37cddbc4697a01c04d8f3b11ec261b6fd87b50c367ecd617b5b741133"}
{"isin":"INE721A01047","instrumentKey":"BSE_EQ|INE721A01047","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
```

```jsonl
// File: news/instruments/INE733E01010.jsonl
{"isin":"INE733E01010","instrumentKey":"NSE_EQ|INE733E01010","heading":"Defence minister approves 250 MW solar-BESS project on vacant defence land in UP's Sitapur","summary":"NTPC Limited will implement the project through a competitive bidding process in coordination with the Integrated Headquarters of the Ministry of Defence (Army) and the Directorate General Defence Estates.","thumbnail":"https://assets.upstox.com/content/assets/images/news/defence-minister-rajnath-singh.webp","articleLink":"https://upstox.com/news/business-news/latest-updates/defence-minister-approves-250-mw-solar-bess-project-on-vacant-defence-land-in-up-s-sitapur/article-195037/","publishedTime":1780995252127,"sourceHash":"00810011af39ad66c76294224737b7c6ffca23a3646761082aad03030db37591"}
{"isin":"INE733E01010","instrumentKey":"NSE_EQ|INE733E01010","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
{"isin":"INE733E01010","instrumentKey":"NSE_EQ|INE733E01010","heading":"BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details","summary":"Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-june-4.webp","articleLink":"https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/","publishedTime":1780906190587,"sourceHash":"9721ce6fd114c5ea2c24f25b6a1fdaf0d703f67a9986c8b14e9341816f1f772d"}
```

```jsonl
// File: news/instruments/INE742F01042.jsonl
{"isin":"INE742F01042","instrumentKey":"NSE_EQ|INE742F01042","heading":"Adani Ports secures 10-year marine services contract for Argentina's first LNG export; details here","summary":"The contract has been awarded to APSEZ's step-down subsidiary, The Adani Harbour International FZCO, through a consortium with Argentina-based Meridian Group following a global competitive tender process conducted by Southern Energy S.A. (SESA). ","thumbnail":"https://assets.upstox.com/content/assets/images/news/adani-ports-shares-june-8-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/adani-ports-secures-10-year-marine-services-contract-for-argentina-s-first-lng-export-details-here/article-194943/","publishedTime":1780894555448,"sourceHash":"a5f4cc40654d1b212f4589d42861dbd77c3523e859ffa14155d9a299768d3e5c"}
```

```jsonl
// File: news/instruments/INE752E01010.jsonl
{"isin":"INE752E01010","instrumentKey":"NSE_EQ|INE752E01010","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
{"isin":"INE752E01010","instrumentKey":"NSE_EQ|INE752E01010","heading":"BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details","summary":"Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-june-4.webp","articleLink":"https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/","publishedTime":1780906190587,"sourceHash":"9721ce6fd114c5ea2c24f25b6a1fdaf0d703f67a9986c8b14e9341816f1f772d"}
{"isin":"INE752E01010","instrumentKey":"NSE_EQ|INE752E01010","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE758E01017.jsonl
{"isin":"INE758E01017","instrumentKey":"BSE_EQ|INE758E01017","heading":"Top gainers and losers, June 9: IndiGo gains 4%, Jio Finserv up 2%, Titan Company down 2%; check list","summary":"On June 9, the SENSEX surged by 394.50 points or 0.54% to close at 73,918.76, while the NIFTY50 ended higher by 119.10 points or 0.52% at 23,242.10.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/gainers-and-losers-may-20-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-9-indi-go-gains-4-jio-finserv-up-2-titan-company-down-2-check-list/article-195047/","publishedTime":1781002812449,"sourceHash":"7399f00a68e77ef551fbaaa18ba2fd3e924d9a7741638a3fe7aaf6d6c00d9a96"}
{"isin":"INE758E01017","instrumentKey":"BSE_EQ|INE758E01017","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
{"isin":"INE758E01017","instrumentKey":"BSE_EQ|INE758E01017","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
```

```jsonl
// File: news/instruments/INE758T01015.jsonl
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%","summary":"NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/sterlite-techmtarstockrise.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/","publishedTime":1781264551315,"sourceHash":"362e2a33e0519980dbb144e87564af7974220addfe67b331f5319a8285f6764e"}
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"TCS, Zee Ent, DOMS Industries among buzzing stocks as SENSEX gains over 200 pts, NIFTY above 23,200","summary":"Shares of Doms Industries climbed as much as 7.33% to hit an intraday high of ₹2,273.90 per unit on the NSE on Thursday, after the company entered into an agreement to buy Reynolds Pens India Limited.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-22-may.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-zee-ent-doms-industries-among-buzzing-stocks-as-sensex-gains-over-200-pts-nifty-above-23-200/article-195172/","publishedTime":1781163081692,"sourceHash":"68a8aff833eff1e988bebb9ddc11b6faf6a0f9e509996d1b1d8b2581be8a530d"}
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"Zepto vs Blinkit vs Instamart: Which quick commerce platform leads in revenue and profitability ahead of Zepto IPO?","summary":"Zepto IPO has moved one step closer to its market debut after the company filed its updated DRHP with SEBI. As Zepto prepares to enter the public markets, here’s a comparative look at how the company stacks up against its listed quick commerce peers, Eternal’s Blinkit and Swiggy’s Instamart, across key metrics such as revenue, profitability, and dark stores.","thumbnail":"https://assets.upstox.com/content/assets/images/news/zeptoipogmp.webp","articleLink":"https://upstox.com/news/market-news/ipo/zepto-vs-blinkit-vs-instamart-which-quick-commerce-platform-leads-in-revenue-and-profitability-ahead-of-zepto-ipo/article-195149/","publishedTime":1781153861680,"sourceHash":"e3259ceba7e3dbc624152f5be59c94abc749568aba20e0f2ba8a830d00cb981a"}
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"Stocks to watch, June 11: REC, PFC, Eternal, Power Grid, Lenskart Solutions, Meesho, Zee Entertainment","summary":" Zomato and Blinkit's parent entity, Eternal, has received a ₹9.63 crore GST demand notice, along with interest and penalty, from the Andhra Pradesh tax authorities.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stocks-to-watch-june-11-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-11-2026-rec-pfc-eternal-power-grid-lenskart-solutions-meesho-zee-entertainment/article-195142/","publishedTime":1781145776128,"sourceHash":"9e1ab0cd5f0a59961dde64c34935e52e5bf7a7d0ee1d0004138277c7ad8ae1cd"}
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"Eternal share price in focus: Zomato-parent gets GST demand notice of ₹9.63 crore from Andhra Pradesh authorities","summary":"Eternal shares will be in focus on Thursday after the firm received a GST order from Andhra Pradesh authorities after the market hours on June 10. Here's what investors should know.","thumbnail":"https://assets.upstox.com/content/assets/images/news/eternalsharepricetoday.webp","articleLink":"https://upstox.com/news/market-news/stocks/eternal-share-price-in-focus-zomato-parent-gets-gst-demand-notice-of-9-63-crore-from-andhra-pradesh-authorities/article-195124/","publishedTime":1781094559025,"sourceHash":"5e8e69022b2884956c0ee3bdff035ec4c9d0f30c4f2efa611adb05bcf57c8fcb"}
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"Top gainers and losers, June 8: Wipro tumbles 8%, Jio Finserv, Eternal down 4%, Max Healthcare up 3%; check list","summary":"On June 8, the S&P BSE SENSEX closed 719.09 points, or 0.97% lower at 73,524.26. Meanwhile, NSE’s NIFTY50 ended at 23,123, reflecting a 243.70-point, or 1.04% decline.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/top-gainers-and-losers-may-29-converted-from-jpg.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-8-wipro-tumbles-8-jio-finserv-eternal-down-4-max-healthcare-up-3-check-list/article-194980/","publishedTime":1780918146424,"sourceHash":"012e692bade01e45f5afa79802b9bded089e7cf7bd2a557366238dddb204c3a0"}
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment","summary":"The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarketwrap183.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/","publishedTime":1780917274477,"sourceHash":"b90f0538fba4be37d0af9ab2c6285f8402458c272e7546a7e01623d7f3b8d57f"}
{"isin":"INE758T01015","instrumentKey":"NSE_EQ|INE758T01015","heading":"SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers","summary":"The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/markets-wrap-nifty50-index-lost-19percent-while-sensex-dropped-23percent-in-the-week-ended-friday-april-24.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/","publishedTime":1780892719326,"sourceHash":"f6f9560f206031c57e76bcd7c01ad69d4f7e4d98a98825bb13b1394e90ef4f2e"}
```

```jsonl
// File: news/instruments/INE849A01020.jsonl
{"isin":"INE849A01020","instrumentKey":"BSE_EQ|INE849A01020","heading":"NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains","summary":"At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/","publishedTime":1781237907785,"sourceHash":"1772a0e37cddbc4697a01c04d8f3b11ec261b6fd87b50c367ecd617b5b741133"}
{"isin":"INE849A01020","instrumentKey":"BSE_EQ|INE849A01020","heading":"Adani Enterprises, Tata Steel, PNB, Tata Motors, Trent dividend record date on June 12: Last day to buy for payout","summary":"Tata Steel has recommended a dividend of ₹4 per share of face value of ₹1 each to the shareholders of the company for FY2025-26. The record date is June 12.","thumbnail":"https://assets.upstox.com/content/assets/images/news/dividend-alert-news-adani-enterprises-ports-tata-steel-motors-acc-trent-last-date.webp","articleLink":"https://upstox.com/news/market-news/stocks/adani-enterprises-tata-steel-pnb-tata-motors-trent-dividend-record-date-on-june-12-last-day-to-buy-for-payout/article-195146/","publishedTime":1781166173232,"sourceHash":"c51620c9dab0825e3f1f2c967ab56045f3500f68a02984a0a6af60b82ba6c52a"}
{"isin":"INE849A01020","instrumentKey":"BSE_EQ|INE849A01020","heading":"SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers","summary":"The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ","thumbnail":"https://assets.upstox.com/content/assets/images/news/stockmarket24nov2025.webp","articleLink":"https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/","publishedTime":1780978075718,"sourceHash":"9fe032d525835532070a2b17a25bb17c1be9144a89efab58107d0d215c3b7177"}
```

```jsonl
// File: news/instruments/INE860A01027.jsonl
{"isin":"INE860A01027","instrumentKey":"NSE_EQ|INE860A01027","heading":"Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list","summary":"On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/market-wrap-april-9.webp","articleLink":"https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/","publishedTime":1781175583927,"sourceHash":"2386d018edd12f95cb9e4767657b93c1fe060b93ffefe15fa92b65cc08df1379"}
{"isin":"INE860A01027","instrumentKey":"NSE_EQ|INE860A01027","heading":"TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump’s $100,000 H-1B visa fee rule","summary":"A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.","thumbnail":"https://assets.upstox.com/content/assets/images/news/it-stocks-june-09-2026.webp","articleLink":"https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/","publishedTime":1780978356295,"sourceHash":"88547509c42c0805cc1222667a0e95d8f2bc1b172668b63b83291838c9450fc4"}
{"isin":"INE860A01027","instrumentKey":"NSE_EQ|INE860A01027","heading":"Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL","summary":"Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of ₹303 per share. The OFS opens for non-retail investors on Tuesday.","thumbnail":"https://assets.upstox.com/content/assets/images/news/stock-to-watch-tuesday.webp","articleLink":"https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/","publishedTime":1780975699014,"sourceHash":"7cb3e9365ebcc8ea852f5f8b1c759e8a689808885338130bb2404eb3120cb31d"}
{"isin":"INE860A01027","instrumentKey":"NSE_EQ|INE860A01027","heading":"HCLTech shares in the spotlight on collaborating with Google Cloud to launch AI Innovation Zone ","summary":"The AI Innovation Zone, as per the company, will provide a “dedicated environment” for HCLTech and its clients to design, build, and deploy AI-driven workflows and advance robotics-led innovation.\n","thumbnail":"https://assets.upstox.com/content/assets/images/news/hcltechjune8.webp","articleLink":"https://upstox.com/news/market-news/stocks/hcl-tech-shares-in-the-spotlight-on-collaborating-with-google-cloud-to-launch-ai-innovation/article-194984/","publishedTime":1780923944477,"sourceHash":"e58408c2ddad0b111cc05a6b9b68b87cb3032803c48ef9c08fcdb93b8e65abe8"}
```

```jsonl
// File: news/instruments/INE917I01010.jsonl
{"isin":"INE917I01010","instrumentKey":"BSE_EQ|INE917I01010","heading":"From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter","summary":"HDFC Bank reported net profit of ₹19,221 crore in Q4FY26, marking an increase of 8% from ₹17,616.14 crore in the year-ago period.","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-june-5.webp","articleLink":"https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/","publishedTime":1780987251866,"sourceHash":"f6c2d4f07fabca2511fa4579e91a56ec891aa83da0622ee38c6a14a8eb01cfbc"}
```

```jsonl
// File: news/instruments/INE931S01010.jsonl
{"isin":"INE931S01010","instrumentKey":"NSE_EQ|INE931S01010","heading":"Adani Enterprises, Adani Energy: Adani Group shares trade mixed as SBI MF buys stake worth ₹5,747 crore","summary":"The stake buy comes after SBI Mutual Fund last month acquired a 0.45% stake in Adani's flagship firm, Adani Enterprises, for ₹1,435 crore","thumbnail":"https://assets.upstox.com/content/assets/images/news/adanigroupwebp.webp","articleLink":"https://upstox.com/news/market-news/stocks/adani-enterprises-adani-energy-adani-group-shares-in-focus-as-sbi-mf-buys-stake-worth-5-747-crore-from-gqg-partners/article-194917/","publishedTime":1780895617577,"sourceHash":"f318c0c5c4169d92bedb03bd7409c3a7113386f21e1e5c6cfec420ec2428fbe2"}
```

```jsonl
// File: news/instruments/INE944F01028.jsonl
{"isin":"INE944F01028","instrumentKey":"NSE_EQ|INE944F01028","heading":"From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter","summary":"HDFC Bank reported net profit of ₹19,221 crore in Q4FY26, marking an increase of 8% from ₹17,616.14 crore in the year-ago period.","thumbnail":"https://assets.upstox.com/content/assets/images/news/buzzing-stocks-june-5.webp","articleLink":"https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/","publishedTime":1780987251866,"sourceHash":"f6c2d4f07fabca2511fa4579e91a56ec891aa83da0622ee38c6a14a8eb01cfbc"}
```

```jsonl
// File: news/instruments/INE982J01020.jsonl
{"isin":"INE982J01020","instrumentKey":"NSE_EQ|INE982J01020","heading":"22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround","summary":"Vodafone Idea reported a net profit of ₹52,022 crore in March quarter compared with a loss of ₹7,268 crore in the year-ago period and loss of ₹5,324 crore in the previous quarter due to relief in statutory liabilities.","thumbnail":"https://assets.upstox.com/content/assets/images/news/market09june4214.webp","articleLink":"https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/","publishedTime":1781075957852,"sourceHash":"596ebd8b82c9627af55e917aeaf5c53eb7215884b5c5abd9372862ede2e13533"}
```

```json
// File: news/metadata/instruments/INE002A01018.json
{"isin":"INE002A01018","totalArticles":5,"lastUpdated":1781447437680,"latestArticle":1781087106142}
```

```json
// File: news/metadata/instruments/INE006I01046.json
{"isin":"INE006I01046","totalArticles":1,"lastUpdated":1781447434715,"latestArticle":1781239380279}
```

```json
// File: news/metadata/instruments/INE009A01021.json
{"isin":"INE009A01021","totalArticles":4,"lastUpdated":1781447433776,"latestArticle":1781175583927}
```

```json
// File: news/metadata/instruments/INE00H001014.json
{"isin":"INE00H001014","totalArticles":2,"lastUpdated":1781447441453,"latestArticle":1781153861680}
```

```json
// File: news/metadata/instruments/INE00R701025.json
{"isin":"INE00R701025","totalArticles":1,"lastUpdated":1781447432982,"latestArticle":1780918365081}
```

```json
// File: news/metadata/instruments/INE016A01026.json
{"isin":"INE016A01026","totalArticles":2,"lastUpdated":1781447444332,"latestArticle":1781237178241}
```

```json
// File: news/metadata/instruments/INE018A01030.json
{"isin":"INE018A01030","totalArticles":4,"lastUpdated":1781447443280,"latestArticle":1781264551315}
```

```json
// File: news/metadata/instruments/INE019A01038.json
{"isin":"INE019A01038","totalArticles":2,"lastUpdated":1781447437432,"latestArticle":1780992961868}
```

```json
// File: news/metadata/instruments/INE020B01018.json
{"isin":"INE020B01018","totalArticles":3,"lastUpdated":1781447441251,"latestArticle":1781163081692}
```

```json
// File: news/metadata/instruments/INE027H01010.json
{"isin":"INE027H01010","totalArticles":2,"lastUpdated":1781447435240,"latestArticle":1780918146424}
```

```json
// File: news/metadata/instruments/INE028A01039.json
{"isin":"INE028A01039","totalArticles":1,"lastUpdated":1781447434283,"latestArticle":1780993166287}
```

```json
// File: news/metadata/instruments/INE029A01011.json
{"isin":"INE029A01011","totalArticles":1,"lastUpdated":1781447444006,"latestArticle":1781234130711}
```

```json
// File: news/metadata/instruments/INE030A01027.json
{"isin":"INE030A01027","totalArticles":3,"lastUpdated":1781447434170,"latestArticle":1781088957700}
```

```json
// File: news/metadata/instruments/INE038A01020.json
{"isin":"INE038A01020","totalArticles":3,"lastUpdated":1781447441008,"latestArticle":1781088957700}
```

```json
// File: news/metadata/instruments/INE040A01034.json
{"isin":"INE040A01034","totalArticles":3,"lastUpdated":1781447438241,"latestArticle":1781264551315}
```

```json
// File: news/metadata/instruments/INE040H01021.json
{"isin":"INE040H01021","totalArticles":1,"lastUpdated":1781447443263,"latestArticle":1780994528822}
```

```json
// File: news/metadata/instruments/INE044A01036.json
{"isin":"INE044A01036","totalArticles":1,"lastUpdated":1781447440904,"latestArticle":1781233777283}
```

```json
// File: news/metadata/instruments/INE047A01021.json
{"isin":"INE047A01021","totalArticles":1,"lastUpdated":1781447435617,"latestArticle":1780978075718}
```

```json
// File: news/metadata/instruments/INE061F01013.json
{"isin":"INE061F01013","totalArticles":1,"lastUpdated":1781447441166,"latestArticle":1781075957852}
```

```json
// File: news/metadata/instruments/INE062A01020.json
{"isin":"INE062A01020","totalArticles":2,"lastUpdated":1781447434901,"latestArticle":1780906190587}
```

```json
// File: news/metadata/instruments/INE075A01022.json
{"isin":"INE075A01022","totalArticles":7,"lastUpdated":1781447435052,"latestArticle":1781332567326}
```

```json
// File: news/metadata/instruments/INE07Y701011.json
{"isin":"INE07Y701011","totalArticles":1,"lastUpdated":1781447443636,"latestArticle":1781248424220}
```

```json
// File: news/metadata/instruments/INE081A01020.json
{"isin":"INE081A01020","totalArticles":4,"lastUpdated":1781447434912,"latestArticle":1781166173232}
```

```json
// File: news/metadata/instruments/INE090A01021.json
{"isin":"INE090A01021","totalArticles":2,"lastUpdated":1781447443174,"latestArticle":1781332567326}
```

```json
// File: news/metadata/instruments/INE095A01012.json
{"isin":"INE095A01012","totalArticles":1,"lastUpdated":1781447440523,"latestArticle":1781075957852}
```

```json
// File: news/metadata/instruments/INE0J1Y01017.json
{"isin":"INE0J1Y01017","totalArticles":1,"lastUpdated":1781447438224,"latestArticle":1780886006796}
```

```json
// File: news/metadata/instruments/INE0V6F01027.json
{"isin":"INE0V6F01027","totalArticles":3,"lastUpdated":1781447439637,"latestArticle":1781077374640}
```

```json
// File: news/metadata/instruments/INE101A01026.json
{"isin":"INE101A01026","totalArticles":3,"lastUpdated":1781447442741,"latestArticle":1781175583927}
```

```json
// File: news/metadata/instruments/INE121E01018.json
{"isin":"INE121E01018","totalArticles":1,"lastUpdated":1781447437417,"latestArticle":1780975699014}
```

```json
// File: news/metadata/instruments/INE134E01011.json
{"isin":"INE134E01011","totalArticles":3,"lastUpdated":1781447433805,"latestArticle":1781163081692}
```

```json
// File: news/metadata/instruments/INE155A01022.json
{"isin":"INE155A01022","totalArticles":5,"lastUpdated":1781447440361,"latestArticle":1781264551315}
```

```json
// File: news/metadata/instruments/INE171A01029.json
{"isin":"INE171A01029","totalArticles":3,"lastUpdated":1781447441461,"latestArticle":1781252951486}
```

```json
// File: news/metadata/instruments/INE192R01011.json
{"isin":"INE192R01011","totalArticles":3,"lastUpdated":1781447439845,"latestArticle":1781006386749}
```

```json
// File: news/metadata/instruments/INE205A01025.json
{"isin":"INE205A01025","totalArticles":5,"lastUpdated":1781447435143,"latestArticle":1781419291042}
```

```json
// File: news/metadata/instruments/INE213A01029.json
{"isin":"INE213A01029","totalArticles":3,"lastUpdated":1781447435919,"latestArticle":1781332567326}
```

```json
// File: news/metadata/instruments/INE237A01036.json
{"isin":"INE237A01036","totalArticles":1,"lastUpdated":1781447434022,"latestArticle":1781332567326}
```

```json
// File: news/metadata/instruments/INE238A01034.json
{"isin":"INE238A01034","totalArticles":2,"lastUpdated":1781447440704,"latestArticle":1781332567326}
```

```json
// File: news/metadata/instruments/INE239A01024.json
{"isin":"INE239A01024","totalArticles":4,"lastUpdated":1781447437128,"latestArticle":1781273568006}
```

```json
// File: news/metadata/instruments/INE242A01010.json
{"isin":"INE242A01010","totalArticles":2,"lastUpdated":1781447441032,"latestArticle":1781234130711}
```

```json
// File: news/metadata/instruments/INE263A01024.json
{"isin":"INE263A01024","totalArticles":3,"lastUpdated":1781447441177,"latestArticle":1780917274477}
```

```json
// File: news/metadata/instruments/INE267A01025.json
{"isin":"INE267A01025","totalArticles":1,"lastUpdated":1781447433163,"latestArticle":1781076230013}
```

```json
// File: news/metadata/instruments/INE274J01014.json
{"isin":"INE274J01014","totalArticles":1,"lastUpdated":1781447440428,"latestArticle":1781232986902}
```

```json
// File: news/metadata/instruments/INE280A01028.json
{"isin":"INE280A01028","totalArticles":2,"lastUpdated":1781447440319,"latestArticle":1781264551315}
```

```json
// File: news/metadata/instruments/INE296A01032.json
{"isin":"INE296A01032","totalArticles":5,"lastUpdated":1781447437457,"latestArticle":1781264551315}
```

```json
// File: news/metadata/instruments/INE326A01037.json
{"isin":"INE326A01037","totalArticles":1,"lastUpdated":1781447435663,"latestArticle":1780909010412}
```

```json
// File: news/metadata/instruments/INE377N01017.json
{"isin":"INE377N01017","totalArticles":1,"lastUpdated":1781447435146,"latestArticle":1780994528822}
```

```json
// File: news/metadata/instruments/INE397D01024.json
{"isin":"INE397D01024","totalArticles":6,"lastUpdated":1781447439856,"latestArticle":1781087106142}
```

```json
// File: news/metadata/instruments/INE415G01027.json
{"isin":"INE415G01027","totalArticles":1,"lastUpdated":1781447441968,"latestArticle":1780918365081}
```

```json
// File: news/metadata/instruments/INE423A01024.json
{"isin":"INE423A01024","totalArticles":1,"lastUpdated":1781447443627,"latestArticle":1780895617577}
```

```json
// File: news/metadata/instruments/INE437A01024.json
{"isin":"INE437A01024","totalArticles":1,"lastUpdated":1781447437150,"latestArticle":1780892719326}
```

```json
// File: news/metadata/instruments/INE455K01017.json
{"isin":"INE455K01017","totalArticles":2,"lastUpdated":1781447442626,"latestArticle":1780992961868}
```

```json
// File: news/metadata/instruments/INE467B01029.json
{"isin":"INE467B01029","totalArticles":8,"lastUpdated":1781447437685,"latestArticle":1781253125381}
```

```json
// File: news/metadata/instruments/INE476A01022.json
{"isin":"INE476A01022","totalArticles":1,"lastUpdated":1781447434206,"latestArticle":1780993166287}
```

```json
// File: news/metadata/instruments/INE522F01014.json
{"isin":"INE522F01014","totalArticles":2,"lastUpdated":1781447437435,"latestArticle":1781088957700}
```

```json
// File: news/metadata/instruments/INE646L01027.json
{"isin":"INE646L01027","totalArticles":6,"lastUpdated":1781447439636,"latestArticle":1781264551315}
```

```json
// File: news/metadata/instruments/INE669C01036.json
{"isin":"INE669C01036","totalArticles":3,"lastUpdated":1781447434870,"latestArticle":1781154186328}
```

```json
// File: news/metadata/instruments/INE669E01016.json
{"isin":"INE669E01016","totalArticles":4,"lastUpdated":1781447435666,"latestArticle":1781253125381}
```

```json
// File: news/metadata/instruments/INE721A01047.json
{"isin":"INE721A01047","totalArticles":4,"lastUpdated":1781447434213,"latestArticle":1781264551315}
```

```json
// File: news/metadata/instruments/INE733E01010.json
{"isin":"INE733E01010","totalArticles":3,"lastUpdated":1781447436825,"latestArticle":1780995252127}
```

```json
// File: news/metadata/instruments/INE742F01042.json
{"isin":"INE742F01042","totalArticles":1,"lastUpdated":1781447435398,"latestArticle":1780894555448}
```

```json
// File: news/metadata/instruments/INE752E01010.json
{"isin":"INE752E01010","totalArticles":3,"lastUpdated":1781447438770,"latestArticle":1780917274477}
```

```json
// File: news/metadata/instruments/INE758E01017.json
{"isin":"INE758E01017","totalArticles":3,"lastUpdated":1781447439092,"latestArticle":1781002812449}
```

```json
// File: news/metadata/instruments/INE758T01015.json
{"isin":"INE758T01015","totalArticles":9,"lastUpdated":1781447442428,"latestArticle":1781264551315}
```

```json
// File: news/metadata/instruments/INE849A01020.json
{"isin":"INE849A01020","totalArticles":3,"lastUpdated":1781447434763,"latestArticle":1781237907785}
```

```json
// File: news/metadata/instruments/INE860A01027.json
{"isin":"INE860A01027","totalArticles":4,"lastUpdated":1781447441348,"latestArticle":1781175583927}
```

```json
// File: news/metadata/instruments/INE917I01010.json
{"isin":"INE917I01010","totalArticles":1,"lastUpdated":1781447432983,"latestArticle":1780987251866}
```

```json
// File: news/metadata/instruments/INE931S01010.json
{"isin":"INE931S01010","totalArticles":1,"lastUpdated":1781447436051,"latestArticle":1780895617577}
```

```json
// File: news/metadata/instruments/INE944F01028.json
{"isin":"INE944F01028","totalArticles":1,"lastUpdated":1781447440426,"latestArticle":1780987251866}
```

```json
// File: news/metadata/instruments/INE982J01020.json
{"isin":"INE982J01020","totalArticles":1,"lastUpdated":1781447439481,"latestArticle":1781075957852}
```

```jsonl
// File: news/positions.jsonl

```

```jsonl
// File: user/holdings/holdings.jsonl
{
  "status": "success",
  "portfolio":"holdings",
  "data": [
    {
      "isin": "INE528G01035",
      "cnc_used_quantity": 0,
      "collateral_type": "WC",
      "company_name": "YES BANK LTD.",
      "haircut": 0.2,
      "product": "D",
      "quantity": 36,
      "trading_symbol": "YESBANK",
      "tradingsymbol": "YESBANK",
      "last_price": 17.05,
      "close_price": 17.05,
      "pnl": -61.2,
      "day_change": 0,
      "day_change_percentage": 0,
      "instrument_token": "NSE_EQ|INE528G01035",
      "average_price": 18.75,
      "collateral_quantity": 0,
      "collateral_update_quantity": 0,
      "t1_quantity": 0,
      "exchange": "NSE"
    },
    {
      "isin": "INE036A01016",
      "cnc_used_quantity": 0,
      "collateral_type": "WC",
      "company_name": "RELIANCE INFRASTRUCTURE LTD.",
      "haircut": 1,
      "product": "D",
      "quantity": 1,
      "trading_symbol": "RELINFRA",
      "tradingsymbol": "RELINFRA",
      "last_price": 174.85,
      "close_price": 169.2,
      "pnl": -17.7,
      "day_change": 0,
      "day_change_percentage": 0,
      "instrument_token": "NSE_EQ|INE036A01016",
      "average_price": 192.55,
      "collateral_quantity": 0,
      "collateral_update_quantity": 0,
      "t1_quantity": 0,
      "exchange": "NSE"
    }
  ]
}
```

```jsonl
// File: user/positions/positions.jsonl
{
  "status": "success",
  "portfolio":"positions",
  "data": [
    {
      "exchange": "NFO",
      "multiplier": 1.0,
      "value": 39.75,
      "pnl": 26.25,
      "product": "D",
      "instrument_token": "NSE_FO|52618",
      "average_price": 2.65,
      "buy_value": 0.0,
      "overnight_quantity": 15,
      "day_buy_value": 0.0,
      "day_buy_price": 0.0,
      "overnight_buy_amount": 39.75,
      "overnight_buy_quantity": 15,
      "day_buy_quantity": 0,
      "day_sell_value": 0.0,
      "day_sell_price": 0.0,
      "overnight_sell_amount": 0.0,
      "overnight_sell_quantity": 0,
      "day_sell_quantity": 0,
      "quantity": 15,
      "last_price": 1.75,
      "unrealised": -658304.25,
      "realised": -0.0,
      "sell_value": 0.0,
      "trading_symbol": "BANKNIFTY23OCT38000PE",
      "tradingsymbol": "BANKNIFTY23OCT38000PE",
      "close_price": 1.95,
      "buy_price": 2.65,
      "sell_price": 0.0
    },
    {
      "exchange": "BSE",
      "multiplier": 1.0,
      "value": 0.8,
      "pnl": 0.01,
      "product": "D",
      "instrument_token": "BSE_EQ|INE220J01025",
      "average_price": null,
      "buy_value": 0.8,
      "overnight_quantity": 0,
      "day_buy_value": 0.8,
      "day_buy_price": 0.8,
      "overnight_buy_amount": 0.0,
      "overnight_buy_quantity": 0,
      "day_buy_quantity": 1,
      "day_sell_value": 0.0,
      "day_sell_price": 0.0,
      "overnight_sell_amount": 0.0,
      "overnight_sell_quantity": 0,
      "day_sell_quantity": 0,
      "quantity": 1,
      "last_price": 0.81,
      "unrealised": 0.01,
      "realised": -0.0,
      "sell_value": 0.0,
      "trading_symbol": "FCONSUMER",
      "tradingsymbol": "FCONSUMER",
      "close_price": 0.8,
      "buy_price": 0.8,
      "sell_price": 0.0
    },
    {
      "exchange": "MCX",
      "multiplier": 1.0,
      "value": 5867.0,
      "pnl": 6005.0,
      "product": "D",
      "instrument_token": "MCX_FO|259711",
      "average_price": 5867.0,
      "buy_value": 0.0,
      "overnight_quantity": 1,
      "day_buy_value": 0.0,
      "day_buy_price": 0.0,
      "overnight_buy_amount": 5867.0,
      "overnight_buy_quantity": 1,
      "day_buy_quantity": 0,
      "day_sell_value": 0.0,
      "day_sell_price": 0.0,
      "overnight_sell_amount": 0.0,
      "overnight_sell_quantity": 0,
      "day_sell_quantity": 0,
      "quantity": 1,
      "last_price": 6005.0,
      "unrealised": 0.0,
      "realised": -0.0,
      "sell_value": 0.0,
      "trading_symbol": "GOLDPETAL23DECFUT",
      "tradingsymbol": "GOLDPETAL23DECFUT",
      "close_price": 6005.0,
      "buy_price": 5867.0,
      "sell_price": 0.0
    },
    {
      "exchange": "CDS",
      "multiplier": 1000.0,
      "value": 5.0,
      "pnl": 2.5,
      "product": "D",
      "instrument_token": "NCD_FO|13177",
      "average_price": 0.005,
      "buy_value": 0.0,
      "overnight_quantity": 1,
      "day_buy_value": 0.0,
      "day_buy_price": 0.0,
      "overnight_buy_amount": 0.005,
      "overnight_buy_quantity": 1,
      "day_buy_quantity": 0,
      "day_sell_value": 0.0,
      "day_sell_price": 0.0,
      "overnight_sell_amount": 0.0,
      "overnight_sell_quantity": 0,
      "day_sell_quantity": 0,
      "quantity": 1,
      "last_price": 0.0025,
      "unrealised": -83265.0,
      "realised": -0.0,
      "sell_value": 0.0,
      "trading_symbol": "USDINR23OCT85.5CE",
      "tradingsymbol": "USDINR23OCT85.5CE",
      "close_price": 0.0025,
      "buy_price": 0.005,
      "sell_price": 0.0
    },
    {
      "exchange": "NSE",
      "multiplier": 1.0,
      "value": 0.0,
      "pnl": 0.45,
      "product": "D",
      "instrument_token": "NSE_EQ|INE062A01020",
      "average_price": null,
      "buy_value": 570.95,
      "overnight_quantity": 0,
      "day_buy_value": 570.95,
      "day_buy_price": 570.95,
      "overnight_buy_amount": 0.0,
      "overnight_buy_quantity": 0,
      "day_buy_quantity": 1,
      "day_sell_value": 571.4,
      "day_sell_price": 571.4,
      "overnight_sell_amount": 0.0,
      "overnight_sell_quantity": 0,
      "day_sell_quantity": 1,
      "quantity": 0,
      "last_price": 571.2,
      "unrealised": 0.0,
      "realised": 0.45,
      "sell_value": 571.4,
      "trading_symbol": "SBIN",
      "tradingsymbol": "SBIN",
      "close_price": 572.65,
      "buy_price": 570.95,
      "sell_price": 571.4
    }
  ]
}
```
