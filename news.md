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
// File: src/main/java/com/vega/news/config/RequestLoggingFilter.java
package com.vega.news.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger reqLog = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            reqLog.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
        }
    }
}
```

```java
// File: src/main/java/com/vega/news/config/StorageInitializer.java
package com.vega.news.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageInitializer {

    private final NewsProperties properties;

    @PostConstruct
    public void verifyStorage() {
        log.info("Verifying storage directories...");
        
        File storageRoot = new File(properties.getStorage().getRoot());
        ensureDirectory(storageRoot, "Storage Root");

        ensureDirectory(new File(storageRoot, "instruments"), "Instruments Archive");
        ensureDirectory(new File(storageRoot, "metadata"), "Metadata");
        ensureDirectory(new File(storageRoot, "state"), "Collector State");

        // Note: holdings.jsonl and positions.jsonl are created by the collector,
        // so we don't necessarily want to create them as directories here.
        // But we should log if they are missing.
        
        checkFile(new File(properties.getStorage().getHoldingsView()), "Holdings View");
        checkFile(new File(properties.getStorage().getPositionsView()), "Positions View");
    }

    private void ensureDirectory(File dir, String name) {
        if (!dir.exists()) {
            log.warn("{} directory missing. Attempting to create: {}", name, dir.getAbsolutePath());
            if (dir.mkdirs()) {
                log.info("Successfully created {} directory.", name);
            } else {
                log.error("Failed to create {} directory!", name);
            }
        } else if (!dir.isDirectory()) {
            log.error("{} path exists but is NOT a directory: {}", name, dir.getAbsolutePath());
        } else {
            log.info("{} directory verified: {}", name, dir.getAbsolutePath());
        }
    }

    private void checkFile(File file, String name) {
        if (!file.exists()) {
            log.warn("{} file missing: {}. This will be created by the collector.", name, file.getAbsolutePath());
        } else {
            log.info("{} file verified: {}", name, file.getAbsolutePath());
        }
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

        File holdingsView = new File(properties.getStorage().getHoldingsView());
        boolean holdingsExists = holdingsView.exists() && holdingsView.isFile();

        File positionsView = new File(properties.getStorage().getPositionsView());
        boolean positionsExists = positionsView.exists() && positionsView.isFile();

        File metadataDir = new File(storageRoot, "metadata");
        boolean metadataExists = metadataDir.exists() && metadataDir.isDirectory();

        File instrumentsDir = new File(storageRoot, "instruments");
        boolean instrumentsExists = instrumentsDir.exists() && instrumentsDir.isDirectory();

        boolean allHealthy = instrumentLoaded && storageReachable && holdingsExists && positionsExists && metadataExists && instrumentsExists;

        Health.Builder builder = allHealthy ? Health.up() : Health.down();
        return builder
                .withDetail("instrumentsLoaded", instrumentLoaded)
                .withDetail("storageReachable", storageReachable)
                .withDetail("holdingsExists", holdingsExists)
                .withDetail("positionsExists", positionsExists)
                .withDetail("metadataExists", metadataExists)
                .withDetail("instrumentsDirExists", instrumentsExists)
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
import com.vega.news.model.NewsErrorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<?> getInstrumentNews(@PathVariable String isin) {
        log.info("Instrument news request received. ISIN={}", isin);
        
        if (isin == null || isin.trim().isEmpty() || !isin.matches("^[A-Z0-9]{12}$")) {
            return ResponseEntity.badRequest().body(NewsErrorResponse.builder()
                    .status("error")
                    .code("INVALID_ISIN")
                    .isin(isin)
                    .message("The provided ISIN is invalid.")
                    .build());
        }
        
        File file = new File(properties.getStorage().getRoot() + "/instruments/" + isin + ".jsonl");
        log.info("Archive lookup path={} exists={}", file.getAbsolutePath(), file.exists());

        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NewsErrorResponse.builder()
                    .status("error")
                    .code("ARCHIVE_NOT_FOUND")
                    .isin(isin)
                    .message("News archive not found for the requested ISIN.")
                    .build());
        }
        
        log.info("Serving archive file {}", file.getAbsolutePath());
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "/holdings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getHoldingsNews() {
        log.info("Holdings news request received.");
        File file = new File(properties.getStorage().getHoldingsView());
        log.info("Holdings view lookup path={} exists={}", file.getAbsolutePath(), file.exists());

        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NewsErrorResponse.builder()
                    .status("error")
                    .code("VIEW_NOT_FOUND")
                    .message("Holdings news view not found.")
                    .build());
        }
        
        log.info("Serving holdings file {}", file.getAbsolutePath());
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "/positions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getPositionsNews() {
        log.info("Positions news request received.");
        File file = new File(properties.getStorage().getPositionsView());
        log.info("Positions view lookup path={} exists={}", file.getAbsolutePath(), file.exists());

        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NewsErrorResponse.builder()
                    .status("error")
                    .code("VIEW_NOT_FOUND")
                    .message("Positions news view not found.")
                    .build());
        }
        
        log.info("Serving positions file {}", file.getAbsolutePath());
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
// File: src/main/java/com/vega/news/model/NewsErrorResponse.java
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
