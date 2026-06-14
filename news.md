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
            url += "&instrument_keys=" + instrumentKey;
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
        log.info("Vega News Application started.");
        log.info("Initialization complete. Scheduled tasks will handle news view generation shortly.");
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

import java.io.BufferedReader;
import java.io.IOException;
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
        Path holdingsPath = Paths.get("storage/user/holdings/holdings.jsonl");
        if (!Files.exists(holdingsPath)) {
            return isins;
        }

        try (BufferedReader reader = Files.newBufferedReader(holdingsPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode node = objectMapper.readTree(line);
                    if (node.has("isin")) {
                        isins.add(node.get("isin").asText());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse holding line", e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read holdings", e);
        }
        return isins;
    }

    public Set<String> readPositionsIsins() {
        Set<String> isins = new HashSet<>();
        Path positionsPath = Paths.get("storage/user/positions/positions.jsonl");
        if (!Files.exists(positionsPath)) {
            return isins;
        }

        try (BufferedReader reader = Files.newBufferedReader(positionsPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode node = objectMapper.readTree(line);
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
                    log.warn("Failed to parse position line", e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read positions", e);
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
  instrument-path: data/instruments/upstox/upstox.json
  auth:
    file: auth/upstox/auth.upstox.json

news:
  refresh:
    interval: 15m
  retention:
    days: 3650
  upstox:
    page-size: 100
  storage:
    root: storage/news
    holdings-view: storage/news/holdings.jsonl
    positions-view: storage/news/positions.jsonl
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
