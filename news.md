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
            <artifactId>spring-boot-starter-actuator</artifactId>
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
// File: src/main/java/com/vega/news/config/VegaNewsHealthIndicator.java
package com.vega.news.config;

import com.vega.news.service.InstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@RequiredArgsConstructor
public class VegaNewsHealthIndicator implements HealthIndicator {

    private final InstrumentService instrumentService;
    private final NewsProperties properties;

    @Override
    public Health health() {
        boolean instrumentLoaded = instrumentService.isLoaded();
        
        File storageRoot = new File(properties.getStorage().getRoot());
        boolean storageReachable = storageRoot.exists() && storageRoot.isDirectory() && storageRoot.canRead();

        if (instrumentLoaded && storageReachable) {
            return Health.up()
                    .withDetail("instrumentsLoaded", true)
                    .withDetail("storageReachable", true)
                    .build();
        }

        return Health.down()
                .withDetail("instrumentsLoaded", instrumentLoaded)
                .withDetail("storageReachable", storageReachable)
                .build();
    }
}
```

```java
// File: src/main/java/com/vega/news/controller/NewsController.java
package com.vega.news.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vega.news.config.NewsProperties;
import com.vega.news.model.NewsArticle;
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

    private final NewsProperties properties;

    @GetMapping(value = "/instrument/{isin}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> getInstrumentNews(@PathVariable String isin) {
        if (isin == null || isin.trim().isEmpty() || !isin.matches("^[A-Z0-9]{12}$")) {
            return ResponseEntity.badRequest().build();
        }
        
        File file = new File(properties.getStorage().getRoot() + "/instruments/" + isin + ".jsonl");
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
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

    public boolean isLoaded() {
        return !isinMap.isEmpty();
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
// File: src/main/java/com/vega/news/service/PortfolioNewsBuilderService.java
package com.vega.news.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PortfolioNewsBuilderService {

    // Views are now built entirely by the collector process.
    // This service is retained as per architectural directives,
    // potentially for future real-time view generation.
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
    root: ${news.vega-root}/storage/user/news
    holdings-view: ${news.vega-root}/storage/user/news/holdings.jsonl
    positions-view: ${news.vega-root}/storage/user/news/positions.jsonl
```

```jsonl
// File: user/news/instruments/INE006I01046.jsonl
{"isin": "INE006I01046", "instrumentKey": "NSE_EQ|INE006I01046", "heading": "Astral shares jump over 2% as subsidiary buys 60% in DSS worth \u20b939 crore; check details", "summary": "DSS is the only entity in India to possess a technology to produce a wide range of polyamines and very unique bismaleimides and benzoxazines (specialty chemicals)", "articleLink": "https://upstox.com/news/market-news/stocks/astral-shares-in-focus-as-subsidiary-buys-60-in-dss-worth-39-crore-check-details/article-195202/", "publishedTime": 1781239380279, "hash": "98f46192f5844aaff253b03ec9c56dea074d2dceda92d2ddede2e2f03f8687a2"}
```

```jsonl
// File: user/news/instruments/INE009A01021.jsonl
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list", "summary": "On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/", "publishedTime": 1781175583927, "hash": "8662e58006add6cf8f6a88dd62606dfaf7ef21f98879d4a2f8d680cca29badb4"}
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "TCS, Infosys, Tech Mahindra, other IT shares fall amid AI stocks sell-off on Wall Street", "summary": "Wall Street has been on shaky ground since last week, as artificial intelligence stocks have traded lower after reaching record highs.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-tech-mahindra-other-it-stocks-fall-amid-ai-stocks-sell-off-on-wall-street/article-195152/", "publishedTime": 1781154186328, "hash": "0ef341c9234cab441057738f11d2d43560ea11eb33a81923d3b9af746b851768"}
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump\u2019s $100,000 H-1B visa fee rule", "summary": "A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/", "publishedTime": 1780978356295, "hash": "94b5c2a562a22ca1f6fc2472ae19b1e4a418299d33d3c09c8eeda068a16cb1bc"}
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "hash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE00R701025.jsonl
{"isin": "INE00R701025", "instrumentKey": "NSE_EQ|INE00R701025", "heading": "Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE", "summary": "NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/", "publishedTime": 1780918365081, "hash": "df35c8aa9544c48b9dbaa16623a9c2fc662a5561d7c5990483ef56fad54b4840"}
```

```jsonl
// File: user/news/instruments/INE019A01038.jsonl
{"isin": "INE019A01038", "instrumentKey": "NSE_EQ|INE019A01038", "heading": "Redington, JSW Steel, DMart among buzzing stocks as SENSEX jumps over 270 pts, NIFTY above 23,200", "summary": "Grasim Industries shares gained 2% on the NSE on Tuesday, June 9, as it said it will invest \u20b93,094 crore for expanding its Lyocell capacity at Harihar, Karnataka, under phase II.\n", "articleLink": "https://upstox.com/news/market-news/stocks/redington-jsw-steel-d-mart-among-buzzing-stocks-as-sensex-jumps-over-170-pts-nifty-above-23-200/article-195029/", "publishedTime": 1780992961868, "hash": "358818ad16b24d83090792c0564e01f660ba99e3769286c56765ff7b5f6468fc"}
{"isin": "INE019A01038", "instrumentKey": "NSE_EQ|INE019A01038", "heading": "JSW Steel jumps 1% after 15% YoY growth in steel production", "summary": "Production was higher in May 2026, mainly due to full operations of the Dolvi unit (one of the blast furnaces was under planned maintenance shutdown in May 2025), and JVML operations fully ramped up. ", "articleLink": "https://upstox.com/news/market-news/stocks/jsw-steel-jumps-1-after-15-yo-y-growth-in-steel-production/article-195017/", "publishedTime": 1780982888844, "hash": "bfcce66d307cffc5682be10b42ec90db43fcf527c7f60f5a542ae71ed15d0182"}
```

```jsonl
// File: user/news/instruments/INE021A01026.jsonl
{"isin": "INE021A01026", "instrumentKey": "NSE_EQ|INE021A01026", "heading": "Asian Paints remains upbeat on long-term prospects; stock jumps 25% in a year", "summary": "Asian Paints expects the business environment to remain dynamic in FY27 amid heightened competition, commodity price movements, supply-chain risks, and geopolitical uncertainties, said its Managing Director and CEO Amit Syngle in the annual report.", "articleLink": "https://upstox.com/news/market-news/stocks/asian-paints-remains-upbeat-on-long-term-prospects-stock-jumps-25-in-a-year/article-195305/", "publishedTime": 1781495767578, "hash": "eae2c790e6c23611961b2388f429dc00f420ebab6235e9fdd46c3819f681fd18"}
{"isin": "INE021A01026", "instrumentKey": "NSE_EQ|INE021A01026", "heading": "Indian Oil, Asian Paints, JK Tyre, IndiGo shares jump as US-Iran peace deal sends oil prices tumbling", "summary": "\"The Deal with the Islamic Republic of Iran is now complete. Congratulations to all!\" US President Donald Trump wrote on his Truth Social platform. \"I hereby fully authorize the toll-free opening of the Strait of Hormuz, and, simultaneously herewith, authorize the immediate removal of the United States Naval blockade.\"", "articleLink": "https://upstox.com/news/market-news/stocks/indian-oil-asian-paints-jk-tyre-indi-go-in-focus-as-us-iran-peace-deal-sends-oil-prices-tumbling/article-195300/", "publishedTime": 1781496307908, "hash": "f519e8f51c8527d40695ef1465ee2b1aad4b7e8c51ae5e20925587b52309a664"}
{"isin": "INE021A01026", "instrumentKey": "NSE_EQ|INE021A01026", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "hash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
```

```jsonl
// File: user/news/instruments/INE027H01010.jsonl
{"isin": "INE027H01010", "instrumentKey": "NSE_EQ|INE027H01010", "heading": "Top gainers and losers, June 8: Wipro tumbles 8%, Jio Finserv, Eternal down 4%, Max Healthcare up 3%; check list", "summary": "On June 8, the S&P BSE SENSEX closed 719.09 points, or 0.97% lower at 73,524.26. Meanwhile, NSE\u2019s NIFTY50 ended at 23,123, reflecting a 243.70-point, or 1.04% decline.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-8-wipro-tumbles-8-jio-finserv-eternal-down-4-max-healthcare-up-3-check-list/article-194980/", "publishedTime": 1780918146424, "hash": "e78d9958ddec8e548a96198b575e79fb32327dc38470fe511fb3d1095293270a"}
{"isin": "INE027H01010", "instrumentKey": "NSE_EQ|INE027H01010", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "hash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
```

```jsonl
// File: user/news/instruments/INE028A01039.jsonl
{"isin": "INE028A01039", "instrumentKey": "NSE_EQ|INE028A01039", "heading": "Bank of Baroda, Canara Bank, Federal Bank shares rally; NIFTY Bank surges most in two months, here\u2019s why", "summary": "Banking stocks came under buying interest after the Reserve Bank of India (RBI) on Monday introduced a US dollar-rupee forex swap facility for fresh FCNR (B) deposits.", "articleLink": "https://upstox.com/news/market-news/stocks/bank-of-baroda-canara-bank-federal-bank-shares-rally-nifty-bank-surges-most-in-two-months-here-s-why/article-195034/", "publishedTime": 1780993166287, "hash": "d80c3e2cf1cf67ba961fc529beecd0122875ef088fefb506656fb96bc98eb96e"}
```

```jsonl
// File: user/news/instruments/INE030A01027.jsonl
{"isin": "INE030A01027", "instrumentKey": "NSE_EQ|INE030A01027", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "hash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
{"isin": "INE030A01027", "instrumentKey": "NSE_EQ|INE030A01027", "heading": "NIFTY50 above 23,375, SENSEX up 518 pts in noon deals; RIL, Clean Max Enviro, CMR Green Tech among buzzing stocks", "summary": "Shares of non-ferrous metal recycler CMR Green Technologies Ltd made a robust debut on the stock exchanges on Wednesday", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-above-23-375-sensex-up-518-pts-in-noon-deals-ril-clean-max-enviro-cmr-green-tech-among-buzzing-stocks/article-195104/", "publishedTime": 1781077374640, "hash": "d634df98c53995753c2fd759b35f92aa4e630e95528e0a587926fde52c167b8f"}
{"isin": "INE030A01027", "instrumentKey": "NSE_EQ|INE030A01027", "heading": "Why did HUL share price jump over 3% today? Check JP Morgan and other analysts' views", "summary": "Hindustan Unilever shares jumped more than 3% on June 10 as investors were shifting towards defensive stocks amid escalating geopolitical tensions and FMCG sectors' stable outlook for FY2027. ", "articleLink": "https://upstox.com/news/market-news/stocks/why-did-hul-share-price-jump-over-3-today-check-jp-morgan-and-other-analysts-views/article-195090/", "publishedTime": 1781071445298, "hash": "2b14e4aee6476fb635f2c10ac097d10fff285fa8adec04e642c472c07a020a0a"}
```

```jsonl
// File: user/news/instruments/INE047A01021.jsonl
{"isin": "INE047A01021", "instrumentKey": "NSE_EQ|INE047A01021", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "hash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE062A01020.jsonl
{"isin": "INE062A01020", "instrumentKey": "NSE_EQ|INE062A01020", "heading": "BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details", "summary": "Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ", "articleLink": "https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/", "publishedTime": 1780906190587, "hash": "c2ec158afea8e4e7d041bbc28d47443d11e32184002ba448514c351d5fd33eef"}
{"isin": "INE062A01020", "instrumentKey": "NSE_EQ|INE062A01020", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "hash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE075A01022.jsonl
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads", "summary": "A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran", "articleLink": "https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/", "publishedTime": 1781332567326, "hash": "974ccc4cc38a08ad5be1cc2bfe00f302dd6235053a5fd4846d3cac61fa4d6fd3"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "Wipro buyback update: \u20b915,000 crore share repurchase programme opens June 11; details here", "summary": "June 4 was the last day for market participants to buy Wipro shares to be eligible for the share buyback programme.", "articleLink": "https://upstox.com/news/market-news/stocks/wipro-buyback-update-15-000-crore-share-repurchase-programme-opens-june-11-details-here/article-195016/", "publishedTime": 1780986966340, "hash": "29959acaff1f835bcc54448f1089f553cf19b77467b5ec7aaffcb654d5e9cc4c"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump\u2019s $100,000 H-1B visa fee rule", "summary": "A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/", "publishedTime": 1780978356295, "hash": "94b5c2a562a22ca1f6fc2472ae19b1e4a418299d33d3c09c8eeda068a16cb1bc"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "Top gainers and losers, June 8: Wipro tumbles 8%, Jio Finserv, Eternal down 4%, Max Healthcare up 3%; check list", "summary": "On June 8, the S&P BSE SENSEX closed 719.09 points, or 0.97% lower at 73,524.26. Meanwhile, NSE\u2019s NIFTY50 ended at 23,123, reflecting a 243.70-point, or 1.04% decline.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-8-wipro-tumbles-8-jio-finserv-eternal-down-4-max-healthcare-up-3-check-list/article-194980/", "publishedTime": 1780918146424, "hash": "e78d9958ddec8e548a96198b575e79fb32327dc38470fe511fb3d1095293270a"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "hash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE", "summary": "NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/", "publishedTime": 1780918365081, "hash": "df35c8aa9544c48b9dbaa16623a9c2fc662a5561d7c5990483ef56fad54b4840"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "hash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE081A01020.jsonl
{"isin": "INE081A01020", "instrumentKey": "NSE_EQ|INE081A01020", "heading": "Adani Enterprises, Tata Steel, PNB, Tata Motors, Trent dividend record date on June 12: Last day to buy for payout", "summary": "Tata Steel has recommended a dividend of \u20b94 per share of face value of \u20b91 each to the shareholders of the company for FY2025-26. The record date is June 12.", "articleLink": "https://upstox.com/news/market-news/stocks/adani-enterprises-tata-steel-pnb-tata-motors-trent-dividend-record-date-on-june-12-last-day-to-buy-for-payout/article-195146/", "publishedTime": 1781166173232, "hash": "45e05299d99d4e605c62f52b025606c13bf9c0622f055f5dd95531686b275320"}
{"isin": "INE081A01020", "instrumentKey": "NSE_EQ|INE081A01020", "heading": "HG Infra, Zee Ent, Rajesh Exports among buzzing stocks as SENSEX falls over 400 pts, NIFTY below 23,300", "summary": "Shares of H.G. Infra Engineering rallied 10% on Monday, June 8, after it received the provisional completion certificate for executing the \u20b94,970.99 crore Ganga Expressway project in Uttar Pradesh.\n", "articleLink": "https://upstox.com/news/market-news/stocks/hg-infra-zee-ent-rajesh-exports-among-buzzing-stocks-as-sensex-falls-over-400-pts-nifty-below-23-300/article-194956/", "publishedTime": 1780903580292, "hash": "5c663b3333b3f7dd57d91fc35466db76e815187815148ce146f71791b2f80604"}
{"isin": "INE081A01020", "instrumentKey": "NSE_EQ|INE081A01020", "heading": "Tata Steel may defer UK low-carbon steel project by up to 8 months; shares slip 2%", "summary": "As part of its decarbonisation plan, Tata Steel is setting up the UK's largest low-carbon EAF (electric arc furnace) project of 3.2 million tonnes capacity at Port Talbot with 1.25 billion pounds of investment to replace its now-shut blast furnace plant of similar capacity.", "articleLink": "https://upstox.com/news/market-news/stocks/tata-steel-may-defer-uk-low-carbon-steel-project-by-up-to-8-months-amid-power-access-delays-shares-slip-2/article-194938/", "publishedTime": 1780891632666, "hash": "6dd15cbc4369e823bf12d28069ce40d51607208be05cd1a509da4fd0f5e6a354"}
{"isin": "INE081A01020", "instrumentKey": "NSE_EQ|INE081A01020", "heading": "Stocks to watch, June 8: Rajesh Exports, OMCs, LIC, Avanti Feeds, Apex Frozen, ixigo, Tata Steel, ZEEL", "summary": "Tata Steel may have to defer the timeline of its 1.25-billion-pound UK project for transitioning to a low-carbon steel-making process by six to eight months, as the company is facing delays in \"securing access to electricity\".", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-8-rajesh-exports-om-cs-lic-avanti-feeds-apex-frozen-ixigo-tata-steel-zeel/article-194924/", "publishedTime": 1780886006796, "hash": "b89b729c97fbf91f6b9eaa495fbfb5b3e6e3b0a18c089ab59e7e29eb7571c4cd"}
```

```jsonl
// File: user/news/instruments/INE121E01018.jsonl
{"isin": "INE121E01018", "instrumentKey": "NSE_EQ|INE121E01018", "heading": "Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL", "summary": "Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of \u20b9303 per share. The OFS opens for non-retail investors on Tuesday.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/", "publishedTime": 1780975699014, "hash": "9af36ebb0af7fe86ac20cbb37db130270ba63485b04ec054985702314e0c97a4"}
```

```jsonl
// File: user/news/instruments/INE134E01011.jsonl
{"isin": "INE134E01011", "instrumentKey": "NSE_EQ|INE134E01011", "heading": "TCS, Zee Ent, DOMS Industries among buzzing stocks as SENSEX gains over 200 pts, NIFTY above 23,200", "summary": "Shares of Doms Industries climbed as much as 7.33% to hit an intraday high of \u20b92,273.90 per unit on the NSE on Thursday, after the company entered into an agreement to buy Reynolds Pens India Limited.\n", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-zee-ent-doms-industries-among-buzzing-stocks-as-sensex-gains-over-200-pts-nifty-above-23-200/article-195172/", "publishedTime": 1781163081692, "hash": "85e04ca1d6ac8f3481e563d4459ed32df16783cec362ac9284ccbc8c88d68934"}
{"isin": "INE134E01011", "instrumentKey": "NSE_EQ|INE134E01011", "heading": "Stocks to watch, June 11: REC, PFC, Eternal, Power Grid, Lenskart Solutions, Meesho, Zee Entertainment", "summary": " Zomato and Blinkit's parent entity, Eternal, has received a \u20b99.63 crore GST demand notice, along with interest and penalty, from the Andhra Pradesh tax authorities.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-11-2026-rec-pfc-eternal-power-grid-lenskart-solutions-meesho-zee-entertainment/article-195142/", "publishedTime": 1781145776128, "hash": "17bd3e3723e25c89d2e82c51456135f0bba039dd1e073a5a45763b9dc083915c"}
{"isin": "INE134E01011", "instrumentKey": "NSE_EQ|INE134E01011", "heading": "REC receives presidential approval for proposed merger into PFC; what investors need to know", "summary": "Earlier, on February 9, PFC\u2019s board had given its in-principle approval for the merger of the Maharatna non-banking finance company REC with itself.\n", "articleLink": "https://upstox.com/news/market-news/stocks/rec-receives-presidential-approval-for-proposed-merger-into-pfc-what-investors-need-to-know/article-195133/", "publishedTime": 1781161079014, "hash": "d03a3e8f4e6a228f9a7995d8fec5c37803889b2fed9c7492655d4916ed00a571"}
```

```jsonl
// File: user/news/instruments/INE205A01025.jsonl
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger: Anil Agarwal sees $100 billion potential in each biz; hints at Vedanta Resources relisting", "summary": "Vedanta Ltd group's four demerged entities -- Vedanta Aluminium Metal, Vedanta Power, Vedanta Oil and Gas, and Vedanta Iron and Steel -- made their stock market debut on Monday.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-listing-anil-agarwal-sees-100-billion-potential-in-each-demerged-business-hints-at-vedanta-resources-relisting/article-195341/", "publishedTime": 1781514077889, "hash": "cbb68cf24dd663e52ebcdf622298c548f0493e9e2724ae02c0169b0e01851bdd"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta, OMCs, SEPC, IFCI, among buzzing stocks as SENSEX, NIFTY50 rally over 1% in noon deals", "summary": "Bharti Airtel stock rose as much as 1.17% to hit an intraday high of \u20b91,843.80 per equity share on the NSE, after announcing that nearly 100% of its shareholders have approved the ongoing transaction to consolidate its stake in key strategic subsidiary Airtel Africa plc.\n", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-om-cs-sepc-ifci-among-buzzing-stocks-as-sensex-nifty-50-rally-over-1-in-noon-deals/article-195332/", "publishedTime": 1781509425276, "hash": "33888ce75c28cfef92ade728a563522423cd19abb4a83ff2f85badb75e3eba1a"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta Aluminium lists at \u20b9522 on the NSE; all four demerged Vedanta entities now trading on exchanges", "summary": "The newly listed companies are Vedanta Oil and Gas Limited (formerly Malco Energy Limited), Vedanta Power Limited (formerly Talwandi Sabo Power Limited), Vedanta Aluminium Metal Limited, and Vedanta Iron and Steel Limited.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-aluminium-lists-at-522-on-the-nse-all-four-demerged-vedanta-entities-now-trading-on-exchanges/article-195311/", "publishedTime": 1781507457903, "hash": "45cc772044534c4209587841ad8f8141c49c633cc67ab2c7827e7d3780cf8cf9"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger: Vedanta Oil and Gas to list on June 15; key details shareholders need to know", "summary": "The equity shares of Vedanta Oil and Gas Ltd will begin trading on the NSE and BSE on June 15, 2026, as part of Vedanta Ltd's ongoing demerger exercise. The listing will create a separately traded oil and gas company, allowing investors to independently assess the business's operations, growth prospects, and valuation.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-demerger-vedanta-oil-and-gas-to-list-on-june-15-key-details-shareholders-need-to-know/article-195281/", "publishedTime": 1781409053613, "hash": "5d948ffbbe0d7a0419ec0511d6c6c1d3abe96387b662e656a692cee5b5e3bf69"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger: Four newly carved-out companies set for market debut on June 15; details to know", "summary": "In their notices and circulars, both BSE and NSE stated on Thursday, that the four demerged arms, namely, Vedanta Oil and Gas Limited, Vedanta Power Limited, Vedanta Aluminium Metal Limited, and Vedanta Iron And Steel Limited, shall be listed on June 15, 2026.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-demerger-four-entities-to-list-on-june-15-what-shareholders-need-to-know/article-195209/", "publishedTime": 1781419291042, "hash": "cfb391a4ce768d68937112b03d6ec37f118e61c0d5012faeb67c28a1d4d77db9"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger: What shareholders should know before Vedanta Power starts trading", "summary": "Vedanta demerger is aimed at unlocking shareholder value by creating pure-play businesses, allowing investors to gain targeted exposure to individual sectors such as power, aluminium, and other metals, instead of investing in Vedanta's diversified portfolio", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-demerger-what-shareholders-should-know-before-vedanta-power-starts-trading/article-195136/", "publishedTime": 1781108250005, "hash": "7d26070dbc0d4423fb0eb464472571bc642ef3a381aa8e0804f347a5a0892bdb"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger update: Oil & gas arm renamed Vedanta Oil and Gas from Malco Energy", "summary": "On Tuesday, June 9, Vedanta, in its filing to stock exchanges, said that the Registrar of Companies under the Ministry of Corporate Affairs (MCA) has approved the change in name of its oil & gas business from \u201cMalco Energy Limited\u201d to \u201cVedanta Oil and Gas Limited\u201d, with effect from June 9, 2026.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-demerger-update-oil-and-gas-arm-renamed-vedanta-oil-and-gas-from-malco-energy/article-195058/", "publishedTime": 1781496898846, "hash": "75d636e49ba6c710a61671f44f67b78ff5d5fa559e535708e9c6c03cec972fbd"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta shares in focus on rebranding its copper and nickel businesses; key details to know", "summary": "Vedanta Nico, renamed as Vedanta Nickel, will sharpen its focus on building a domestic nickel ecosystem and supporting the country's demand for critical minerals.\n", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-shares-in-focus-on-rebranding-its-copper-and-nickel-businesses-key-details-to-know/article-194982/", "publishedTime": 1780920773195, "hash": "ceed60433dbd01df97ab3546373c5bfdaf9f3130d8932900b24f900beb0e2935"}
```

```jsonl
// File: user/news/instruments/INE213A01029.jsonl
{"isin": "INE213A01029", "instrumentKey": "NSE_EQ|INE213A01029", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "hash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
{"isin": "INE213A01029", "instrumentKey": "NSE_EQ|INE213A01029", "heading": "Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads", "summary": "A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran", "articleLink": "https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/", "publishedTime": 1781332567326, "hash": "974ccc4cc38a08ad5be1cc2bfe00f302dd6235053a5fd4846d3cac61fa4d6fd3"}
{"isin": "INE213A01029", "instrumentKey": "NSE_EQ|INE213A01029", "heading": "Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics", "summary": "Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of \u20b9175 crore through open market transactions.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/", "publishedTime": 1781232986902, "hash": "10d7ec3171f1eb0eee6593b016ec2b73940ce6fb38d8f80504fa35e3c0db21bb"}
{"isin": "INE213A01029", "instrumentKey": "NSE_EQ|INE213A01029", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "hash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
```

```jsonl
// File: user/news/instruments/INE237A01036.jsonl
{"isin": "INE237A01036", "instrumentKey": "NSE_EQ|INE237A01036", "heading": "Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads", "summary": "A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran", "articleLink": "https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/", "publishedTime": 1781332567326, "hash": "974ccc4cc38a08ad5be1cc2bfe00f302dd6235053a5fd4846d3cac61fa4d6fd3"}
```

```jsonl
// File: user/news/instruments/INE239A01024.jsonl
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Vedanta, OMCs, SEPC, IFCI, among buzzing stocks as SENSEX, NIFTY50 rally over 1% in noon deals", "summary": "Bharti Airtel stock rose as much as 1.17% to hit an intraday high of \u20b91,843.80 per equity share on the NSE, after announcing that nearly 100% of its shareholders have approved the ongoing transaction to consolidate its stake in key strategic subsidiary Airtel Africa plc.\n", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-om-cs-sepc-ifci-among-buzzing-stocks-as-sensex-nifty-50-rally-over-1-in-noon-deals/article-195332/", "publishedTime": 1781509425276, "hash": "33888ce75c28cfef92ade728a563522423cd19abb4a83ff2f85badb75e3eba1a"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "hash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Nestle India shares in spotlight on rejecting claims of infestation in MAGGI noodles; key details", "summary": "Nestle said that it has already submitted a detailed representation, supported by all relevant facts, quality records from batch and market samples, and test reports to the competent authorities.\n", "articleLink": "https://upstox.com/news/market-news/stocks/nestle-india-shares-in-spotlight-on-rejecting-claims-of-infestation-in-maggi-noodles-key-details/article-195274/", "publishedTime": 1781273568006, "hash": "3ff4ec0ad17c6167f5569dc4c7d9a5e6667c4c5eb6f69ec0401e8aa6b3fa2da5"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list", "summary": "On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/", "publishedTime": 1781262446006, "hash": "695fd1b2df08bd00530b8322c0b7569b5143460d2e5585792c2b4ca0397b866d"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "hash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "hash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE267A01025.jsonl
{"isin": "INE267A01025", "instrumentKey": "NSE_EQ|INE267A01025", "heading": "Hindustan Zinc signs MoU with Sulfozyme Agro to advance sustainable metal recovery; stock falls", "summary": "Hindustan Zinc said that it entered into a partnership with Sulfozyme Agro India under its flagship Zinc Industrial Park initiative, set up at the Bhilwara district in Rajasthan. \n", "articleLink": "https://upstox.com/news/market-news/stocks/hindustan-zinc-signs-mo-u-with-sulfozyme-agro-to-advance-sustainable-metal-recovery-stock-falls/article-195100/", "publishedTime": 1781076230013, "hash": "9c1a3fb1f8c18a19a1536ae83068675a42186ddbdddea1887c445f7ee415aa3c"}
```

```jsonl
// File: user/news/instruments/INE296A01032.jsonl
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list", "summary": "On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/", "publishedTime": 1781262446006, "hash": "695fd1b2df08bd00530b8322c0b7569b5143460d2e5585792c2b4ca0397b866d"}
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "hash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "hash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter", "summary": "HDFC Bank reported net profit of \u20b919,221 crore in Q4FY26, marking an increase of 8% from \u20b917,616.14 crore in the year-ago period.", "articleLink": "https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/", "publishedTime": 1780987251866, "hash": "4048fe01ce51183a2647103708a97ce3b3c3e4b3068bd850f548cda6f4d7dbd2"}
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "hash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE326A01037.jsonl
{"isin": "INE326A01037", "instrumentKey": "NSE_EQ|INE326A01037", "heading": "Lupin partners with Spanish pharma company for launch of Luforbec inhalers in Spain; check stock performance", "summary": "Luforbec (beclometasone/formoterol) 100/6, which will be launched in Spain, is a fixed-dose combination in a pressurised metered-dose inhaler, Lupin said.\n", "articleLink": "https://upstox.com/news/market-news/stocks/lupin-partners-with-spanish-pharma-company-for-launch-of-luforbec-inhalers-in-spain-stock-falls/article-194966/", "publishedTime": 1780909010412, "hash": "95777dcca496a1064418b5fd4a755b9ac5c03c35724c0483bd46afdcf06f6218"}
```

```jsonl
// File: user/news/instruments/INE377N01017.jsonl
{"isin": "INE377N01017", "instrumentKey": "NSE_EQ|INE377N01017", "heading": "Waaree Energies secures order for supply of 800 megawatts solar modules, shares rise", "summary": "Waaree Energies in a regulatory filing said that a renowned customer placed an order for supply of 800 MW of solar modules.", "articleLink": "https://upstox.com/news/market-news/stocks/waaree-energies-secures-order-for-supply-of-800-megawatts-solar-modules-shears-rise/article-195337/", "publishedTime": 1781510482882, "hash": "f2c83ead1ffe72da26b38c4e7712ffe57c88e56bfaa44df76fba8b9a8a8857a2"}
{"isin": "INE377N01017", "instrumentKey": "NSE_EQ|INE377N01017", "heading": "Vikram Solar, Suzlon Energy, Waaree Energies: How renewable energy stocks are performing on June 9", "summary": "In May 2026, Colliers India released a report, 'The Green Shift: Renewable Prioritisation Reshaping Indian Real Estate', wherein it said that India will need around 7 lakh acres of land parcels, estimated to cost \u20b910-\u20b915 billion, in five years to set up solar and wind energy projects.", "articleLink": "https://upstox.com/news/market-news/stocks/vikram-solar-suzlon-energy-waaree-energies-how-renewable-energy-stocks-are-performing-on-june-9/article-195036/", "publishedTime": 1780994528822, "hash": "e4c368fca1b9dce87cbcf6b05e752a2fdbd397b42eba5a2bbb4c0cf32bf02337"}
```

```jsonl
// File: user/news/instruments/INE405E01023.jsonl
{"isin": "INE405E01023", "instrumentKey": "NSE_EQ|INE405E01023", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "hash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
```

```jsonl
// File: user/news/instruments/INE437A01024.jsonl
{"isin": "INE437A01024", "instrumentKey": "NSE_EQ|INE437A01024", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "hash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE465A01025.jsonl
{"isin": "INE465A01025", "instrumentKey": "NSE_EQ|INE465A01025", "heading": "Bharat Forge shares soar over 4% as its defence arm unveils MArG series mounted artillery guns at Eurosatory 2026", "summary": "The MArG series, as per Bharat Forge, is a truck-mounted artillery system built for manoeuvre and delivers a highly mobile, rapidly deployable, and cost-optimised firepower solution for modern land forces.\n\n", "articleLink": "https://upstox.com/news/market-news/stocks/bharat-forge-shares-soar-over-4-as-its-defence-arm-unveils-m-ar-g-series-mounted-artillery-guns-at-eurosatory-2026/article-195344/", "publishedTime": 1781514660635, "hash": "2cf07f14b5feeaea02816a4164a4c06242e0da71723a7328164531f3549fdcb3"}
```

```jsonl
// File: user/news/instruments/INE476A01022.jsonl
{"isin": "INE476A01022", "instrumentKey": "NSE_EQ|INE476A01022", "heading": "Bank of Baroda, Canara Bank, Federal Bank shares rally; NIFTY Bank surges most in two months, here\u2019s why", "summary": "Banking stocks came under buying interest after the Reserve Bank of India (RBI) on Monday introduced a US dollar-rupee forex swap facility for fresh FCNR (B) deposits.", "articleLink": "https://upstox.com/news/market-news/stocks/bank-of-baroda-canara-bank-federal-bank-shares-rally-nifty-bank-surges-most-in-two-months-here-s-why/article-195034/", "publishedTime": 1780993166287, "hash": "d80c3e2cf1cf67ba961fc529beecd0122875ef088fefb506656fb96bc98eb96e"}
```

```jsonl
// File: user/news/instruments/INE522F01014.jsonl
{"isin": "INE522F01014", "instrumentKey": "NSE_EQ|INE522F01014", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "hash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
{"isin": "INE522F01014", "instrumentKey": "NSE_EQ|INE522F01014", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "hash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE585B01010.jsonl
{"isin": "INE585B01010", "instrumentKey": "NSE_EQ|INE585B01010", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "hash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
```

```jsonl
// File: user/news/instruments/INE669C01036.jsonl
{"isin": "INE669C01036", "instrumentKey": "NSE_EQ|INE669C01036", "heading": "TCS, Infosys, Tech Mahindra, other IT shares fall amid AI stocks sell-off on Wall Street", "summary": "Wall Street has been on shaky ground since last week, as artificial intelligence stocks have traded lower after reaching record highs.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-tech-mahindra-other-it-stocks-fall-amid-ai-stocks-sell-off-on-wall-street/article-195152/", "publishedTime": 1781154186328, "hash": "0ef341c9234cab441057738f11d2d43560ea11eb33a81923d3b9af746b851768"}
{"isin": "INE669C01036", "instrumentKey": "NSE_EQ|INE669C01036", "heading": "TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump\u2019s $100,000 H-1B visa fee rule", "summary": "A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/", "publishedTime": 1780978356295, "hash": "94b5c2a562a22ca1f6fc2472ae19b1e4a418299d33d3c09c8eeda068a16cb1bc"}
{"isin": "INE669C01036", "instrumentKey": "NSE_EQ|INE669C01036", "heading": "BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details", "summary": "Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ", "articleLink": "https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/", "publishedTime": 1780906190587, "hash": "c2ec158afea8e4e7d041bbc28d47443d11e32184002ba448514c351d5fd33eef"}
```

```jsonl
// File: user/news/instruments/INE669E01016.jsonl
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "112 stocks hit 52-week high: IFCI, Bandhan Bank, Vi among 20 major stocks at one-year high; check full list", "summary": "The broader market was seen outperforming the main equity indices, with both the NIFTY Midcap 100 index and NIFTY Smallcap 100 climbing 1.8% to their intraday high levels ", "articleLink": "https://upstox.com/news/market-news/stocks/112-stocks-hit-52-week-high-ifci-bandhan-bank-vi-among-20-major-stocks-at-one-year-high-smi-ds-outperform-check-full-list/article-195335/", "publishedTime": 1781511247213, "hash": "acd5a367f5c856cbf85e5c98aec5b9c690bd8eb5bc90516779c9a9f4a77c4235"}
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "OMCs, Tata Motors PV, TCS among buzzing stocks as NIFTY up 1.4%, SENSEX climbs 1,202 pts in noon deals", "summary": "From the NIFTY firms, Shriram Finance, InterGlobe Aviation, Bajaj Finance, L&T, HDFC Bank, Jio Financial Services Eternal, Titan, Tata Motors Passenger Vehicles and were among the biggest winners.", "articleLink": "https://upstox.com/news/market-news/stocks/om-cs-tata-motors-pv-tcs-among-buzzing-stocks-as-nifty-up-0-77-sensex-climbs-729-pts-in-noon-deals/article-195250/", "publishedTime": 1781253125381, "hash": "068276d01372b66bf813757a7055edd7450f2250f00bf783ced1fa1b281269c5"}
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround", "summary": "Vodafone Idea reported a net profit of \u20b952,022 crore in March quarter compared with a loss of \u20b97,268 crore in the year-ago period and loss of \u20b95,324 crore in the previous quarter due to relief in statutory liabilities.", "articleLink": "https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/", "publishedTime": 1781075957852, "hash": "4cfc61b8d7241a5fbd871cb24a99f86b4eddf8d672df1c52ccc80b0f9bb76cef"}
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "Bharti Airtel, Vodafone Idea shares rise as Bombay HC quashes govt decision to impose 1-time spectrum charge", "summary": "In November 2012, the Union Cabinet took a decision that a one-time charge would be imposed for spectrum held beyond 6.2 MHz from July 2008 onwards. Following this, demand notices were issued to the petitioners (Bharti Airtel Ltd and Vodafone Idea Ltd) specifying the amounts payable by them towards one-time spectrum charge.", "articleLink": "https://upstox.com/news/market-news/stocks/bharti-airtel-vodafone-idea-shares-rise-as-bombay-hc-quashes-govt-decision-to-impose-1-time-spectrum-charge/article-195011/", "publishedTime": 1780978490137, "hash": "f9af2752c5cbe55cdd88878543700cfa644342c67b721515d4da89ecbe2feed3"}
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL", "summary": "Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of \u20b9303 per share. The OFS opens for non-retail investors on Tuesday.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/", "publishedTime": 1780975699014, "hash": "9af36ebb0af7fe86ac20cbb37db130270ba63485b04ec054985702314e0c97a4"}
```

```jsonl
// File: user/news/instruments/INE721A01047.jsonl
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list", "summary": "On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/", "publishedTime": 1781262446006, "hash": "695fd1b2df08bd00530b8322c0b7569b5143460d2e5585792c2b4ca0397b866d"}
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "hash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "hash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "hash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
```

```jsonl
// File: user/news/instruments/INE733E01010.jsonl
{"isin": "INE733E01010", "instrumentKey": "NSE_EQ|INE733E01010", "heading": "Defence minister approves 250 MW solar-BESS project on vacant defence land in UP's Sitapur", "summary": "NTPC Limited will implement the project through a competitive bidding process in coordination with the Integrated Headquarters of the Ministry of Defence (Army) and the Directorate General Defence Estates.", "articleLink": "https://upstox.com/news/business-news/latest-updates/defence-minister-approves-250-mw-solar-bess-project-on-vacant-defence-land-in-up-s-sitapur/article-195037/", "publishedTime": 1780995252127, "hash": "dbf0944b0e08dcb425a9d475d2287c322ff441793c5211e6dadb9aa0691dae37"}
{"isin": "INE733E01010", "instrumentKey": "NSE_EQ|INE733E01010", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "hash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
{"isin": "INE733E01010", "instrumentKey": "NSE_EQ|INE733E01010", "heading": "BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details", "summary": "Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ", "articleLink": "https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/", "publishedTime": 1780906190587, "hash": "c2ec158afea8e4e7d041bbc28d47443d11e32184002ba448514c351d5fd33eef"}
```

```jsonl
// File: user/news/instruments/INE742F01042.jsonl
{"isin": "INE742F01042", "instrumentKey": "NSE_EQ|INE742F01042", "heading": "Adani Ports secures 10-year marine services contract for Argentina's first LNG export; details here", "summary": "The contract has been awarded to APSEZ's step-down subsidiary, The Adani Harbour International FZCO, through a consortium with Argentina-based Meridian Group following a global competitive tender process conducted by Southern Energy S.A. (SESA). ", "articleLink": "https://upstox.com/news/market-news/stocks/adani-ports-secures-10-year-marine-services-contract-for-argentina-s-first-lng-export-details-here/article-194943/", "publishedTime": 1780894555448, "hash": "edad350fadd7c59c312d75ff3ae17813d91947e282db5c59577a92dafaa184cd"}
```

```jsonl
// File: user/news/instruments/INE849A01020.jsonl
{"isin": "INE849A01020", "instrumentKey": "NSE_EQ|INE849A01020", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "hash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
{"isin": "INE849A01020", "instrumentKey": "NSE_EQ|INE849A01020", "heading": "Adani Enterprises, Tata Steel, PNB, Tata Motors, Trent dividend record date on June 12: Last day to buy for payout", "summary": "Tata Steel has recommended a dividend of \u20b94 per share of face value of \u20b91 each to the shareholders of the company for FY2025-26. The record date is June 12.", "articleLink": "https://upstox.com/news/market-news/stocks/adani-enterprises-tata-steel-pnb-tata-motors-trent-dividend-record-date-on-june-12-last-day-to-buy-for-payout/article-195146/", "publishedTime": 1781166173232, "hash": "45e05299d99d4e605c62f52b025606c13bf9c0622f055f5dd95531686b275320"}
{"isin": "INE849A01020", "instrumentKey": "NSE_EQ|INE849A01020", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "hash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE917I01010.jsonl
{"isin": "INE917I01010", "instrumentKey": "NSE_EQ|INE917I01010", "heading": "From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter", "summary": "HDFC Bank reported net profit of \u20b919,221 crore in Q4FY26, marking an increase of 8% from \u20b917,616.14 crore in the year-ago period.", "articleLink": "https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/", "publishedTime": 1780987251866, "hash": "4048fe01ce51183a2647103708a97ce3b3c3e4b3068bd850f548cda6f4d7dbd2"}
```

```json
// File: user/news/metadata/INE006I01046.json
{
  "isin": "INE006I01046",
  "totalArticles": 1,
  "latestPublishedTime": 1781239380279,
  "lastUpdated": 1781516455321,
  "last_fetch": "2026-06-15T09:40:55.321353Z"
}
```

```json
// File: user/news/metadata/INE009A01021.json
{
  "isin": "INE009A01021",
  "totalArticles": 4,
  "latestPublishedTime": 1781175583927,
  "lastUpdated": 1781516452390,
  "last_fetch": "2026-06-15T09:40:52.390676Z"
}
```

```json
// File: user/news/metadata/INE00R701025.json
{
  "isin": "INE00R701025",
  "totalArticles": 1,
  "latestPublishedTime": 1780918365081,
  "lastUpdated": 1781516450835,
  "last_fetch": "2026-06-15T09:40:50.835143Z"
}
```

```json
// File: user/news/metadata/INE019A01038.json
{
  "isin": "INE019A01038",
  "totalArticles": 2,
  "latestPublishedTime": 1780992961868,
  "lastUpdated": 1781516460468,
  "last_fetch": "2026-06-15T09:41:00.468379Z"
}
```

```json
// File: user/news/metadata/INE021A01026.json
{
  "isin": "INE021A01026",
  "totalArticles": 3,
  "latestPublishedTime": 1781496307908,
  "lastUpdated": 1781516458920,
  "last_fetch": "2026-06-15T09:40:58.921017Z"
}
```

```json
// File: user/news/metadata/INE027H01010.json
{
  "isin": "INE027H01010",
  "totalArticles": 2,
  "latestPublishedTime": 1780918146424,
  "lastUpdated": 1781516456819,
  "last_fetch": "2026-06-15T09:40:56.819496Z"
}
```

```json
// File: user/news/metadata/INE028A01039.json
{
  "isin": "INE028A01039",
  "totalArticles": 1,
  "latestPublishedTime": 1780993166287,
  "lastUpdated": 1781516453975,
  "last_fetch": "2026-06-15T09:40:53.975324Z"
}
```

```json
// File: user/news/metadata/INE030A01027.json
{
  "isin": "INE030A01027",
  "totalArticles": 3,
  "latestPublishedTime": 1781088957700,
  "lastUpdated": 1781516453568,
  "last_fetch": "2026-06-15T09:40:53.568183Z"
}
```

```json
// File: user/news/metadata/INE047A01021.json
{
  "isin": "INE047A01021",
  "totalArticles": 1,
  "latestPublishedTime": 1780978075718,
  "lastUpdated": 1781516457462,
  "last_fetch": "2026-06-15T09:40:57.462103Z"
}
```

```json
// File: user/news/metadata/INE062A01020.json
{
  "isin": "INE062A01020",
  "totalArticles": 2,
  "latestPublishedTime": 1780906190587,
  "lastUpdated": 1781516455949,
  "last_fetch": "2026-06-15T09:40:55.949391Z"
}
```

```json
// File: user/news/metadata/INE075A01022.json
{
  "isin": "INE075A01022",
  "totalArticles": 7,
  "latestPublishedTime": 1781332567326,
  "lastUpdated": 1781516456319,
  "last_fetch": "2026-06-15T09:40:56.319185Z"
}
```

```json
// File: user/news/metadata/INE081A01020.json
{
  "isin": "INE081A01020",
  "totalArticles": 4,
  "latestPublishedTime": 1781166173232,
  "lastUpdated": 1781516456235,
  "last_fetch": "2026-06-15T09:40:56.235067Z"
}
```

```json
// File: user/news/metadata/INE121E01018.json
{
  "isin": "INE121E01018",
  "totalArticles": 1,
  "latestPublishedTime": 1780975699014,
  "lastUpdated": 1781516460530,
  "last_fetch": "2026-06-15T09:41:00.530403Z"
}
```

```json
// File: user/news/metadata/INE134E01011.json
{
  "isin": "INE134E01011",
  "totalArticles": 3,
  "latestPublishedTime": 1781163081692,
  "lastUpdated": 1781516452357,
  "last_fetch": "2026-06-15T09:40:52.357628Z"
}
```

```json
// File: user/news/metadata/INE205A01025.json
{
  "isin": "INE205A01025",
  "totalArticles": 8,
  "latestPublishedTime": 1781514077889,
  "lastUpdated": 1781516456787,
  "last_fetch": "2026-06-15T09:40:56.787985Z"
}
```

```json
// File: user/news/metadata/INE213A01029.json
{
  "isin": "INE213A01029",
  "totalArticles": 4,
  "latestPublishedTime": 1781490582777,
  "lastUpdated": 1781516458351,
  "last_fetch": "2026-06-15T09:40:58.351242Z"
}
```

```json
// File: user/news/metadata/INE237A01036.json
{
  "isin": "INE237A01036",
  "totalArticles": 1,
  "latestPublishedTime": 1781332567326,
  "lastUpdated": 1781516452945,
  "last_fetch": "2026-06-15T09:40:52.945167Z"
}
```

```json
// File: user/news/metadata/INE239A01024.json
{
  "isin": "INE239A01024",
  "totalArticles": 6,
  "latestPublishedTime": 1781509425276,
  "lastUpdated": 1781516459748,
  "last_fetch": "2026-06-15T09:40:59.748429Z"
}
```

```json
// File: user/news/metadata/INE267A01025.json
{
  "isin": "INE267A01025",
  "totalArticles": 1,
  "latestPublishedTime": 1781076230013,
  "lastUpdated": 1781516451257,
  "last_fetch": "2026-06-15T09:40:51.257106Z"
}
```

```json
// File: user/news/metadata/INE296A01032.json
{
  "isin": "INE296A01032",
  "totalArticles": 5,
  "latestPublishedTime": 1781264551315,
  "lastUpdated": 1781516460746,
  "last_fetch": "2026-06-15T09:41:00.746693Z"
}
```

```json
// File: user/news/metadata/INE326A01037.json
{
  "isin": "INE326A01037",
  "totalArticles": 1,
  "latestPublishedTime": 1780909010412,
  "lastUpdated": 1781516457427,
  "last_fetch": "2026-06-15T09:40:57.427925Z"
}
```

```json
// File: user/news/metadata/INE377N01017.json
{
  "isin": "INE377N01017",
  "totalArticles": 2,
  "latestPublishedTime": 1781510482882,
  "lastUpdated": 1781516456346,
  "last_fetch": "2026-06-15T09:40:56.346085Z"
}
```

```json
// File: user/news/metadata/INE405E01023.json
{
  "isin": "INE405E01023",
  "totalArticles": 1,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781516454345,
  "last_fetch": "2026-06-15T09:40:54.345689Z"
}
```

```json
// File: user/news/metadata/INE437A01024.json
{
  "isin": "INE437A01024",
  "totalArticles": 1,
  "latestPublishedTime": 1780892719326,
  "lastUpdated": 1781516459927,
  "last_fetch": "2026-06-15T09:40:59.927872Z"
}
```

```json
// File: user/news/metadata/INE465A01025.json
{
  "isin": "INE465A01025",
  "totalArticles": 1,
  "latestPublishedTime": 1781514660635,
  "lastUpdated": 1781516452831,
  "last_fetch": "2026-06-15T09:40:52.831546Z"
}
```

```json
// File: user/news/metadata/INE476A01022.json
{
  "isin": "INE476A01022",
  "totalArticles": 1,
  "latestPublishedTime": 1780993166287,
  "lastUpdated": 1781516453613,
  "last_fetch": "2026-06-15T09:40:53.613124Z"
}
```

```json
// File: user/news/metadata/INE522F01014.json
{
  "isin": "INE522F01014",
  "totalArticles": 2,
  "latestPublishedTime": 1781088957700,
  "lastUpdated": 1781516460547,
  "last_fetch": "2026-06-15T09:41:00.547121Z"
}
```

```json
// File: user/news/metadata/INE585B01010.json
{
  "isin": "INE585B01010",
  "totalArticles": 1,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781516450719,
  "last_fetch": "2026-06-15T09:40:50.719030Z"
}
```

```json
// File: user/news/metadata/INE669C01036.json
{
  "isin": "INE669C01036",
  "totalArticles": 3,
  "latestPublishedTime": 1781154186328,
  "lastUpdated": 1781516455775,
  "last_fetch": "2026-06-15T09:40:55.775541Z"
}
```

```json
// File: user/news/metadata/INE669E01016.json
{
  "isin": "INE669E01016",
  "totalArticles": 5,
  "latestPublishedTime": 1781511247213,
  "lastUpdated": 1781516457922,
  "last_fetch": "2026-06-15T09:40:57.922546Z"
}
```

```json
// File: user/news/metadata/INE721A01047.json
{
  "isin": "INE721A01047",
  "totalArticles": 4,
  "latestPublishedTime": 1781264551315,
  "lastUpdated": 1781516453795,
  "last_fetch": "2026-06-15T09:40:53.795391Z"
}
```

```json
// File: user/news/metadata/INE733E01010.json
{
  "isin": "INE733E01010",
  "totalArticles": 3,
  "latestPublishedTime": 1780995252127,
  "lastUpdated": 1781516459027,
  "last_fetch": "2026-06-15T09:40:59.027165Z"
}
```

```json
// File: user/news/metadata/INE742F01042.json
{
  "isin": "INE742F01042",
  "totalArticles": 1,
  "latestPublishedTime": 1780894555448,
  "lastUpdated": 1781516457379,
  "last_fetch": "2026-06-15T09:40:57.379582Z"
}
```

```json
// File: user/news/metadata/INE849A01020.json
{
  "isin": "INE849A01020",
  "totalArticles": 3,
  "latestPublishedTime": 1781237907785,
  "lastUpdated": 1781516455535,
  "last_fetch": "2026-06-15T09:40:55.535957Z"
}
```

```json
// File: user/news/metadata/INE917I01010.json
{
  "isin": "INE917I01010",
  "totalArticles": 1,
  "latestPublishedTime": 1780987251866,
  "lastUpdated": 1781516450719,
  "last_fetch": "2026-06-15T09:40:50.719968Z"
}
```

```json
// File: user/news/state/holdings_snapshot.json
[]
```

```json
// File: user/news/state/positions_snapshot.json
[]
```
