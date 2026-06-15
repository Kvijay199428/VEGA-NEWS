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
        long metadataCount = metadataExists ? countFiles(metadataDir, ".json") : 0;

        File instrumentsDir = new File(storageRoot, "instruments");
        boolean instrumentsExists = instrumentsDir.exists() && instrumentsDir.isDirectory();
        long archiveCount = instrumentsExists ? countFiles(instrumentsDir, ".jsonl") : 0;

        int expectedFno = instrumentService.getFnoInstrumentCount();

        // Health is UP if core infrastructure is ready, even if collector hasn't run yet.
        boolean allHealthy = instrumentLoaded && storageReachable && metadataExists && instrumentsExists;

        Health.Builder builder = allHealthy ? Health.up() : Health.down();
        return builder
                .withDetail("instrumentsLoaded", instrumentLoaded)
                .withDetail("storageReachable", storageReachable)
                .withDetail("metadataDirExists", metadataExists)
                .withDetail("metadataCount", metadataCount)
                .withDetail("instrumentsDirExists", instrumentsExists)
                .withDetail("archiveCount", archiveCount)
                .withDetail("expectedFno", expectedFno)
                .withDetail("holdingsExists", holdingsExists)
                .withDetail("positionsExists", positionsExists)
                .build();
    }

    private long countFiles(File dir, String extension) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(extension));
        return files != null ? files.length : 0;
    }
}
```

```java
// File: src/main/java/com/vega/news/controller/NewsController.java
package com.vega.news.controller;

import com.vega.news.config.NewsProperties;
import com.vega.news.model.NewsArticle;
import com.vega.news.model.NewsErrorResponse;
import com.vega.news.service.InstrumentService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsProperties properties;
    private final InstrumentService instrumentService;

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> getDiagnostics() {
        Map<String, Object> stats = new HashMap<>();
        
        File storageRoot = new File(properties.getStorage().getRoot());
        File metadataDir = new File(storageRoot, "metadata");
        File instrumentsDir = new File(storageRoot, "instruments");
        
        long metadataCount = countFiles(metadataDir, ".json");
        long archiveCount = countFiles(instrumentsDir, ".jsonl");
        int expectedFno = instrumentService.getFnoInstrumentCount();
        
        stats.put("fnoInstruments", expectedFno);
        stats.put("archives", archiveCount);
        stats.put("metadata", metadataCount);
        stats.put("missingArchives", Math.max(0, expectedFno - archiveCount));
        stats.put("storageRoot", storageRoot.getAbsolutePath());
        
        return ResponseEntity.ok(stats);
    }

    private long countFiles(File dir, String extension) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles((d, name) -> name.endsWith(extension));
        return files != null ? files.length : 0;
    }

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

    public int getFnoInstrumentCount() {
        return fnoEquities.size();
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
import com.vega.news.config.NewsProperties;
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
    private final NewsProperties properties;

    public Set<String> readHoldingsIsins() {
        Set<String> isins = new HashSet<>();
        Path holdingsPath = Paths.get(properties.getVegaRoot(), "storage/user/holdings/holdings.jsonl");
        if (!Files.exists(holdingsPath)) {
            log.warn("Holdings raw file missing at: {}", holdingsPath);
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
        Path positionsPath = Paths.get(properties.getVegaRoot(), "storage/user/positions/positions.jsonl");
        if (!Files.exists(positionsPath)) {
            log.warn("Positions raw file missing at: {}", positionsPath);
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
// File: user/news/holdings.jsonl

```

```jsonl
// File: user/news/instruments/INE002A01018.jsonl
{"isin": "INE002A01018", "instrumentKey": "NSE_EQ|INE002A01018", "heading": "Reliance Jio, NSE, Zepto among key upcoming IPOs: Will US-Iran peace deal revive India's IPO market?", "summary": "Reliance Jio IPO, along with NSE, Zepto and Flipkart, are some of the most anticipated IPOs of 2026. However, recent weak market sentiments because of the US-Iran war have slowed the primary market activity. But there is a strong likelihood of revival in the domestic IPO market after record-breaking funds raised by the SpaceX IPO and the signing of the US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/ipo/reliance-jio-nse-zepto-among-key-upcoming-ip-os-will-us-iran-peace-deal-revive-india-s-ipo-market/article-195325/", "publishedTime": 1781506978606, "sourceHash": "a84024f5b3d43fb8db03d8e76dc00f086384e524fac2b12e96cd1c9231d36c62"}
{"isin": "INE002A01018", "instrumentKey": "NSE_EQ|INE002A01018", "heading": "SENSEX, NIFTY50 erase gains to end on a flat note dragged by Reliance, Bharti Airtel", "summary": "Selling pressure was broad based as 12 of 15 major sector gauges compiled by the National Stock Exchange (NSE) ended lower led by the NIFTY Media index's over 2% fall.", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-nifty-50-erase-gains-to-end-on-a-flat-note-dragged-by-reliance-bharti-airtel/article-195118/", "publishedTime": 1781087106142, "sourceHash": "68ab5da18a71da03a7ed68df59ca2887c0a39058cf1d5a45d9591b3771dc631c"}
{"isin": "INE002A01018", "instrumentKey": "NSE_EQ|INE002A01018", "heading": "NIFTY50 above 23,375, SENSEX up 518 pts in noon deals; RIL, Clean Max Enviro, CMR Green Tech among buzzing stocks", "summary": "Shares of non-ferrous metal recycler CMR Green Technologies Ltd made a robust debut on the stock exchanges on Wednesday", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-above-23-375-sensex-up-518-pts-in-noon-deals-ril-clean-max-enviro-cmr-green-tech-among-buzzing-stocks/article-195104/", "publishedTime": 1781077374640, "sourceHash": "d634df98c53995753c2fd759b35f92aa4e630e95528e0a587926fde52c167b8f"}
{"isin": "INE002A01018", "instrumentKey": "NSE_EQ|INE002A01018", "heading": "Reliance Industries shares rally on AI data centre deal with Meta; what it means for the company and India", "summary": "Reliance will build a data center with 168 MW capacity in Jamnagar, Gujarat, which Meta will lease, with options to scale, news reports said.", "articleLink": "https://upstox.com/news/market-news/stocks/reliance-industries-shares-in-focus-as-meta-partners-for-ai-enabled-data-centre-push-report/article-195069/", "publishedTime": 1781067052760, "sourceHash": "6df783bf7656858c076cdfcacf745e7d01921ffcf3950f44daf6f7d5a6b34733"}
{"isin": "INE002A01018", "instrumentKey": "NSE_EQ|INE002A01018", "heading": "Stocks to watch, June 10: Afcons Infra, NLC India, Ajanta Pharma, Emcure Pharma, Adani Green, oil-sensitives", "summary": "ONGC, Oil India, and downstream companies' stocks, such as Indian Oil Corporation, HPCL, BPCL, will be in focus as oil prices rose on Wednesday after the US launched military strikes against Iran, raising concerns that renewed hostilities could threaten shipping through the Strait of Hormuz.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-10-afcons-infra-nlc-india-ajanta-pharma-emcure-pharma-adani-green-oil-sensitives/article-195063/", "publishedTime": 1781060155207, "sourceHash": "d10c77fdfc26cea04e8c86a2b73ceeda625a902fc214cb84aad0cb4c23cf53ec"}
{"isin": "INE002A01018", "instrumentKey": "NSE_EQ|INE002A01018", "heading": "Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE", "summary": "NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/", "publishedTime": 1780918365081, "sourceHash": "df35c8aa9544c48b9dbaa16623a9c2fc662a5561d7c5990483ef56fad54b4840"}
```

```jsonl
// File: user/news/instruments/INE003A01024.jsonl

```

```jsonl
// File: user/news/instruments/INE006I01046.jsonl
{"isin": "INE006I01046", "instrumentKey": "NSE_EQ|INE006I01046", "heading": "Astral shares jump over 2% as subsidiary buys 60% in DSS worth \u20b939 crore; check details", "summary": "DSS is the only entity in India to possess a technology to produce a wide range of polyamines and very unique bismaleimides and benzoxazines (specialty chemicals)", "articleLink": "https://upstox.com/news/market-news/stocks/astral-shares-in-focus-as-subsidiary-buys-60-in-dss-worth-39-crore-check-details/article-195202/", "publishedTime": 1781239380279, "sourceHash": "98f46192f5844aaff253b03ec9c56dea074d2dceda92d2ddede2e2f03f8687a2"}
```

```jsonl
// File: user/news/instruments/INE009A01021.jsonl
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "Infosys Q1 FY27 results schedule out: Check date, time, share price trend and other details", "summary": "Infosys will hold investor/analyst calls on July 23, 2026, to discuss earnings for the first quarter of the financial year 2026-27 and business outlook. ", "articleLink": "https://upstox.com/news/market-news/earnings/infosys-q1-fy-27-results-schedule-out-check-date-timing-share-price-trend-and-other-details/article-195351/", "publishedTime": 1781518917990, "sourceHash": "bd12fc268eaf3e3515f8d86e6fd437e9edaebe7ccce2775c5a929efc99f88a82"}
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list", "summary": "On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/", "publishedTime": 1781175583927, "sourceHash": "8662e58006add6cf8f6a88dd62606dfaf7ef21f98879d4a2f8d680cca29badb4"}
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "TCS, Infosys, Tech Mahindra, other IT shares fall amid AI stocks sell-off on Wall Street", "summary": "Wall Street has been on shaky ground since last week, as artificial intelligence stocks have traded lower after reaching record highs.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-tech-mahindra-other-it-stocks-fall-amid-ai-stocks-sell-off-on-wall-street/article-195152/", "publishedTime": 1781154186328, "sourceHash": "0ef341c9234cab441057738f11d2d43560ea11eb33a81923d3b9af746b851768"}
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump\u2019s $100,000 H-1B visa fee rule", "summary": "A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/", "publishedTime": 1780978356295, "sourceHash": "94b5c2a562a22ca1f6fc2472ae19b1e4a418299d33d3c09c8eeda068a16cb1bc"}
{"isin": "INE009A01021", "instrumentKey": "NSE_EQ|INE009A01021", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE00H001014.jsonl
{"isin": "INE00H001014", "instrumentKey": "NSE_EQ|INE00H001014", "heading": "Zepto vs Blinkit vs Instamart: Which quick commerce platform leads in revenue and profitability ahead of Zepto IPO?", "summary": "Zepto IPO has moved one step closer to its market debut after the company filed its updated DRHP with SEBI. As Zepto prepares to enter the public markets, here\u2019s a comparative look at how the company stacks up against its listed quick commerce peers, Eternal\u2019s Blinkit and Swiggy\u2019s Instamart, across key metrics such as revenue, profitability, and dark stores.", "articleLink": "https://upstox.com/news/market-news/ipo/zepto-vs-blinkit-vs-instamart-which-quick-commerce-platform-leads-in-revenue-and-profitability-ahead-of-zepto-ipo/article-195149/", "publishedTime": 1781153861680, "sourceHash": "d951018e2b17a2bb241309f3b8225c070504d52b8f1364e5a91ebca66ae3e441"}
{"isin": "INE00H001014", "instrumentKey": "NSE_EQ|INE00H001014", "heading": "Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE", "summary": "NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/", "publishedTime": 1780918365081, "sourceHash": "df35c8aa9544c48b9dbaa16623a9c2fc662a5561d7c5990483ef56fad54b4840"}
```

```jsonl
// File: user/news/instruments/INE00R701025.jsonl
{"isin": "INE00R701025", "instrumentKey": "NSE_EQ|INE00R701025", "heading": "Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE", "summary": "NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/", "publishedTime": 1780918365081, "sourceHash": "df35c8aa9544c48b9dbaa16623a9c2fc662a5561d7c5990483ef56fad54b4840"}
```

```jsonl
// File: user/news/instruments/INE010B01027.jsonl

```

```jsonl
// File: user/news/instruments/INE016A01026.jsonl
{"isin": "INE016A01026", "instrumentKey": "NSE_EQ|INE016A01026", "heading": "Dabur India shares in focus as US FDA issues import alert for Silvassa manufacturing plant", "summary": "Dabur India, in its filing to stock exchanges on Thursday, said that the US FDA had inspected the company\u2019s manufacturing plant situated at Silvassa, Dadra and Nagar Haveli, and had identified certain deficiencies on account of data integrity and maintenance lapses.", "articleLink": "https://upstox.com/news/market-news/stocks/dabur-india-shares-in-focus-as-us-fda-issues-import-alert-for-silvassa-manufacturing-plant/article-195222/", "publishedTime": 1781237178241, "sourceHash": "9daa94e5d9f1fa080d26b516a1752fcbb2c1022de91c67701f583300ae3c6bfa"}
{"isin": "INE016A01026", "instrumentKey": "NSE_EQ|INE016A01026", "heading": "Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics", "summary": "Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of \u20b9175 crore through open market transactions.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/", "publishedTime": 1781232986902, "sourceHash": "10d7ec3171f1eb0eee6593b016ec2b73940ce6fb38d8f80504fa35e3c0db21bb"}
```

```jsonl
// File: user/news/instruments/INE018A01030.jsonl
{"isin": "INE018A01030", "instrumentKey": "NSE_EQ|INE018A01030", "heading": "L&T stock rallies 4%, turns YTD positive; here\u2019s why the stock surged on Monday", "summary": "The shares of the company have, however, fallen 5% from their 52-week high of \u20b94,440 apiece, hit on February 24, 2026", "articleLink": "https://upstox.com/news/market-news/stocks/larsen-and-toubro-share-price-l-and-t-stock-rallies-4-turns-ytd-positive-here-s-why-the-stock-surged-on-monday/article-195348/", "publishedTime": 1781517389502, "sourceHash": "acd464b8ba095dde6bf07fd56f95f892a2cb2a8af818b3f0173d682ff2e69a17"}
{"isin": "INE018A01030", "instrumentKey": "NSE_EQ|INE018A01030", "heading": "Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list", "summary": "On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/", "publishedTime": 1781262446006, "sourceHash": "695fd1b2df08bd00530b8322c0b7569b5143460d2e5585792c2b4ca0397b866d"}
{"isin": "INE018A01030", "instrumentKey": "NSE_EQ|INE018A01030", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "sourceHash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE018A01030", "instrumentKey": "NSE_EQ|INE018A01030", "heading": "L&T shares rise nearly 4% amid renewed hopes of US-Iran peace deal", "summary": "L&T shares had hit its 52-week high of \u20b94,440 per share on February 24, 2026, while its 52-week low of \u20b93,288.10 apiece was recorded on March 23, 2026.", "articleLink": "https://upstox.com/news/market-news/stocks/l-and-t-shares-rise-nearly-4-amid-renewed-hopes-of-us-iran-peace-deal/article-195229/", "publishedTime": 1781238512275, "sourceHash": "3bf5d9bbfc80b4e6f694e88cca269aad3794c2d46dfb905868ea80fd4d520ca1"}
{"isin": "INE018A01030", "instrumentKey": "NSE_EQ|INE018A01030", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "sourceHash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
```

```jsonl
// File: user/news/instruments/INE018E01016.jsonl

```

```jsonl
// File: user/news/instruments/INE019A01038.jsonl
{"isin": "INE019A01038", "instrumentKey": "NSE_EQ|INE019A01038", "heading": "Redington, JSW Steel, DMart among buzzing stocks as SENSEX jumps over 270 pts, NIFTY above 23,200", "summary": "Grasim Industries shares gained 2% on the NSE on Tuesday, June 9, as it said it will invest \u20b93,094 crore for expanding its Lyocell capacity at Harihar, Karnataka, under phase II.\n", "articleLink": "https://upstox.com/news/market-news/stocks/redington-jsw-steel-d-mart-among-buzzing-stocks-as-sensex-jumps-over-170-pts-nifty-above-23-200/article-195029/", "publishedTime": 1780992961868, "sourceHash": "358818ad16b24d83090792c0564e01f660ba99e3769286c56765ff7b5f6468fc"}
{"isin": "INE019A01038", "instrumentKey": "NSE_EQ|INE019A01038", "heading": "JSW Steel jumps 1% after 15% YoY growth in steel production", "summary": "Production was higher in May 2026, mainly due to full operations of the Dolvi unit (one of the blast furnaces was under planned maintenance shutdown in May 2025), and JVML operations fully ramped up. ", "articleLink": "https://upstox.com/news/market-news/stocks/jsw-steel-jumps-1-after-15-yo-y-growth-in-steel-production/article-195017/", "publishedTime": 1780982888844, "sourceHash": "bfcce66d307cffc5682be10b42ec90db43fcf527c7f60f5a542ae71ed15d0182"}
```

```jsonl
// File: user/news/instruments/INE01EA01019.jsonl

```

```jsonl
// File: user/news/instruments/INE020B01018.jsonl
{"isin": "INE020B01018", "instrumentKey": "NSE_EQ|INE020B01018", "heading": "TCS, Zee Ent, DOMS Industries among buzzing stocks as SENSEX gains over 200 pts, NIFTY above 23,200", "summary": "Shares of Doms Industries climbed as much as 7.33% to hit an intraday high of \u20b92,273.90 per unit on the NSE on Thursday, after the company entered into an agreement to buy Reynolds Pens India Limited.\n", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-zee-ent-doms-industries-among-buzzing-stocks-as-sensex-gains-over-200-pts-nifty-above-23-200/article-195172/", "publishedTime": 1781163081692, "sourceHash": "85e04ca1d6ac8f3481e563d4459ed32df16783cec362ac9284ccbc8c88d68934"}
{"isin": "INE020B01018", "instrumentKey": "NSE_EQ|INE020B01018", "heading": "Stocks to watch, June 11: REC, PFC, Eternal, Power Grid, Lenskart Solutions, Meesho, Zee Entertainment", "summary": " Zomato and Blinkit's parent entity, Eternal, has received a \u20b99.63 crore GST demand notice, along with interest and penalty, from the Andhra Pradesh tax authorities.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-11-2026-rec-pfc-eternal-power-grid-lenskart-solutions-meesho-zee-entertainment/article-195142/", "publishedTime": 1781145776128, "sourceHash": "17bd3e3723e25c89d2e82c51456135f0bba039dd1e073a5a45763b9dc083915c"}
{"isin": "INE020B01018", "instrumentKey": "NSE_EQ|INE020B01018", "heading": "REC receives presidential approval for proposed merger into PFC; what investors need to know", "summary": "Earlier, on February 9, PFC\u2019s board had given its in-principle approval for the merger of the Maharatna non-banking finance company REC with itself.\n", "articleLink": "https://upstox.com/news/market-news/stocks/rec-receives-presidential-approval-for-proposed-merger-into-pfc-what-investors-need-to-know/article-195133/", "publishedTime": 1781161079014, "sourceHash": "d03a3e8f4e6a228f9a7995d8fec5c37803889b2fed9c7492655d4916ed00a571"}
```

```jsonl
// File: user/news/instruments/INE021A01026.jsonl
{"isin": "INE021A01026", "instrumentKey": "NSE_EQ|INE021A01026", "heading": "Asian Paints remains upbeat on long-term prospects; stock jumps 25% in a year", "summary": "Asian Paints expects the business environment to remain dynamic in FY27 amid heightened competition, commodity price movements, supply-chain risks, and geopolitical uncertainties, said its Managing Director and CEO Amit Syngle in the annual report.", "articleLink": "https://upstox.com/news/market-news/stocks/asian-paints-remains-upbeat-on-long-term-prospects-stock-jumps-25-in-a-year/article-195305/", "publishedTime": 1781495767578, "sourceHash": "eae2c790e6c23611961b2388f429dc00f420ebab6235e9fdd46c3819f681fd18"}
{"isin": "INE021A01026", "instrumentKey": "NSE_EQ|INE021A01026", "heading": "Indian Oil, Asian Paints, JK Tyre, IndiGo shares jump as US-Iran peace deal sends oil prices tumbling", "summary": "\"The Deal with the Islamic Republic of Iran is now complete. Congratulations to all!\" US President Donald Trump wrote on his Truth Social platform. \"I hereby fully authorize the toll-free opening of the Strait of Hormuz, and, simultaneously herewith, authorize the immediate removal of the United States Naval blockade.\"", "articleLink": "https://upstox.com/news/market-news/stocks/indian-oil-asian-paints-jk-tyre-indi-go-in-focus-as-us-iran-peace-deal-sends-oil-prices-tumbling/article-195300/", "publishedTime": 1781496307908, "sourceHash": "f519e8f51c8527d40695ef1465ee2b1aad4b7e8c51ae5e20925587b52309a664"}
{"isin": "INE021A01026", "instrumentKey": "NSE_EQ|INE021A01026", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "sourceHash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
```

```jsonl
// File: user/news/instruments/INE022Q01020.jsonl

```

```jsonl
// File: user/news/instruments/INE027H01010.jsonl
{"isin": "INE027H01010", "instrumentKey": "NSE_EQ|INE027H01010", "heading": "Top gainers and losers, June 8: Wipro tumbles 8%, Jio Finserv, Eternal down 4%, Max Healthcare up 3%; check list", "summary": "On June 8, the S&P BSE SENSEX closed 719.09 points, or 0.97% lower at 73,524.26. Meanwhile, NSE\u2019s NIFTY50 ended at 23,123, reflecting a 243.70-point, or 1.04% decline.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-8-wipro-tumbles-8-jio-finserv-eternal-down-4-max-healthcare-up-3-check-list/article-194980/", "publishedTime": 1780918146424, "sourceHash": "e78d9958ddec8e548a96198b575e79fb32327dc38470fe511fb3d1095293270a"}
{"isin": "INE027H01010", "instrumentKey": "NSE_EQ|INE027H01010", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
```

```jsonl
// File: user/news/instruments/INE028A01039.jsonl
{"isin": "INE028A01039", "instrumentKey": "NSE_EQ|INE028A01039", "heading": "Bank of Baroda, Canara Bank, Federal Bank shares rally; NIFTY Bank surges most in two months, here\u2019s why", "summary": "Banking stocks came under buying interest after the Reserve Bank of India (RBI) on Monday introduced a US dollar-rupee forex swap facility for fresh FCNR (B) deposits.", "articleLink": "https://upstox.com/news/market-news/stocks/bank-of-baroda-canara-bank-federal-bank-shares-rally-nifty-bank-surges-most-in-two-months-here-s-why/article-195034/", "publishedTime": 1780993166287, "sourceHash": "d80c3e2cf1cf67ba961fc529beecd0122875ef088fefb506656fb96bc98eb96e"}
```

```jsonl
// File: user/news/instruments/INE029A01011.jsonl
{"isin": "INE029A01011", "instrumentKey": "NSE_EQ|INE029A01011", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "sourceHash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
{"isin": "INE029A01011", "instrumentKey": "NSE_EQ|INE029A01011", "heading": "Govt bars bulk industrial petrol, diesel purchases through retail fuel outlets", "summary": "The order, issued under the Essential Commodities Act, aims to prevent diversion of supplies meant for retail consumers after authorities observed bulk buyers shifting to petrol pumps due to the lower retail prices compared with bulk rates.", "articleLink": "https://upstox.com/news/business-news/latest-updates/govt-bars-bulk-industrial-petrol-diesel-purchases-through-retail-fuel-outlets/article-195216/", "publishedTime": 1781234130711, "sourceHash": "a28f3fd76dcf3a831fcf388cddb654f0ea39033d25cf0f6d795876eb358c6c36"}
```

```jsonl
// File: user/news/instruments/INE030A01027.jsonl
{"isin": "INE030A01027", "instrumentKey": "NSE_EQ|INE030A01027", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "sourceHash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
{"isin": "INE030A01027", "instrumentKey": "NSE_EQ|INE030A01027", "heading": "NIFTY50 above 23,375, SENSEX up 518 pts in noon deals; RIL, Clean Max Enviro, CMR Green Tech among buzzing stocks", "summary": "Shares of non-ferrous metal recycler CMR Green Technologies Ltd made a robust debut on the stock exchanges on Wednesday", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-above-23-375-sensex-up-518-pts-in-noon-deals-ril-clean-max-enviro-cmr-green-tech-among-buzzing-stocks/article-195104/", "publishedTime": 1781077374640, "sourceHash": "d634df98c53995753c2fd759b35f92aa4e630e95528e0a587926fde52c167b8f"}
{"isin": "INE030A01027", "instrumentKey": "NSE_EQ|INE030A01027", "heading": "Why did HUL share price jump over 3% today? Check JP Morgan and other analysts' views", "summary": "Hindustan Unilever shares jumped more than 3% on June 10 as investors were shifting towards defensive stocks amid escalating geopolitical tensions and FMCG sectors' stable outlook for FY2027. ", "articleLink": "https://upstox.com/news/market-news/stocks/why-did-hul-share-price-jump-over-3-today-check-jp-morgan-and-other-analysts-views/article-195090/", "publishedTime": 1781071445298, "sourceHash": "2b14e4aee6476fb635f2c10ac097d10fff285fa8adec04e642c472c07a020a0a"}
```

```jsonl
// File: user/news/instruments/INE038A01020.jsonl
{"isin": "INE038A01020", "instrumentKey": "NSE_EQ|INE038A01020", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "sourceHash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
{"isin": "INE038A01020", "instrumentKey": "NSE_EQ|INE038A01020", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
{"isin": "INE038A01020", "instrumentKey": "NSE_EQ|INE038A01020", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE040A01034.jsonl
{"isin": "INE040A01034", "instrumentKey": "NSE_EQ|INE040A01034", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "sourceHash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE040A01034", "instrumentKey": "NSE_EQ|INE040A01034", "heading": "Dividend, bonus issue, stock splits next week: HDFC Bank, Tata Motors PV, Tata Tech, Torrent Power, among others in focus", "summary": "Shares of HDFC Bank will trade ex-record date on Friday, June 19, for its final dividend. On April 18, the country's largest private sector bank had recommended a final dividend of \u20b913 per equity share, with a face value of \u20b91 each for FY26. \n", "articleLink": "https://upstox.com/news/market-news/stocks/dividend-bonus-issue-stock-splits-next-week-hdfc-bank-tata-motors-pv-tata-tech-torrent-power-among-others-in-focus/article-195253/", "publishedTime": 1781262401321, "sourceHash": "a17f769439f18017d7ed3480c320e8b24ae53dabf5eed5efc3388941b15c848a"}
{"isin": "INE040A01034", "instrumentKey": "NSE_EQ|INE040A01034", "heading": "From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter", "summary": "HDFC Bank reported net profit of \u20b919,221 crore in Q4FY26, marking an increase of 8% from \u20b917,616.14 crore in the year-ago period.", "articleLink": "https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/", "publishedTime": 1780987251866, "sourceHash": "4048fe01ce51183a2647103708a97ce3b3c3e4b3068bd850f548cda6f4d7dbd2"}
```

```jsonl
// File: user/news/instruments/INE040H01021.jsonl
{"isin": "INE040H01021", "instrumentKey": "NSE_EQ|INE040H01021", "heading": "Vikram Solar, Suzlon Energy, Waaree Energies: How renewable energy stocks are performing on June 9", "summary": "In May 2026, Colliers India released a report, 'The Green Shift: Renewable Prioritisation Reshaping Indian Real Estate', wherein it said that India will need around 7 lakh acres of land parcels, estimated to cost \u20b910-\u20b915 billion, in five years to set up solar and wind energy projects.", "articleLink": "https://upstox.com/news/market-news/stocks/vikram-solar-suzlon-energy-waaree-energies-how-renewable-energy-stocks-are-performing-on-june-9/article-195036/", "publishedTime": 1780994528822, "sourceHash": "e4c368fca1b9dce87cbcf6b05e752a2fdbd397b42eba5a2bbb4c0cf32bf02337"}
```

```jsonl
// File: user/news/instruments/INE044A01036.jsonl
{"isin": "INE044A01036", "instrumentKey": "NSE_EQ|INE044A01036", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "sourceHash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
{"isin": "INE044A01036", "instrumentKey": "NSE_EQ|INE044A01036", "heading": "Sun Pharma dividend schedule: Board announces record date for \u20b95/share final dividend issue", "summary": "Sun Pharma announced the record date for its \u20b95 per share final dividend issue for the financial year ended 2025-26. Here's what investors should know.", "articleLink": "https://upstox.com/news/market-news/stocks/sun-pharma-dividend-schedule-board-announces-record-date-for-5-share-final-dividend-issue/article-195220/", "publishedTime": 1781233777283, "sourceHash": "25f3c92ec165fabe521e1a23a0c81b5747b45e98b1a8e6eba19c50ebdf4beb24"}
```

```jsonl
// File: user/news/instruments/INE047A01021.jsonl
{"isin": "INE047A01021", "instrumentKey": "NSE_EQ|INE047A01021", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE04I401011.jsonl

```

```jsonl
// File: user/news/instruments/INE053A01029.jsonl

```

```jsonl
// File: user/news/instruments/INE053F01010.jsonl

```

```jsonl
// File: user/news/instruments/INE059A01026.jsonl

```

```jsonl
// File: user/news/instruments/INE061F01013.jsonl
{"isin": "INE061F01013", "instrumentKey": "NSE_EQ|INE061F01013", "heading": "22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround", "summary": "Vodafone Idea reported a net profit of \u20b952,022 crore in March quarter compared with a loss of \u20b97,268 crore in the year-ago period and loss of \u20b95,324 crore in the previous quarter due to relief in statutory liabilities.", "articleLink": "https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/", "publishedTime": 1781075957852, "sourceHash": "4cfc61b8d7241a5fbd871cb24a99f86b4eddf8d672df1c52ccc80b0f9bb76cef"}
```

```jsonl
// File: user/news/instruments/INE062A01020.jsonl
{"isin": "INE062A01020", "instrumentKey": "NSE_EQ|INE062A01020", "heading": "BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details", "summary": "Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ", "articleLink": "https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/", "publishedTime": 1780906190587, "sourceHash": "c2ec158afea8e4e7d041bbc28d47443d11e32184002ba448514c351d5fd33eef"}
{"isin": "INE062A01020", "instrumentKey": "NSE_EQ|INE062A01020", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE066A01021.jsonl
{"isin": "INE066A01021", "instrumentKey": "NSE_EQ|INE066A01021", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "sourceHash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
```

```jsonl
// File: user/news/instruments/INE066F01020.jsonl

```

```jsonl
// File: user/news/instruments/INE066P01011.jsonl

```

```jsonl
// File: user/news/instruments/INE067A01029.jsonl

```

```jsonl
// File: user/news/instruments/INE070A01015.jsonl

```

```jsonl
// File: user/news/instruments/INE073K01018.jsonl

```

```jsonl
// File: user/news/instruments/INE075A01022.jsonl
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads", "summary": "A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran", "articleLink": "https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/", "publishedTime": 1781332567326, "sourceHash": "974ccc4cc38a08ad5be1cc2bfe00f302dd6235053a5fd4846d3cac61fa4d6fd3"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "Wipro buyback update: \u20b915,000 crore share repurchase programme opens June 11; details here", "summary": "June 4 was the last day for market participants to buy Wipro shares to be eligible for the share buyback programme.", "articleLink": "https://upstox.com/news/market-news/stocks/wipro-buyback-update-15-000-crore-share-repurchase-programme-opens-june-11-details-here/article-195016/", "publishedTime": 1780986966340, "sourceHash": "29959acaff1f835bcc54448f1089f553cf19b77467b5ec7aaffcb654d5e9cc4c"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump\u2019s $100,000 H-1B visa fee rule", "summary": "A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/", "publishedTime": 1780978356295, "sourceHash": "94b5c2a562a22ca1f6fc2472ae19b1e4a418299d33d3c09c8eeda068a16cb1bc"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "Top gainers and losers, June 8: Wipro tumbles 8%, Jio Finserv, Eternal down 4%, Max Healthcare up 3%; check list", "summary": "On June 8, the S&P BSE SENSEX closed 719.09 points, or 0.97% lower at 73,524.26. Meanwhile, NSE\u2019s NIFTY50 ended at 23,123, reflecting a 243.70-point, or 1.04% decline.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-8-wipro-tumbles-8-jio-finserv-eternal-down-4-max-healthcare-up-3-check-list/article-194980/", "publishedTime": 1780918146424, "sourceHash": "e78d9958ddec8e548a96198b575e79fb32327dc38470fe511fb3d1095293270a"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE", "summary": "NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/", "publishedTime": 1780918365081, "sourceHash": "df35c8aa9544c48b9dbaa16623a9c2fc662a5561d7c5990483ef56fad54b4840"}
{"isin": "INE075A01022", "instrumentKey": "NSE_EQ|INE075A01022", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE079A01024.jsonl

```

```jsonl
// File: user/news/instruments/INE07Y701011.jsonl
{"isin": "INE07Y701011", "instrumentKey": "NSE_EQ|INE07Y701011", "heading": "Hitachi Energy shares rise 4% on plans to invest \u20b92,000 crore for Vadodara power transformer factory", "summary": "The factory is scheduled for completion in FY28 and will manufacture a significant volume of power transformers annually to enable faster delivery of mission-critical grid equipment, Hitachi Energy India said. \n", "articleLink": "https://upstox.com/news/market-news/stocks/hitachi-energy-shares-rise-4-on-plans-to-invest-2-000-crore-for-vadodara-power-transformer-factory/article-195246/", "publishedTime": 1781248424220, "sourceHash": "073c8595867399ae27ce3744cb676a332a9d87daecb0a0f63eb9ed163b04583a"}
```

```jsonl
// File: user/news/instruments/INE081A01020.jsonl
{"isin": "INE081A01020", "instrumentKey": "NSE_EQ|INE081A01020", "heading": "Adani Enterprises, Tata Steel, PNB, Tata Motors, Trent dividend record date on June 12: Last day to buy for payout", "summary": "Tata Steel has recommended a dividend of \u20b94 per share of face value of \u20b91 each to the shareholders of the company for FY2025-26. The record date is June 12.", "articleLink": "https://upstox.com/news/market-news/stocks/adani-enterprises-tata-steel-pnb-tata-motors-trent-dividend-record-date-on-june-12-last-day-to-buy-for-payout/article-195146/", "publishedTime": 1781166173232, "sourceHash": "45e05299d99d4e605c62f52b025606c13bf9c0622f055f5dd95531686b275320"}
{"isin": "INE081A01020", "instrumentKey": "NSE_EQ|INE081A01020", "heading": "HG Infra, Zee Ent, Rajesh Exports among buzzing stocks as SENSEX falls over 400 pts, NIFTY below 23,300", "summary": "Shares of H.G. Infra Engineering rallied 10% on Monday, June 8, after it received the provisional completion certificate for executing the \u20b94,970.99 crore Ganga Expressway project in Uttar Pradesh.\n", "articleLink": "https://upstox.com/news/market-news/stocks/hg-infra-zee-ent-rajesh-exports-among-buzzing-stocks-as-sensex-falls-over-400-pts-nifty-below-23-300/article-194956/", "publishedTime": 1780903580292, "sourceHash": "5c663b3333b3f7dd57d91fc35466db76e815187815148ce146f71791b2f80604"}
{"isin": "INE081A01020", "instrumentKey": "NSE_EQ|INE081A01020", "heading": "Tata Steel may defer UK low-carbon steel project by up to 8 months; shares slip 2%", "summary": "As part of its decarbonisation plan, Tata Steel is setting up the UK's largest low-carbon EAF (electric arc furnace) project of 3.2 million tonnes capacity at Port Talbot with 1.25 billion pounds of investment to replace its now-shut blast furnace plant of similar capacity.", "articleLink": "https://upstox.com/news/market-news/stocks/tata-steel-may-defer-uk-low-carbon-steel-project-by-up-to-8-months-amid-power-access-delays-shares-slip-2/article-194938/", "publishedTime": 1780891632666, "sourceHash": "6dd15cbc4369e823bf12d28069ce40d51607208be05cd1a509da4fd0f5e6a354"}
{"isin": "INE081A01020", "instrumentKey": "NSE_EQ|INE081A01020", "heading": "Stocks to watch, June 8: Rajesh Exports, OMCs, LIC, Avanti Feeds, Apex Frozen, ixigo, Tata Steel, ZEEL", "summary": "Tata Steel may have to defer the timeline of its 1.25-billion-pound UK project for transitioning to a low-carbon steel-making process by six to eight months, as the company is facing delays in \"securing access to electricity\".", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-8-rajesh-exports-om-cs-lic-avanti-feeds-apex-frozen-ixigo-tata-steel-zeel/article-194924/", "publishedTime": 1780886006796, "sourceHash": "b89b729c97fbf91f6b9eaa495fbfb5b3e6e3b0a18c089ab59e7e29eb7571c4cd"}
```

```jsonl
// File: user/news/instruments/INE084A01016.jsonl

```

```jsonl
// File: user/news/instruments/INE089A01031.jsonl

```

```jsonl
// File: user/news/instruments/INE090A01021.jsonl
{"isin": "INE090A01021", "instrumentKey": "NSE_EQ|INE090A01021", "heading": "Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads", "summary": "A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran", "articleLink": "https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/", "publishedTime": 1781332567326, "sourceHash": "974ccc4cc38a08ad5be1cc2bfe00f302dd6235053a5fd4846d3cac61fa4d6fd3"}
{"isin": "INE090A01021", "instrumentKey": "NSE_EQ|INE090A01021", "heading": "Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list", "summary": "On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/", "publishedTime": 1781175583927, "sourceHash": "8662e58006add6cf8f6a88dd62606dfaf7ef21f98879d4a2f8d680cca29badb4"}
```

```jsonl
// File: user/news/instruments/INE092T01019.jsonl

```

```jsonl
// File: user/news/instruments/INE093I01010.jsonl

```

```jsonl
// File: user/news/instruments/INE094A01015.jsonl

```

```jsonl
// File: user/news/instruments/INE095A01012.jsonl
{"isin": "INE095A01012", "instrumentKey": "NSE_EQ|INE095A01012", "heading": "22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround", "summary": "Vodafone Idea reported a net profit of \u20b952,022 crore in March quarter compared with a loss of \u20b97,268 crore in the year-ago period and loss of \u20b95,324 crore in the previous quarter due to relief in statutory liabilities.", "articleLink": "https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/", "publishedTime": 1781075957852, "sourceHash": "4cfc61b8d7241a5fbd871cb24a99f86b4eddf8d672df1c52ccc80b0f9bb76cef"}
```

```jsonl
// File: user/news/instruments/INE095N01031.jsonl

```

```jsonl
// File: user/news/instruments/INE0BS701011.jsonl

```

```jsonl
// File: user/news/instruments/INE0J1Y01017.jsonl
{"isin": "INE0J1Y01017", "instrumentKey": "NSE_EQ|INE0J1Y01017", "heading": "Stocks to watch, June 8: Rajesh Exports, OMCs, LIC, Avanti Feeds, Apex Frozen, ixigo, Tata Steel, ZEEL", "summary": "Tata Steel may have to defer the timeline of its 1.25-billion-pound UK project for transitioning to a low-carbon steel-making process by six to eight months, as the company is facing delays in \"securing access to electricity\".", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-8-rajesh-exports-om-cs-lic-avanti-feeds-apex-frozen-ixigo-tata-steel-zeel/article-194924/", "publishedTime": 1780886006796, "sourceHash": "b89b729c97fbf91f6b9eaa495fbfb5b3e6e3b0a18c089ab59e7e29eb7571c4cd"}
```

```jsonl
// File: user/news/instruments/INE0V6F01027.jsonl
{"isin": "INE0V6F01027", "instrumentKey": "NSE_EQ|INE0V6F01027", "heading": "NIFTY50 above 23,375, SENSEX up 518 pts in noon deals; RIL, Clean Max Enviro, CMR Green Tech among buzzing stocks", "summary": "Shares of non-ferrous metal recycler CMR Green Technologies Ltd made a robust debut on the stock exchanges on Wednesday", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-above-23-375-sensex-up-518-pts-in-noon-deals-ril-clean-max-enviro-cmr-green-tech-among-buzzing-stocks/article-195104/", "publishedTime": 1781077374640, "sourceHash": "d634df98c53995753c2fd759b35f92aa4e630e95528e0a587926fde52c167b8f"}
{"isin": "INE0V6F01027", "instrumentKey": "NSE_EQ|INE0V6F01027", "heading": "Hyundai Motor India expects production at Chennai plant to normalise by June 22 after fire incident; here\u2019s how the stock is faring", "summary": "The carmaker expects that any loss of production arising due to the fire incident shall be mostly recovered within the next quarter itself", "articleLink": "https://upstox.com/news/market-news/stocks/hyundai-motor-india-expects-production-at-chennai-plant-to-normalise-by-june-22-after-fire-incident-here-s-how-the-stock-in-faring/article-195095/", "publishedTime": 1781072929036, "sourceHash": "07ee2376933d6481ac002b5389c10f364e7cb150ade620fa8eec8a7c0019ed6f"}
{"isin": "INE0V6F01027", "instrumentKey": "NSE_EQ|INE0V6F01027", "heading": "Hyundai Motor India expects Chennai plant output to normalise by June 22 after supplier fire", "summary": "Hyundai expects to recover most of the production losses within the next quarter and does not anticipate any significant impact on June retail sales due to adequate inventory across its dealer network.", "articleLink": "https://upstox.com/news/business-news/latest-updates/hyundai-motor-india-expects-chennai-plant-output-to-normalise-by-june-22-after-supplier-fire/article-195091/", "publishedTime": 1781069990581, "sourceHash": "9a337f00dc82ae8405f7ec4709511b941eeda5ac18e5bcc1d541e65fe3fca768"}
```

```jsonl
// File: user/news/instruments/INE101A01026.jsonl
{"isin": "INE101A01026", "instrumentKey": "NSE_EQ|INE101A01026", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "sourceHash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
{"isin": "INE101A01026", "instrumentKey": "NSE_EQ|INE101A01026", "heading": "Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list", "summary": "On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/", "publishedTime": 1781175583927, "sourceHash": "8662e58006add6cf8f6a88dd62606dfaf7ef21f98879d4a2f8d680cca29badb4"}
{"isin": "INE101A01026", "instrumentKey": "NSE_EQ|INE101A01026", "heading": "Mahindra & Mahindra shares recover 4% from intraday low; here's why the stock is buzzing on Thursday", "summary": "After opening at \u20b92,930 apiece, M&M shares had slipped nearly 2% to an intraday low of \u20b92,900.40 apiece from its previous close", "articleLink": "https://upstox.com/news/market-news/stocks/mahindra-and-mahindra-m-and-m-shares-recover-4-from-intraday-low-here-s-why-the-stock-is-buzzing-on-thursday/article-195183/", "publishedTime": 1781169153685, "sourceHash": "14a0204a1dcb81ca284fe95c1abf4a71a720aa97580736437cdd70c241f775ee"}
{"isin": "INE101A01026", "instrumentKey": "NSE_EQ|INE101A01026", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE102D01028.jsonl

```

```jsonl
// File: user/news/instruments/INE111A01025.jsonl

```

```jsonl
// File: user/news/instruments/INE114A01011.jsonl

```

```jsonl
// File: user/news/instruments/INE115A01026.jsonl

```

```jsonl
// File: user/news/instruments/INE117A01022.jsonl

```

```jsonl
// File: user/news/instruments/INE118A01012.jsonl

```

```jsonl
// File: user/news/instruments/INE118H01025.jsonl

```

```jsonl
// File: user/news/instruments/INE121A01024.jsonl

```

```jsonl
// File: user/news/instruments/INE121E01018.jsonl
{"isin": "INE121E01018", "instrumentKey": "NSE_EQ|INE121E01018", "heading": "Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL", "summary": "Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of \u20b9303 per share. The OFS opens for non-retail investors on Tuesday.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/", "publishedTime": 1780975699014, "sourceHash": "9af36ebb0af7fe86ac20cbb37db130270ba63485b04ec054985702314e0c97a4"}
```

```jsonl
// File: user/news/instruments/INE121J01017.jsonl

```

```jsonl
// File: user/news/instruments/INE123W01016.jsonl

```

```jsonl
// File: user/news/instruments/INE127D01025.jsonl

```

```jsonl
// File: user/news/instruments/INE129A01019.jsonl

```

```jsonl
// File: user/news/instruments/INE134E01011.jsonl
{"isin": "INE134E01011", "instrumentKey": "NSE_EQ|INE134E01011", "heading": "TCS, Zee Ent, DOMS Industries among buzzing stocks as SENSEX gains over 200 pts, NIFTY above 23,200", "summary": "Shares of Doms Industries climbed as much as 7.33% to hit an intraday high of \u20b92,273.90 per unit on the NSE on Thursday, after the company entered into an agreement to buy Reynolds Pens India Limited.\n", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-zee-ent-doms-industries-among-buzzing-stocks-as-sensex-gains-over-200-pts-nifty-above-23-200/article-195172/", "publishedTime": 1781163081692, "sourceHash": "85e04ca1d6ac8f3481e563d4459ed32df16783cec362ac9284ccbc8c88d68934"}
{"isin": "INE134E01011", "instrumentKey": "NSE_EQ|INE134E01011", "heading": "Stocks to watch, June 11: REC, PFC, Eternal, Power Grid, Lenskart Solutions, Meesho, Zee Entertainment", "summary": " Zomato and Blinkit's parent entity, Eternal, has received a \u20b99.63 crore GST demand notice, along with interest and penalty, from the Andhra Pradesh tax authorities.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-11-2026-rec-pfc-eternal-power-grid-lenskart-solutions-meesho-zee-entertainment/article-195142/", "publishedTime": 1781145776128, "sourceHash": "17bd3e3723e25c89d2e82c51456135f0bba039dd1e073a5a45763b9dc083915c"}
{"isin": "INE134E01011", "instrumentKey": "NSE_EQ|INE134E01011", "heading": "REC receives presidential approval for proposed merger into PFC; what investors need to know", "summary": "Earlier, on February 9, PFC\u2019s board had given its in-principle approval for the merger of the Maharatna non-banking finance company REC with itself.\n", "articleLink": "https://upstox.com/news/market-news/stocks/rec-receives-presidential-approval-for-proposed-merger-into-pfc-what-investors-need-to-know/article-195133/", "publishedTime": 1781161079014, "sourceHash": "d03a3e8f4e6a228f9a7995d8fec5c37803889b2fed9c7492655d4916ed00a571"}
```

```jsonl
// File: user/news/instruments/INE138Y01010.jsonl

```

```jsonl
// File: user/news/instruments/INE139A01034.jsonl

```

```jsonl
// File: user/news/instruments/INE148I01020.jsonl

```

```jsonl
// File: user/news/instruments/INE148O01028.jsonl

```

```jsonl
// File: user/news/instruments/INE154A01025.jsonl

```

```jsonl
// File: user/news/instruments/INE155A01022.jsonl
{"isin": "INE155A01022", "instrumentKey": "NSE_EQ|INE155A01022", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "sourceHash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
{"isin": "INE155A01022", "instrumentKey": "NSE_EQ|INE155A01022", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "sourceHash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE155A01022", "instrumentKey": "NSE_EQ|INE155A01022", "heading": "Dividend, bonus issue, stock splits next week: HDFC Bank, Tata Motors PV, Tata Tech, Torrent Power, among others in focus", "summary": "Shares of HDFC Bank will trade ex-record date on Friday, June 19, for its final dividend. On April 18, the country's largest private sector bank had recommended a final dividend of \u20b913 per equity share, with a face value of \u20b91 each for FY26. \n", "articleLink": "https://upstox.com/news/market-news/stocks/dividend-bonus-issue-stock-splits-next-week-hdfc-bank-tata-motors-pv-tata-tech-torrent-power-among-others-in-focus/article-195253/", "publishedTime": 1781262401321, "sourceHash": "a17f769439f18017d7ed3480c320e8b24ae53dabf5eed5efc3388941b15c848a"}
{"isin": "INE155A01022", "instrumentKey": "NSE_EQ|INE155A01022", "heading": "OMCs, Tata Motors PV, TCS among buzzing stocks as NIFTY up 1.4%, SENSEX climbs 1,202 pts in noon deals", "summary": "From the NIFTY firms, Shriram Finance, InterGlobe Aviation, Bajaj Finance, L&T, HDFC Bank, Jio Financial Services Eternal, Titan, Tata Motors Passenger Vehicles and were among the biggest winners.", "articleLink": "https://upstox.com/news/market-news/stocks/om-cs-tata-motors-pv-tcs-among-buzzing-stocks-as-nifty-up-0-77-sensex-climbs-729-pts-in-noon-deals/article-195250/", "publishedTime": 1781253125381, "sourceHash": "068276d01372b66bf813757a7055edd7450f2250f00bf783ced1fa1b281269c5"}
{"isin": "INE155A01022", "instrumentKey": "NSE_EQ|INE155A01022", "heading": "Tata Motors to hike passenger vehicle prices by up to 1.5% from July 1", "summary": "Tata Motors Passenger Vehicles Ltd (TMPV) will increase prices of its passenger vehicle lineup, including both internal combustion engine (ICE) models and electric vehicles (EVs), by up to 1.5% from July 1, 2026.", "articleLink": "https://upstox.com/news/business-news/latest-updates/tata-motors-to-hike-passenger-vehicle-prices-by-up-to-1-5-from-july-1/article-195242/", "publishedTime": 1781248971838, "sourceHash": "b6e828c38380167a68c9c8bd7ff89760e5a0e2d468bc16b21a0733e969d0cd07"}
{"isin": "INE155A01022", "instrumentKey": "NSE_EQ|INE155A01022", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "sourceHash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
```

```jsonl
// File: user/news/instruments/INE158A01026.jsonl

```

```jsonl
// File: user/news/instruments/INE160A01022.jsonl

```

```jsonl
// File: user/news/instruments/INE171A01029.jsonl
{"isin": "INE171A01029", "instrumentKey": "NSE_EQ|INE171A01029", "heading": "Stock market rally: Aegis Logistics, Cupid, Akums Drugs, JB Chemicals, among 47 stocks that hit 52-week high on NSE", "summary": "Prominent stocks like Aegis Logistics, Cupid, and Akums Drugs were among several others which surged to their 52-week high amid the broader market rally on Friday, June 12. ", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-rally-aegis-logistics-cupid-akums-drugs-jb-chemicals-among-47-stocks-that-hit-52-week-high-on-nse/article-195245/", "publishedTime": 1781252951486, "sourceHash": "d1e3270d78e0eb14240e3dc4d549f4219c7b2dcf2e7a5093eb02367845e75c44"}
{"isin": "INE171A01029", "instrumentKey": "NSE_EQ|INE171A01029", "heading": "Bank of Baroda, Canara Bank, Federal Bank shares rally; NIFTY Bank surges most in two months, here\u2019s why", "summary": "Banking stocks came under buying interest after the Reserve Bank of India (RBI) on Monday introduced a US dollar-rupee forex swap facility for fresh FCNR (B) deposits.", "articleLink": "https://upstox.com/news/market-news/stocks/bank-of-baroda-canara-bank-federal-bank-shares-rally-nifty-bank-surges-most-in-two-months-here-s-why/article-195034/", "publishedTime": 1780993166287, "sourceHash": "d80c3e2cf1cf67ba961fc529beecd0122875ef088fefb506656fb96bc98eb96e"}
{"isin": "INE171A01029", "instrumentKey": "NSE_EQ|INE171A01029", "heading": "Cupid, Aster DM, Data Patterns, Belrise and Federal Bank among top stocks that made fresh record highs on Tuesday; here's why", "summary": "Indian benchmark indices continued to hover near the neutral lines on Tuesday as the NIFTY50 jumped 30 points and the SENSEX traded near the 73,700 levels. Despite the broader consolidation in the markets, few stocks hit fresh record high levels.", "articleLink": "https://upstox.com/news/market-news/stocks/cupid-aster-dm-data-patterns-belrise-and-federal-bank-among-top-stocks-that-made-fresh-record-highs-on-tuesday-here-s-why/article-195033/", "publishedTime": 1780996523609, "sourceHash": "f8906d1355d955801e23175b221f78e975f90e26ace235b62a03fb8224b45166"}
```

```jsonl
// File: user/news/instruments/INE171Z01026.jsonl

```

```jsonl
// File: user/news/instruments/INE176B01034.jsonl

```

```jsonl
// File: user/news/instruments/INE180A01020.jsonl

```

```jsonl
// File: user/news/instruments/INE192A01025.jsonl

```

```jsonl
// File: user/news/instruments/INE192R01011.jsonl
{"isin": "INE192R01011", "instrumentKey": "NSE_EQ|INE192R01011", "heading": "Redington, JSW Steel, DMart among buzzing stocks as SENSEX jumps over 270 pts, NIFTY above 23,200", "summary": "Grasim Industries shares gained 2% on the NSE on Tuesday, June 9, as it said it will invest \u20b93,094 crore for expanding its Lyocell capacity at Harihar, Karnataka, under phase II.\n", "articleLink": "https://upstox.com/news/market-news/stocks/redington-jsw-steel-d-mart-among-buzzing-stocks-as-sensex-jumps-over-170-pts-nifty-above-23-200/article-195029/", "publishedTime": 1780992961868, "sourceHash": "358818ad16b24d83090792c0564e01f660ba99e3769286c56765ff7b5f6468fc"}
{"isin": "INE192R01011", "instrumentKey": "NSE_EQ|INE192R01011", "heading": "Avenue Supermarts shares rise over 1% as company invests \u20b9150 crore in e-commerce arm", "summary": "Avenue Supermarts reported a 19.18% year-on-year (YoY) surge in its consolidated net profit at \u20b9656.59 crore in the March quarter of the 2025-26 financial year (Q4 FY26).", "articleLink": "https://upstox.com/news/market-news/stocks/avenue-supermarts-shares-in-focus-as-company-invests-150-crore-e-commerce-arm/article-195002/", "publishedTime": 1781006386749, "sourceHash": "a7b55471c1b0a864254f6bec4906c69a695bc9ca5398c7225ec4dbdb5cee7b26"}
{"isin": "INE192R01011", "instrumentKey": "NSE_EQ|INE192R01011", "heading": "Avenue Supermarts shares in focus after \u20b9150 crore investment in DMart Ready", "summary": "DMart Ready operated in 18 cities as of FY26, as compared to 25 cities in the previous year. Despite the drop in operational cities and regions, the subsidiary\u2019s revenue for the FY26 stood at \u20b94,093 crore as compared to \u20b93,502 crore in FY25 and \u20b92,899 crore in FY24. The company aims to utilise the funds for working capital requirements and capex expenditure.", "articleLink": "https://upstox.com/news/market-news/stocks/avenue-supermart-s-shares-in-focus-after-149-crore-investment-in-its-subsidiary-d-mart-ready/article-194992/", "publishedTime": 1780937205485, "sourceHash": "1663181bbc32794af200780f048506a1c6d690894f2c3066159a78e4f04f784e"}
```

```jsonl
// File: user/news/instruments/INE195A01028.jsonl

```

```jsonl
// File: user/news/instruments/INE196A01026.jsonl

```

```jsonl
// File: user/news/instruments/INE200A01026.jsonl

```

```jsonl
// File: user/news/instruments/INE200M01039.jsonl

```

```jsonl
// File: user/news/instruments/INE202E01016.jsonl

```

```jsonl
// File: user/news/instruments/INE205A01025.jsonl
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger: Anil Agarwal sees $100 billion potential in each biz; hints at Vedanta Resources relisting", "summary": "Vedanta Ltd group's four demerged entities -- Vedanta Aluminium Metal, Vedanta Power, Vedanta Oil and Gas, and Vedanta Iron and Steel -- made their stock market debut on Monday.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-listing-anil-agarwal-sees-100-billion-potential-in-each-demerged-business-hints-at-vedanta-resources-relisting/article-195341/", "publishedTime": 1781518000626, "sourceHash": "cbb68cf24dd663e52ebcdf622298c548f0493e9e2724ae02c0169b0e01851bdd"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta, OMCs, SEPC, IFCI, among buzzing stocks as SENSEX, NIFTY50 rally over 1% in noon deals", "summary": "Bharti Airtel stock rose as much as 1.17% to hit an intraday high of \u20b91,843.80 per equity share on the NSE, after announcing that nearly 100% of its shareholders have approved the ongoing transaction to consolidate its stake in key strategic subsidiary Airtel Africa plc.\n", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-om-cs-sepc-ifci-among-buzzing-stocks-as-sensex-nifty-50-rally-over-1-in-noon-deals/article-195332/", "publishedTime": 1781509425276, "sourceHash": "33888ce75c28cfef92ade728a563522423cd19abb4a83ff2f85badb75e3eba1a"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta Aluminium lists at \u20b9522 on the NSE; all four demerged Vedanta entities now trading on exchanges", "summary": "The newly listed companies are Vedanta Oil and Gas Limited (formerly Malco Energy Limited), Vedanta Power Limited (formerly Talwandi Sabo Power Limited), Vedanta Aluminium Metal Limited, and Vedanta Iron and Steel Limited.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-aluminium-lists-at-522-on-the-nse-all-four-demerged-vedanta-entities-now-trading-on-exchanges/article-195311/", "publishedTime": 1781507457903, "sourceHash": "45cc772044534c4209587841ad8f8141c49c633cc67ab2c7827e7d3780cf8cf9"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger: Vedanta Oil and Gas to list on June 15; key details shareholders need to know", "summary": "The equity shares of Vedanta Oil and Gas Ltd will begin trading on the NSE and BSE on June 15, 2026, as part of Vedanta Ltd's ongoing demerger exercise. The listing will create a separately traded oil and gas company, allowing investors to independently assess the business's operations, growth prospects, and valuation.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-demerger-vedanta-oil-and-gas-to-list-on-june-15-key-details-shareholders-need-to-know/article-195281/", "publishedTime": 1781409053613, "sourceHash": "5d948ffbbe0d7a0419ec0511d6c6c1d3abe96387b662e656a692cee5b5e3bf69"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger: Four newly carved-out companies set for market debut on June 15; details to know", "summary": "In their notices and circulars, both BSE and NSE stated on Thursday, that the four demerged arms, namely, Vedanta Oil and Gas Limited, Vedanta Power Limited, Vedanta Aluminium Metal Limited, and Vedanta Iron And Steel Limited, shall be listed on June 15, 2026.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-demerger-four-entities-to-list-on-june-15-what-shareholders-need-to-know/article-195209/", "publishedTime": 1781419291042, "sourceHash": "cfb391a4ce768d68937112b03d6ec37f118e61c0d5012faeb67c28a1d4d77db9"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger: What shareholders should know before Vedanta Power starts trading", "summary": "Vedanta demerger is aimed at unlocking shareholder value by creating pure-play businesses, allowing investors to gain targeted exposure to individual sectors such as power, aluminium, and other metals, instead of investing in Vedanta's diversified portfolio", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-demerger-what-shareholders-should-know-before-vedanta-power-starts-trading/article-195136/", "publishedTime": 1781108250005, "sourceHash": "7d26070dbc0d4423fb0eb464472571bc642ef3a381aa8e0804f347a5a0892bdb"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta demerger update: Oil & gas arm renamed Vedanta Oil and Gas from Malco Energy", "summary": "On Tuesday, June 9, Vedanta, in its filing to stock exchanges, said that the Registrar of Companies under the Ministry of Corporate Affairs (MCA) has approved the change in name of its oil & gas business from \u201cMalco Energy Limited\u201d to \u201cVedanta Oil and Gas Limited\u201d, with effect from June 9, 2026.", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-demerger-update-oil-and-gas-arm-renamed-vedanta-oil-and-gas-from-malco-energy/article-195058/", "publishedTime": 1781496898846, "sourceHash": "75d636e49ba6c710a61671f44f67b78ff5d5fa559e535708e9c6c03cec972fbd"}
{"isin": "INE205A01025", "instrumentKey": "NSE_EQ|INE205A01025", "heading": "Vedanta shares in focus on rebranding its copper and nickel businesses; key details to know", "summary": "Vedanta Nico, renamed as Vedanta Nickel, will sharpen its focus on building a domestic nickel ecosystem and supporting the country's demand for critical minerals.\n", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-shares-in-focus-on-rebranding-its-copper-and-nickel-businesses-key-details-to-know/article-194982/", "publishedTime": 1780920773195, "sourceHash": "ceed60433dbd01df97ab3546373c5bfdaf9f3130d8932900b24f900beb0e2935"}
```

```jsonl
// File: user/news/instruments/INE208A01029.jsonl
{"isin": "INE208A01029", "instrumentKey": "NSE_EQ|INE208A01029", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "sourceHash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
```

```jsonl
// File: user/news/instruments/INE211B01039.jsonl

```

```jsonl
// File: user/news/instruments/INE213A01029.jsonl
{"isin": "INE213A01029", "instrumentKey": "NSE_EQ|INE213A01029", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "sourceHash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
{"isin": "INE213A01029", "instrumentKey": "NSE_EQ|INE213A01029", "heading": "Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads", "summary": "A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran", "articleLink": "https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/", "publishedTime": 1781332567326, "sourceHash": "974ccc4cc38a08ad5be1cc2bfe00f302dd6235053a5fd4846d3cac61fa4d6fd3"}
{"isin": "INE213A01029", "instrumentKey": "NSE_EQ|INE213A01029", "heading": "Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics", "summary": "Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of \u20b9175 crore through open market transactions.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/", "publishedTime": 1781232986902, "sourceHash": "10d7ec3171f1eb0eee6593b016ec2b73940ce6fb38d8f80504fa35e3c0db21bb"}
{"isin": "INE213A01029", "instrumentKey": "NSE_EQ|INE213A01029", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "sourceHash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
```

```jsonl
// File: user/news/instruments/INE214T01019.jsonl

```

```jsonl
// File: user/news/instruments/INE216A01030.jsonl

```

```jsonl
// File: user/news/instruments/INE226A01021.jsonl

```

```jsonl
// File: user/news/instruments/INE237A01036.jsonl
{"isin": "INE237A01036", "instrumentKey": "NSE_EQ|INE237A01036", "heading": "Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads", "summary": "A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran", "articleLink": "https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/", "publishedTime": 1781332567326, "sourceHash": "974ccc4cc38a08ad5be1cc2bfe00f302dd6235053a5fd4846d3cac61fa4d6fd3"}
```

```jsonl
// File: user/news/instruments/INE238A01034.jsonl
{"isin": "INE238A01034", "instrumentKey": "NSE_EQ|INE238A01034", "heading": "Weekly Market Wrap: SENSEX rises 2%, NIFTY50 up 1% as metal and banking stocks rally; Kotak Mahindra Bank leads", "summary": "A rally in heavyweights and banking stocks on Friday lifted the overall benchmark index returns on reports of a draft truce deal between the United States and Iran", "articleLink": "https://upstox.com/news/market-news/stocks/weekly-market-wrap-sensex-rises-2-nifty-50-up-1-as-metal-and-banking-stocks-rally-kotak-mahindra-bank-leads/article-195278/", "publishedTime": 1781332567326, "sourceHash": "974ccc4cc38a08ad5be1cc2bfe00f302dd6235053a5fd4846d3cac61fa4d6fd3"}
{"isin": "INE238A01034", "instrumentKey": "NSE_EQ|INE238A01034", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE239A01024.jsonl
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Vedanta, OMCs, SEPC, IFCI, among buzzing stocks as SENSEX, NIFTY50 rally over 1% in noon deals", "summary": "Bharti Airtel stock rose as much as 1.17% to hit an intraday high of \u20b91,843.80 per equity share on the NSE, after announcing that nearly 100% of its shareholders have approved the ongoing transaction to consolidate its stake in key strategic subsidiary Airtel Africa plc.\n", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-om-cs-sepc-ifci-among-buzzing-stocks-as-sensex-nifty-50-rally-over-1-in-noon-deals/article-195332/", "publishedTime": 1781509425276, "sourceHash": "33888ce75c28cfef92ade728a563522423cd19abb4a83ff2f85badb75e3eba1a"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "sourceHash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Nestle India shares in spotlight on rejecting claims of infestation in MAGGI noodles; key details", "summary": "Nestle said that it has already submitted a detailed representation, supported by all relevant facts, quality records from batch and market samples, and test reports to the competent authorities.\n", "articleLink": "https://upstox.com/news/market-news/stocks/nestle-india-shares-in-spotlight-on-rejecting-claims-of-infestation-in-maggi-noodles-key-details/article-195274/", "publishedTime": 1781273568006, "sourceHash": "3ff4ec0ad17c6167f5569dc4c7d9a5e6667c4c5eb6f69ec0401e8aa6b3fa2da5"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list", "summary": "On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/", "publishedTime": 1781262446006, "sourceHash": "695fd1b2df08bd00530b8322c0b7569b5143460d2e5585792c2b4ca0397b866d"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "sourceHash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
{"isin": "INE239A01024", "instrumentKey": "NSE_EQ|INE239A01024", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE242A01010.jsonl
{"isin": "INE242A01010", "instrumentKey": "NSE_EQ|INE242A01010", "heading": "Indian Oil, Asian Paints, JK Tyre, IndiGo shares jump as US-Iran peace deal sends oil prices tumbling", "summary": "\"The Deal with the Islamic Republic of Iran is now complete. Congratulations to all!\" US President Donald Trump wrote on his Truth Social platform. \"I hereby fully authorize the toll-free opening of the Strait of Hormuz, and, simultaneously herewith, authorize the immediate removal of the United States Naval blockade.\"", "articleLink": "https://upstox.com/news/market-news/stocks/indian-oil-asian-paints-jk-tyre-indi-go-in-focus-as-us-iran-peace-deal-sends-oil-prices-tumbling/article-195300/", "publishedTime": 1781496307908, "sourceHash": "f519e8f51c8527d40695ef1465ee2b1aad4b7e8c51ae5e20925587b52309a664"}
{"isin": "INE242A01010", "instrumentKey": "NSE_EQ|INE242A01010", "heading": "Stocks to watch, June 15: OMCs, paints, ONGC, Oil India, Meesho, Vedanta, Sun Pharma, Dr Reddy's", "summary": "Oil-linked stocks are expected to be in the spotlight on Monday, June 15, as the US and Iran have agreed to a deal to bring their over-three-month war to an end, with both sides declaring the immediate and permanent termination of military operations on all fronts. ", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-15-om-cs-paints-ongc-oil-india-meesho-vedanta-sun-pharma-dr-reddy-s/article-195295/", "publishedTime": 1781490582777, "sourceHash": "fd2fced8217c1c2212bcd5cb330410f170da6fd508ad6711f2fc59848501815a"}
{"isin": "INE242A01010", "instrumentKey": "NSE_EQ|INE242A01010", "heading": "Govt bars bulk industrial petrol, diesel purchases through retail fuel outlets", "summary": "The order, issued under the Essential Commodities Act, aims to prevent diversion of supplies meant for retail consumers after authorities observed bulk buyers shifting to petrol pumps due to the lower retail prices compared with bulk rates.", "articleLink": "https://upstox.com/news/business-news/latest-updates/govt-bars-bulk-industrial-petrol-diesel-purchases-through-retail-fuel-outlets/article-195216/", "publishedTime": 1781234130711, "sourceHash": "a28f3fd76dcf3a831fcf388cddb654f0ea39033d25cf0f6d795876eb358c6c36"}
{"isin": "INE242A01010", "instrumentKey": "NSE_EQ|INE242A01010", "heading": "Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics", "summary": "Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of \u20b9175 crore through open market transactions.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/", "publishedTime": 1781232986902, "sourceHash": "10d7ec3171f1eb0eee6593b016ec2b73940ce6fb38d8f80504fa35e3c0db21bb"}
```

```jsonl
// File: user/news/instruments/INE245A01021.jsonl

```

```jsonl
// File: user/news/instruments/INE249Z01020.jsonl

```

```jsonl
// File: user/news/instruments/INE257A01026.jsonl

```

```jsonl
// File: user/news/instruments/INE259A01022.jsonl

```

```jsonl
// File: user/news/instruments/INE260B01028.jsonl

```

```jsonl
// File: user/news/instruments/INE262H01021.jsonl

```

```jsonl
// File: user/news/instruments/INE263A01024.jsonl
{"isin": "INE263A01024", "instrumentKey": "NSE_EQ|INE263A01024", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
{"isin": "INE263A01024", "instrumentKey": "NSE_EQ|INE263A01024", "heading": "BEL shares jump 2% amid weak market; latest buzz you need to know", "summary": "In November 2025, BEL had signed a Joint Venture Cooperation Agreement (JVCA) with France\u2019s Safran Electronics and Defence (SED) to produce the HAMMER precision-guided air-to-ground weapon in India.\n", "articleLink": "https://upstox.com/news/market-news/stocks/bel-shares-jump-2-on-reports-of-iaf-eyeing-large-procurement-of-hammer-precision-weapon-key-details/article-194970/", "publishedTime": 1780913679174, "sourceHash": "37352a9eb78a78d1065e7c3c4bb59bba9dd7b893fc49bdd7a707477deb6ca1da"}
{"isin": "INE263A01024", "instrumentKey": "NSE_EQ|INE263A01024", "heading": "BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details", "summary": "Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ", "articleLink": "https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/", "publishedTime": 1780906190587, "sourceHash": "c2ec158afea8e4e7d041bbc28d47443d11e32184002ba448514c351d5fd33eef"}
```

```jsonl
// File: user/news/instruments/INE267A01025.jsonl
{"isin": "INE267A01025", "instrumentKey": "NSE_EQ|INE267A01025", "heading": "Hindustan Zinc signs MoU with Sulfozyme Agro to advance sustainable metal recovery; stock falls", "summary": "Hindustan Zinc said that it entered into a partnership with Sulfozyme Agro India under its flagship Zinc Industrial Park initiative, set up at the Bhilwara district in Rajasthan. \n", "articleLink": "https://upstox.com/news/market-news/stocks/hindustan-zinc-signs-mo-u-with-sulfozyme-agro-to-advance-sustainable-metal-recovery-stock-falls/article-195100/", "publishedTime": 1781076230013, "sourceHash": "9c1a3fb1f8c18a19a1536ae83068675a42186ddbdddea1887c445f7ee415aa3c"}
```

```jsonl
// File: user/news/instruments/INE271C01023.jsonl

```

```jsonl
// File: user/news/instruments/INE274J01014.jsonl
{"isin": "INE274J01014", "instrumentKey": "NSE_EQ|INE274J01014", "heading": "Stocks to watch, June 12: Oil-sensitives, Dabur, Happiest Minds, Sagility, Astral, GNG Electronics", "summary": "Goldman Sachs, MCP Emerging Markets, and other financial institutions on Thursday picked more than 44 lakh shares, representing nearly a 4% equity stake, of GNG Electronics, the parent company of Electronics Bazaar, for a total of \u20b9175 crore through open market transactions.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-12-oil-sensitives-dabur-happiest-minds-sagility-astral-gng-electronics/article-195215/", "publishedTime": 1781232986902, "sourceHash": "10d7ec3171f1eb0eee6593b016ec2b73940ce6fb38d8f80504fa35e3c0db21bb"}
```

```jsonl
// File: user/news/instruments/INE280A01028.jsonl
{"isin": "INE280A01028", "instrumentKey": "NSE_EQ|INE280A01028", "heading": "Kalyan Jewellers, Titan, Motisons, other jewellery stocks rise as gold prices climb amid US-Iran peace deal", "summary": "Gold futures for August delivery on the Multi Commodity Exchange of India (MCX) surged \u20b92,191, or 1.46%, to \u20b91,52,719 per 10 grams, amid strong spot demand and positive global cues.", "articleLink": "https://upstox.com/news/market-news/stocks/kalyan-jewellers-titan-other-jewellery-stocks-rise-as-gold-prices-climb-amid-us-iran-peace-deal/article-195329/", "publishedTime": 1781519633623, "sourceHash": "ec0458395a345350382ce283d5627f7c802faf66eb94636b30ac37a29992c349"}
{"isin": "INE280A01028", "instrumentKey": "NSE_EQ|INE280A01028", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "sourceHash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE280A01028", "instrumentKey": "NSE_EQ|INE280A01028", "heading": "Top gainers and losers, June 9: IndiGo gains 4%, Jio Finserv up 2%, Titan Company down 2%; check list", "summary": "On June 9, the SENSEX surged by 394.50 points or 0.54% to close at 73,918.76, while the NIFTY50 ended higher by 119.10 points or 0.52% at 23,242.10.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-9-indi-go-gains-4-jio-finserv-up-2-titan-company-down-2-check-list/article-195047/", "publishedTime": 1781002812449, "sourceHash": "7e04998d430532babe1a97ace94ba9db4275cb7dbc3c4fa94bee3b8c2e070a2d"}
```

```jsonl
// File: user/news/instruments/INE296A01032.jsonl
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list", "summary": "On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/", "publishedTime": 1781262446006, "sourceHash": "695fd1b2df08bd00530b8322c0b7569b5143460d2e5585792c2b4ca0397b866d"}
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "sourceHash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "sourceHash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter", "summary": "HDFC Bank reported net profit of \u20b919,221 crore in Q4FY26, marking an increase of 8% from \u20b917,616.14 crore in the year-ago period.", "articleLink": "https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/", "publishedTime": 1780987251866, "sourceHash": "4048fe01ce51183a2647103708a97ce3b3c3e4b3068bd850f548cda6f4d7dbd2"}
{"isin": "INE296A01032", "instrumentKey": "NSE_EQ|INE296A01032", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE298A01020.jsonl

```

```jsonl
// File: user/news/instruments/INE298J01013.jsonl

```

```jsonl
// File: user/news/instruments/INE299U01018.jsonl

```

```jsonl
// File: user/news/instruments/INE302A01020.jsonl

```

```jsonl
// File: user/news/instruments/INE303R01014.jsonl
{"isin": "INE303R01014", "instrumentKey": "NSE_EQ|INE303R01014", "heading": "Kalyan Jewellers, Titan, Motisons, other jewellery stocks rise as gold prices climb amid US-Iran peace deal", "summary": "Gold futures for August delivery on the Multi Commodity Exchange of India (MCX) surged \u20b92,191, or 1.46%, to \u20b91,52,719 per 10 grams, amid strong spot demand and positive global cues.", "articleLink": "https://upstox.com/news/market-news/stocks/kalyan-jewellers-titan-other-jewellery-stocks-rise-as-gold-prices-climb-amid-us-iran-peace-deal/article-195329/", "publishedTime": 1781519633623, "sourceHash": "ec0458395a345350382ce283d5627f7c802faf66eb94636b30ac37a29992c349"}
```

```jsonl
// File: user/news/instruments/INE318A01026.jsonl

```

```jsonl
// File: user/news/instruments/INE323A01026.jsonl

```

```jsonl
// File: user/news/instruments/INE326A01037.jsonl
{"isin": "INE326A01037", "instrumentKey": "NSE_EQ|INE326A01037", "heading": "Lupin partners with Spanish pharma company for launch of Luforbec inhalers in Spain; check stock performance", "summary": "Luforbec (beclometasone/formoterol) 100/6, which will be launched in Spain, is a fixed-dose combination in a pressurised metered-dose inhaler, Lupin said.\n", "articleLink": "https://upstox.com/news/market-news/stocks/lupin-partners-with-spanish-pharma-company-for-launch-of-luforbec-inhalers-in-spain-stock-falls/article-194966/", "publishedTime": 1780909010412, "sourceHash": "95777dcca496a1064418b5fd4a755b9ac5c03c35724c0483bd46afdcf06f6218"}
```

```jsonl
// File: user/news/instruments/INE338I01027.jsonl

```

```jsonl
// File: user/news/instruments/INE343H01029.jsonl

```

```jsonl
// File: user/news/instruments/INE347G01014.jsonl
{"isin": "INE347G01014", "instrumentKey": "NSE_EQ|INE347G01014", "heading": "Vedanta, OMCs, SEPC, IFCI, among buzzing stocks as SENSEX, NIFTY50 rally over 1% in noon deals", "summary": "Bharti Airtel stock rose as much as 1.17% to hit an intraday high of \u20b91,843.80 per equity share on the NSE, after announcing that nearly 100% of its shareholders have approved the ongoing transaction to consolidate its stake in key strategic subsidiary Airtel Africa plc.\n", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-om-cs-sepc-ifci-among-buzzing-stocks-as-sensex-nifty-50-rally-over-1-in-noon-deals/article-195332/", "publishedTime": 1781509425276, "sourceHash": "33888ce75c28cfef92ade728a563522423cd19abb4a83ff2f85badb75e3eba1a"}
{"isin": "INE347G01014", "instrumentKey": "NSE_EQ|INE347G01014", "heading": "Petronet LNG shares post best day in over five months after LNG tanker Disha crosses Strait of Hormuz", "summary": "LNG tanker Disha which was chartered by Petronet LNG successfully transited through the Strait of Hormuz heading east to exit the Persian Gulf.", "articleLink": "https://upstox.com/news/market-news/stocks/petronet-lng-shares-post-best-day-in-over-five-months-after-lng-tanker-disha-crosses-strait-of-hormuz/article-195318/", "publishedTime": 1781502882276, "sourceHash": "1066188a752d6f0b3b3c93497dde8262ad72af4849206cc99e207dc832684649"}
```

```jsonl
// File: user/news/instruments/INE356A01018.jsonl

```

```jsonl
// File: user/news/instruments/INE361B01024.jsonl

```

```jsonl
// File: user/news/instruments/INE364U01010.jsonl

```

```jsonl
// File: user/news/instruments/INE371P01015.jsonl

```

```jsonl
// File: user/news/instruments/INE376G01013.jsonl

```

```jsonl
// File: user/news/instruments/INE377N01017.jsonl
{"isin": "INE377N01017", "instrumentKey": "NSE_EQ|INE377N01017", "heading": "Waaree Energies secures order for supply of 800 megawatts solar modules, shares rise", "summary": "Waaree Energies in a regulatory filing said that a renowned customer placed an order for supply of 800 MW of solar modules.", "articleLink": "https://upstox.com/news/market-news/stocks/waaree-energies-secures-order-for-supply-of-800-megawatts-solar-modules-shears-rise/article-195337/", "publishedTime": 1781510482882, "sourceHash": "f2c83ead1ffe72da26b38c4e7712ffe57c88e56bfaa44df76fba8b9a8a8857a2"}
{"isin": "INE377N01017", "instrumentKey": "NSE_EQ|INE377N01017", "heading": "Vikram Solar, Suzlon Energy, Waaree Energies: How renewable energy stocks are performing on June 9", "summary": "In May 2026, Colliers India released a report, 'The Green Shift: Renewable Prioritisation Reshaping Indian Real Estate', wherein it said that India will need around 7 lakh acres of land parcels, estimated to cost \u20b910-\u20b915 billion, in five years to set up solar and wind energy projects.", "articleLink": "https://upstox.com/news/market-news/stocks/vikram-solar-suzlon-energy-waaree-energies-how-renewable-energy-stocks-are-performing-on-june-9/article-195036/", "publishedTime": 1780994528822, "sourceHash": "e4c368fca1b9dce87cbcf6b05e752a2fdbd397b42eba5a2bbb4c0cf32bf02337"}
```

```jsonl
// File: user/news/instruments/INE388Y01029.jsonl

```

```jsonl
// File: user/news/instruments/INE397D01024.jsonl
{"isin": "INE397D01024", "instrumentKey": "NSE_EQ|INE397D01024", "heading": "Bharti Airtel gets shareholders' nod to increase stake in Africa business; how much does the telecom arm earn?", "summary": "Bharti Airtel receives shareholders' nod to increase around 16.31% stake in Africa arm as the company eyes long-term value creation potential. Here's what investors need to know. ", "articleLink": "https://upstox.com/news/market-news/stocks/bharti-airtel-gets-shareholders-nod-to-increase-stake-in-africa-business-how-much-does-the-telecom-arm-earn/article-195347/", "publishedTime": 1781516419525, "sourceHash": "aee4887cd4091f2bbe5796d0323c872690d210c3067605dc3bef105e865836ac"}
{"isin": "INE397D01024", "instrumentKey": "NSE_EQ|INE397D01024", "heading": "Vedanta, OMCs, SEPC, IFCI, among buzzing stocks as SENSEX, NIFTY50 rally over 1% in noon deals", "summary": "Bharti Airtel stock rose as much as 1.17% to hit an intraday high of \u20b91,843.80 per equity share on the NSE, after announcing that nearly 100% of its shareholders have approved the ongoing transaction to consolidate its stake in key strategic subsidiary Airtel Africa plc.\n", "articleLink": "https://upstox.com/news/market-news/stocks/vedanta-om-cs-sepc-ifci-among-buzzing-stocks-as-sensex-nifty-50-rally-over-1-in-noon-deals/article-195332/", "publishedTime": 1781509425276, "sourceHash": "33888ce75c28cfef92ade728a563522423cd19abb4a83ff2f85badb75e3eba1a"}
{"isin": "INE397D01024", "instrumentKey": "NSE_EQ|INE397D01024", "heading": "SENSEX, NIFTY50 erase gains to end on a flat note dragged by Reliance, Bharti Airtel", "summary": "Selling pressure was broad based as 12 of 15 major sector gauges compiled by the National Stock Exchange (NSE) ended lower led by the NIFTY Media index's over 2% fall.", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-nifty-50-erase-gains-to-end-on-a-flat-note-dragged-by-reliance-bharti-airtel/article-195118/", "publishedTime": 1781087106142, "sourceHash": "68ab5da18a71da03a7ed68df59ca2887c0a39058cf1d5a45d9591b3771dc631c"}
{"isin": "INE397D01024", "instrumentKey": "NSE_EQ|INE397D01024", "heading": "Bharti Airtel announces deployment of over 2,900 new 5G sites across 77 districts in North India; check stock performance", "summary": "In the past 12 months, Bharti Airtel deployed more than 1,066 new 5G sites in Punjab, over 954 in Haryana, more than 276 in Himachal Pradesh, and over 619 in Jammu & Kashmir.\n", "articleLink": "https://upstox.com/news/market-news/stocks/bharti-airtel-announces-deployment-of-over-2-900-new-5-g-sites-across-77-districts-in-north-india-check-stock-performance/article-195107/", "publishedTime": 1781078872635, "sourceHash": "1a7957d5b4e2edfdd67db530290fab6cd468bcbd0d9e89621f63591c18e4b96c"}
{"isin": "INE397D01024", "instrumentKey": "NSE_EQ|INE397D01024", "heading": "Bharti Airtel shares trade higher amid TRAI penalties, latest buzz around Starlink launch", "summary": "Bharti Airtel shares were trading higher on June 10, amid the latest set of penalties imposed by TRAI, likely due to the latest buzz around Starlink India's launch. ", "articleLink": "https://upstox.com/news/market-news/stocks/bharti-airtel-share-price-in-focus-after-trai-penalties-latest-buzz-around-starlink-launch/article-195070/", "publishedTime": 1781063730123, "sourceHash": "713db4769ae19908c0ef1e7cbae044f783b6fd41ebee0ab7523c88cc3038497e"}
{"isin": "INE397D01024", "instrumentKey": "NSE_EQ|INE397D01024", "heading": "Bharti Airtel, Vodafone Idea shares rise as Bombay HC quashes govt decision to impose 1-time spectrum charge", "summary": "In November 2012, the Union Cabinet took a decision that a one-time charge would be imposed for spectrum held beyond 6.2 MHz from July 2008 onwards. Following this, demand notices were issued to the petitioners (Bharti Airtel Ltd and Vodafone Idea Ltd) specifying the amounts payable by them towards one-time spectrum charge.", "articleLink": "https://upstox.com/news/market-news/stocks/bharti-airtel-vodafone-idea-shares-rise-as-bombay-hc-quashes-govt-decision-to-impose-1-time-spectrum-charge/article-195011/", "publishedTime": 1780978490137, "sourceHash": "f9af2752c5cbe55cdd88878543700cfa644342c67b721515d4da89ecbe2feed3"}
{"isin": "INE397D01024", "instrumentKey": "NSE_EQ|INE397D01024", "heading": "Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL", "summary": "Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of \u20b9303 per share. The OFS opens for non-retail investors on Tuesday.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/", "publishedTime": 1780975699014, "sourceHash": "9af36ebb0af7fe86ac20cbb37db130270ba63485b04ec054985702314e0c97a4"}
{"isin": "INE397D01024", "instrumentKey": "NSE_EQ|INE397D01024", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
```

```jsonl
// File: user/news/instruments/INE405E01023.jsonl
{"isin": "INE405E01023", "instrumentKey": "NSE_EQ|INE405E01023", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "sourceHash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
```

```jsonl
// File: user/news/instruments/INE406A01037.jsonl

```

```jsonl
// File: user/news/instruments/INE414G01012.jsonl

```

```jsonl
// File: user/news/instruments/INE415G01027.jsonl
{"isin": "INE415G01027", "instrumentKey": "NSE_EQ|INE415G01027", "heading": "Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE", "summary": "NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/", "publishedTime": 1780918365081, "sourceHash": "df35c8aa9544c48b9dbaa16623a9c2fc662a5561d7c5990483ef56fad54b4840"}
```

```jsonl
// File: user/news/instruments/INE417T01026.jsonl

```

```jsonl
// File: user/news/instruments/INE423A01024.jsonl

```

```jsonl
// File: user/news/instruments/INE437A01024.jsonl
{"isin": "INE437A01024", "instrumentKey": "NSE_EQ|INE437A01024", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE451A01017.jsonl

```

```jsonl
// File: user/news/instruments/INE455K01017.jsonl
{"isin": "INE455K01017", "instrumentKey": "NSE_EQ|INE455K01017", "heading": "Redington, JSW Steel, DMart among buzzing stocks as SENSEX jumps over 270 pts, NIFTY above 23,200", "summary": "Grasim Industries shares gained 2% on the NSE on Tuesday, June 9, as it said it will invest \u20b93,094 crore for expanding its Lyocell capacity at Harihar, Karnataka, under phase II.\n", "articleLink": "https://upstox.com/news/market-news/stocks/redington-jsw-steel-d-mart-among-buzzing-stocks-as-sensex-jumps-over-170-pts-nifty-above-23-200/article-195029/", "publishedTime": 1780992961868, "sourceHash": "358818ad16b24d83090792c0564e01f660ba99e3769286c56765ff7b5f6468fc"}
{"isin": "INE455K01017", "instrumentKey": "NSE_EQ|INE455K01017", "heading": "Polycab India dividend schedule: Board announces record date for \u20b947/share dividend issue", "summary": "Polycab India announced the record date for its \u20b947 per share dividend issue for the financial year ended 2025-26. Here's what investors should know. ", "articleLink": "https://upstox.com/news/market-news/stocks/polycab-india-dividend-schedule-board-announces-record-date-for-47-share-dividend-issue/article-195003/", "publishedTime": 1780976632350, "sourceHash": "55e8524fe43e7f69aef38df16924e16e99ef0a1b450976e2efab0a371c640a9f"}
```

```jsonl
// File: user/news/instruments/INE457L01029.jsonl

```

```jsonl
// File: user/news/instruments/INE465A01025.jsonl
{"isin": "INE465A01025", "instrumentKey": "NSE_EQ|INE465A01025", "heading": "Bharat Forge shares soar over 4% as its defence arm unveils MArG series mounted artillery guns at Eurosatory 2026", "summary": "The MArG series, as per Bharat Forge, is a truck-mounted artillery system built for manoeuvre and delivers a highly mobile, rapidly deployable, and cost-optimised firepower solution for modern land forces.\n\n", "articleLink": "https://upstox.com/news/market-news/stocks/bharat-forge-shares-soar-over-4-as-its-defence-arm-unveils-m-ar-g-series-mounted-artillery-guns-at-eurosatory-2026/article-195344/", "publishedTime": 1781514660635, "sourceHash": "2cf07f14b5feeaea02816a4164a4c06242e0da71723a7328164531f3549fdcb3"}
```

```jsonl
// File: user/news/instruments/INE466L01038.jsonl

```

```jsonl
// File: user/news/instruments/INE467B01029.jsonl
{"isin": "INE467B01029", "instrumentKey": "NSE_EQ|INE467B01029", "heading": "OMCs, Tata Motors PV, TCS among buzzing stocks as NIFTY up 1.4%, SENSEX climbs 1,202 pts in noon deals", "summary": "From the NIFTY firms, Shriram Finance, InterGlobe Aviation, Bajaj Finance, L&T, HDFC Bank, Jio Financial Services Eternal, Titan, Tata Motors Passenger Vehicles and were among the biggest winners.", "articleLink": "https://upstox.com/news/market-news/stocks/om-cs-tata-motors-pv-tcs-among-buzzing-stocks-as-nifty-up-0-77-sensex-climbs-729-pts-in-noon-deals/article-195250/", "publishedTime": 1781253125381, "sourceHash": "068276d01372b66bf813757a7055edd7450f2250f00bf783ced1fa1b281269c5"}
{"isin": "INE467B01029", "instrumentKey": "NSE_EQ|INE467B01029", "heading": "TCS shares rise 2% as it sets up country\u2019s first Oracle AI data platform lab, CoE in Kolkata", "summary": "In a statement, TCS said it also plans to roll out the Oracle AI Data Platform Labs and CoEs across four additional cities in India over the next three years", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-shares-rise-2-as-it-sets-up-country-s-first-oracle-ai-data-platform-lab-co-e-in-kolkata-1/article-195244/", "publishedTime": 1781247769546, "sourceHash": "8ee6ad9d81608159fcddd73dcc91f70aafe9ff528e84bd43de3e7940f073fc05"}
{"isin": "INE467B01029", "instrumentKey": "NSE_EQ|INE467B01029", "heading": "TCS ties up with Anthropic to equip 50,000 employees with Claude; stock hits 52-week low", "summary": "TCS and Anthropic will jointly go to market with AI solutions and services across industries", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-ties-up-with-anthropic-to-equip-50-000-employees-with-claude-stock-hits-52-week-low/article-195164/", "publishedTime": 1781160026627, "sourceHash": "901828a951720c4d8a20a52cd54bb43f1fd19162e3ef1f6288fa2f58f8749d65"}
{"isin": "INE467B01029", "instrumentKey": "NSE_EQ|INE467B01029", "heading": "TCS, Infosys, Tech Mahindra, other IT shares fall amid AI stocks sell-off on Wall Street", "summary": "Wall Street has been on shaky ground since last week, as artificial intelligence stocks have traded lower after reaching record highs.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-tech-mahindra-other-it-stocks-fall-amid-ai-stocks-sell-off-on-wall-street/article-195152/", "publishedTime": 1781154186328, "sourceHash": "0ef341c9234cab441057738f11d2d43560ea11eb33a81923d3b9af746b851768"}
{"isin": "INE467B01029", "instrumentKey": "NSE_EQ|INE467B01029", "heading": "TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump\u2019s $100,000 H-1B visa fee rule", "summary": "A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/", "publishedTime": 1780978356295, "sourceHash": "94b5c2a562a22ca1f6fc2472ae19b1e4a418299d33d3c09c8eeda068a16cb1bc"}
{"isin": "INE467B01029", "instrumentKey": "NSE_EQ|INE467B01029", "heading": "Stock market crash: Reliance Industries, TCS, RVNL, Wipro among 82 stocks that hit 52-week low on NSE", "summary": "NIFTY50 and SENSEX saw strong sell-off today after Iran-Israel entered into a fresh military strike despite the ceasefire. Several prominent stocks like Reliance Industries, TCS, Swiggy, Wipro and others hit their 52-week low today on NSE.", "articleLink": "https://upstox.com/news/market-news/stocks/stock-market-crash-reliance-industries-tcs-rvnl-wipro-among-64-stocks-that-hit-52-week-low-on-nse/article-194955/", "publishedTime": 1780918365081, "sourceHash": "df35c8aa9544c48b9dbaa16623a9c2fc662a5561d7c5990483ef56fad54b4840"}
{"isin": "INE467B01029", "instrumentKey": "NSE_EQ|INE467B01029", "heading": "TCS secures multimillion-euro AI deal from Canada Life, shares down", "summary": "The overhaul will help upgrade operational resilience, increase automation, and boost user experience for Canada Life's customers, TCS said in an exchange filing.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-secures-multimillion-euro-ai-deal-with-canada-life-shares-down/article-194957/", "publishedTime": 1780907036882, "sourceHash": "e25402d65ef823aae989d54c9818da3d27cfc6212e0fafdcb31a532adaa73036"}
{"isin": "INE467B01029", "instrumentKey": "NSE_EQ|INE467B01029", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE472A01039.jsonl

```

```jsonl
// File: user/news/instruments/INE476A01022.jsonl
{"isin": "INE476A01022", "instrumentKey": "NSE_EQ|INE476A01022", "heading": "Bank of Baroda, Canara Bank, Federal Bank shares rally; NIFTY Bank surges most in two months, here\u2019s why", "summary": "Banking stocks came under buying interest after the Reserve Bank of India (RBI) on Monday introduced a US dollar-rupee forex swap facility for fresh FCNR (B) deposits.", "articleLink": "https://upstox.com/news/market-news/stocks/bank-of-baroda-canara-bank-federal-bank-shares-rally-nifty-bank-surges-most-in-two-months-here-s-why/article-195034/", "publishedTime": 1780993166287, "sourceHash": "d80c3e2cf1cf67ba961fc529beecd0122875ef088fefb506656fb96bc98eb96e"}
```

```jsonl
// File: user/news/instruments/INE481G01011.jsonl

```

```jsonl
// File: user/news/instruments/INE484J01027.jsonl

```

```jsonl
// File: user/news/instruments/INE494B01023.jsonl

```

```jsonl
// File: user/news/instruments/INE498L01015.jsonl

```

```jsonl
// File: user/news/instruments/INE522D01027.jsonl

```

```jsonl
// File: user/news/instruments/INE522F01014.jsonl
{"isin": "INE522F01014", "instrumentKey": "NSE_EQ|INE522F01014", "heading": "Top gainers and losers, June 10: Hindalco, Coal India, ONGC fall 3%, Nestle India, HUL rise 2%; check list", "summary": "On June 10, the NIFTY 50 edged lower by 27.15 points or 0.12% to close flat at 23,214.95. Meanwhile, the SENSEX ended flat at 73,983.18, up by 0.09% or 64.42 points.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-10-hindalco-coal-india-ongc-fall-3-nestle-india-hul-rise-2-check-list/article-195122/", "publishedTime": 1781088957700, "sourceHash": "dd841394e94389534bd6bb197a23cf54eea7af8dcd33fc255ca761045e97dcae"}
{"isin": "INE522F01014", "instrumentKey": "NSE_EQ|INE522F01014", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE528G01035.jsonl

```

```jsonl
// File: user/news/instruments/INE531F01023.jsonl

```

```jsonl
// File: user/news/instruments/INE540L01014.jsonl

```

```jsonl
// File: user/news/instruments/INE545U01014.jsonl
{"isin": "INE545U01014", "instrumentKey": "NSE_EQ|INE545U01014", "heading": "112 stocks hit 52-week high: IFCI, Bandhan Bank, Vi among 20 major stocks at one-year high; check full list", "summary": "The broader market was seen outperforming the main equity indices, with both the NIFTY Midcap 100 index and NIFTY Smallcap 100 climbing 1.8% to their intraday high levels ", "articleLink": "https://upstox.com/news/market-news/stocks/112-stocks-hit-52-week-high-ifci-bandhan-bank-vi-among-20-major-stocks-at-one-year-high-smi-ds-outperform-check-full-list/article-195335/", "publishedTime": 1781511247213, "sourceHash": "acd5a367f5c856cbf85e5c98aec5b9c690bd8eb5bc90516779c9a9f4a77c4235"}
```

```jsonl
// File: user/news/instruments/INE562A01011.jsonl

```

```jsonl
// File: user/news/instruments/INE572E01012.jsonl

```

```jsonl
// File: user/news/instruments/INE584A01023.jsonl

```

```jsonl
// File: user/news/instruments/INE585B01010.jsonl
{"isin": "INE585B01010", "instrumentKey": "NSE_EQ|INE585B01010", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "sourceHash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
```

```jsonl
// File: user/news/instruments/INE591G01025.jsonl

```

```jsonl
// File: user/news/instruments/INE596I01020.jsonl

```

```jsonl
// File: user/news/instruments/INE603J01030.jsonl

```

```jsonl
// File: user/news/instruments/INE619A01035.jsonl

```

```jsonl
// File: user/news/instruments/INE628A01036.jsonl

```

```jsonl
// File: user/news/instruments/INE634S01028.jsonl

```

```jsonl
// File: user/news/instruments/INE646L01027.jsonl
{"isin": "INE646L01027", "instrumentKey": "NSE_EQ|INE646L01027", "heading": "US-Iran peace deal boosts tourism stocks: Nifty India Tourism hits 3-month high; IndiGo, Leela Palaces lead gains", "summary": "All the 15 stocks on the Nifty India Tourism index were trading higher. For a month\u2019s period, the index has rallied 6%, while on a year-on-year basis, it has tumbled 13%", "articleLink": "https://upstox.com/news/market-news/stocks/us-iran-peace-deal-boosts-tourism-stocks-nifty-india-tourism-hits-3-month-high-indi-go-leela-palaces-lead-gains/article-195317/", "publishedTime": 1781502787537, "sourceHash": "c9b65438082f20da7f0ac9dd7ee10c43c4b9b8457f99b7e55be31aadcc169504"}
{"isin": "INE646L01027", "instrumentKey": "NSE_EQ|INE646L01027", "heading": "Indian Oil, Asian Paints, JK Tyre, IndiGo shares jump as US-Iran peace deal sends oil prices tumbling", "summary": "\"The Deal with the Islamic Republic of Iran is now complete. Congratulations to all!\" US President Donald Trump wrote on his Truth Social platform. \"I hereby fully authorize the toll-free opening of the Strait of Hormuz, and, simultaneously herewith, authorize the immediate removal of the United States Naval blockade.\"", "articleLink": "https://upstox.com/news/market-news/stocks/indian-oil-asian-paints-jk-tyre-indi-go-in-focus-as-us-iran-peace-deal-sends-oil-prices-tumbling/article-195300/", "publishedTime": 1781496307908, "sourceHash": "f519e8f51c8527d40695ef1465ee2b1aad4b7e8c51ae5e20925587b52309a664"}
{"isin": "INE646L01027", "instrumentKey": "NSE_EQ|INE646L01027", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "sourceHash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE646L01027", "instrumentKey": "NSE_EQ|INE646L01027", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "sourceHash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
{"isin": "INE646L01027", "instrumentKey": "NSE_EQ|INE646L01027", "heading": "Can IndiGo navigate its near-term cost headwinds? Experts weigh in", "summary": "IndiGo is facing several cost pressures with the rising energy prices and expansion plans, as experts predict the cost pressures are likely to normalise as services return to normalcy.", "articleLink": "https://upstox.com/news/market-news/stocks/can-indi-go-navigate-its-near-term-cost-headwinds-experts-weigh-in/article-195054/", "publishedTime": 1781018309062, "sourceHash": "b9b3a08ae711466da9e6d58a0a4ea94817602d60f8f3c0a5e27eaa204a597046"}
{"isin": "INE646L01027", "instrumentKey": "NSE_EQ|INE646L01027", "heading": "Top gainers and losers, June 9: IndiGo gains 4%, Jio Finserv up 2%, Titan Company down 2%; check list", "summary": "On June 9, the SENSEX surged by 394.50 points or 0.54% to close at 73,918.76, while the NIFTY50 ended higher by 119.10 points or 0.52% at 23,242.10.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-9-indi-go-gains-4-jio-finserv-up-2-titan-company-down-2-check-list/article-195047/", "publishedTime": 1781002812449, "sourceHash": "7e04998d430532babe1a97ace94ba9db4275cb7dbc3c4fa94bee3b8c2e070a2d"}
{"isin": "INE646L01027", "instrumentKey": "NSE_EQ|INE646L01027", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
{"isin": "INE646L01027", "instrumentKey": "NSE_EQ|INE646L01027", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE647A01010.jsonl

```

```jsonl
// File: user/news/instruments/INE663F01032.jsonl

```

```jsonl
// File: user/news/instruments/INE669C01036.jsonl
{"isin": "INE669C01036", "instrumentKey": "NSE_EQ|INE669C01036", "heading": "TCS, Infosys, Tech Mahindra, other IT shares fall amid AI stocks sell-off on Wall Street", "summary": "Wall Street has been on shaky ground since last week, as artificial intelligence stocks have traded lower after reaching record highs.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-tech-mahindra-other-it-stocks-fall-amid-ai-stocks-sell-off-on-wall-street/article-195152/", "publishedTime": 1781154186328, "sourceHash": "0ef341c9234cab441057738f11d2d43560ea11eb33a81923d3b9af746b851768"}
{"isin": "INE669C01036", "instrumentKey": "NSE_EQ|INE669C01036", "heading": "TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump\u2019s $100,000 H-1B visa fee rule", "summary": "A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/", "publishedTime": 1780978356295, "sourceHash": "94b5c2a562a22ca1f6fc2472ae19b1e4a418299d33d3c09c8eeda068a16cb1bc"}
{"isin": "INE669C01036", "instrumentKey": "NSE_EQ|INE669C01036", "heading": "BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details", "summary": "Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ", "articleLink": "https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/", "publishedTime": 1780906190587, "sourceHash": "c2ec158afea8e4e7d041bbc28d47443d11e32184002ba448514c351d5fd33eef"}
```

```jsonl
// File: user/news/instruments/INE669E01016.jsonl
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "112 stocks hit 52-week high: IFCI, Bandhan Bank, Vi among 20 major stocks at one-year high; check full list", "summary": "The broader market was seen outperforming the main equity indices, with both the NIFTY Midcap 100 index and NIFTY Smallcap 100 climbing 1.8% to their intraday high levels ", "articleLink": "https://upstox.com/news/market-news/stocks/112-stocks-hit-52-week-high-ifci-bandhan-bank-vi-among-20-major-stocks-at-one-year-high-smi-ds-outperform-check-full-list/article-195335/", "publishedTime": 1781511247213, "sourceHash": "acd5a367f5c856cbf85e5c98aec5b9c690bd8eb5bc90516779c9a9f4a77c4235"}
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "OMCs, Tata Motors PV, TCS among buzzing stocks as NIFTY up 1.4%, SENSEX climbs 1,202 pts in noon deals", "summary": "From the NIFTY firms, Shriram Finance, InterGlobe Aviation, Bajaj Finance, L&T, HDFC Bank, Jio Financial Services Eternal, Titan, Tata Motors Passenger Vehicles and were among the biggest winners.", "articleLink": "https://upstox.com/news/market-news/stocks/om-cs-tata-motors-pv-tcs-among-buzzing-stocks-as-nifty-up-0-77-sensex-climbs-729-pts-in-noon-deals/article-195250/", "publishedTime": 1781253125381, "sourceHash": "068276d01372b66bf813757a7055edd7450f2250f00bf783ced1fa1b281269c5"}
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround", "summary": "Vodafone Idea reported a net profit of \u20b952,022 crore in March quarter compared with a loss of \u20b97,268 crore in the year-ago period and loss of \u20b95,324 crore in the previous quarter due to relief in statutory liabilities.", "articleLink": "https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/", "publishedTime": 1781075957852, "sourceHash": "4cfc61b8d7241a5fbd871cb24a99f86b4eddf8d672df1c52ccc80b0f9bb76cef"}
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "Bharti Airtel, Vodafone Idea shares rise as Bombay HC quashes govt decision to impose 1-time spectrum charge", "summary": "In November 2012, the Union Cabinet took a decision that a one-time charge would be imposed for spectrum held beyond 6.2 MHz from July 2008 onwards. Following this, demand notices were issued to the petitioners (Bharti Airtel Ltd and Vodafone Idea Ltd) specifying the amounts payable by them towards one-time spectrum charge.", "articleLink": "https://upstox.com/news/market-news/stocks/bharti-airtel-vodafone-idea-shares-rise-as-bombay-hc-quashes-govt-decision-to-impose-1-time-spectrum-charge/article-195011/", "publishedTime": 1780978490137, "sourceHash": "f9af2752c5cbe55cdd88878543700cfa644342c67b721515d4da89ecbe2feed3"}
{"isin": "INE669E01016", "instrumentKey": "NSE_EQ|INE669E01016", "heading": "Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL", "summary": "Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of \u20b9303 per share. The OFS opens for non-retail investors on Tuesday.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/", "publishedTime": 1780975699014, "sourceHash": "9af36ebb0af7fe86ac20cbb37db130270ba63485b04ec054985702314e0c97a4"}
```

```jsonl
// File: user/news/instruments/INE670A01012.jsonl

```

```jsonl
// File: user/news/instruments/INE670K01029.jsonl

```

```jsonl
// File: user/news/instruments/INE674K01013.jsonl

```

```jsonl
// File: user/news/instruments/INE685A01028.jsonl

```

```jsonl
// File: user/news/instruments/INE692A01016.jsonl

```

```jsonl
// File: user/news/instruments/INE702C01027.jsonl

```

```jsonl
// File: user/news/instruments/INE704P01025.jsonl

```

```jsonl
// File: user/news/instruments/INE721A01047.jsonl
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "Top gainers and losers, June 15: Trent, HDFC Life, Shriram Finance surge 5%, NTPC falls 2%; check list", "summary": "On June 15, the SENSEX soared 736.38 points or 0.97% to close at 76,264.33. The 50-share NIFTY ended 231 points or 0.98% higher at 23,853.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-15-trent-hdfc-life-shriram-finance-surge-5-ntpc-falls-2-check-list/article-195357/", "publishedTime": 1781521262994, "sourceHash": "f00d08e42af4f49facdf759cce2287493d1b477d4374f0a4afec923b81bfdeea"}
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "Top gainers and losers, June 12: Shriram Finance climbs 8%, Bajaj Finance rises 6%, Nestle falls 3%; check full list", "summary": "On June 12, the SENSEX climbed by 1,695.41 points or 2.30% to close at 75,527.95, while the NIFTY50 advanced by 461.30 points or 1.99% to end at 23,622.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-12-shriram-finance-climbs-8-bajaj-finance-rises-6-nestle-falls-3-check-full-list/article-195266/", "publishedTime": 1781262446006, "sourceHash": "695fd1b2df08bd00530b8322c0b7569b5143460d2e5585792c2b4ca0397b866d"}
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "sourceHash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "sourceHash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
{"isin": "INE721A01047", "instrumentKey": "NSE_EQ|INE721A01047", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
```

```jsonl
// File: user/news/instruments/INE726G01019.jsonl

```

```jsonl
// File: user/news/instruments/INE732I01021.jsonl

```

```jsonl
// File: user/news/instruments/INE733E01010.jsonl
{"isin": "INE733E01010", "instrumentKey": "NSE_EQ|INE733E01010", "heading": "Top gainers and losers, June 15: Trent, HDFC Life, Shriram Finance surge 5%, NTPC falls 2%; check list", "summary": "On June 15, the SENSEX soared 736.38 points or 0.97% to close at 76,264.33. The 50-share NIFTY ended 231 points or 0.98% higher at 23,853.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-15-trent-hdfc-life-shriram-finance-surge-5-ntpc-falls-2-check-list/article-195357/", "publishedTime": 1781521262994, "sourceHash": "f00d08e42af4f49facdf759cce2287493d1b477d4374f0a4afec923b81bfdeea"}
{"isin": "INE733E01010", "instrumentKey": "NSE_EQ|INE733E01010", "heading": "Defence minister approves 250 MW solar-BESS project on vacant defence land in UP's Sitapur", "summary": "NTPC Limited will implement the project through a competitive bidding process in coordination with the Integrated Headquarters of the Ministry of Defence (Army) and the Directorate General Defence Estates.", "articleLink": "https://upstox.com/news/business-news/latest-updates/defence-minister-approves-250-mw-solar-bess-project-on-vacant-defence-land-in-up-s-sitapur/article-195037/", "publishedTime": 1780995252127, "sourceHash": "dbf0944b0e08dcb425a9d475d2287c322ff441793c5211e6dadb9aa0691dae37"}
{"isin": "INE733E01010", "instrumentKey": "NSE_EQ|INE733E01010", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
{"isin": "INE733E01010", "instrumentKey": "NSE_EQ|INE733E01010", "heading": "BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details", "summary": "Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ", "articleLink": "https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/", "publishedTime": 1780906190587, "sourceHash": "c2ec158afea8e4e7d041bbc28d47443d11e32184002ba448514c351d5fd33eef"}
```

```jsonl
// File: user/news/instruments/INE736A01011.jsonl

```

```jsonl
// File: user/news/instruments/INE742F01042.jsonl
{"isin": "INE742F01042", "instrumentKey": "NSE_EQ|INE742F01042", "heading": "Adani Ports secures 10-year marine services contract for Argentina's first LNG export; details here", "summary": "The contract has been awarded to APSEZ's step-down subsidiary, The Adani Harbour International FZCO, through a consortium with Argentina-based Meridian Group following a global competitive tender process conducted by Southern Energy S.A. (SESA). ", "articleLink": "https://upstox.com/news/market-news/stocks/adani-ports-secures-10-year-marine-services-contract-for-argentina-s-first-lng-export-details-here/article-194943/", "publishedTime": 1780894555448, "sourceHash": "edad350fadd7c59c312d75ff3ae17813d91947e282db5c59577a92dafaa184cd"}
```

```jsonl
// File: user/news/instruments/INE745G01043.jsonl

```

```jsonl
// File: user/news/instruments/INE749A01030.jsonl

```

```jsonl
// File: user/news/instruments/INE752E01010.jsonl
{"isin": "INE752E01010", "instrumentKey": "NSE_EQ|INE752E01010", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
{"isin": "INE752E01010", "instrumentKey": "NSE_EQ|INE752E01010", "heading": "BEL, Tech Mahindra, SBI, NTPC and Power Grid crossed their 200 EMA on Monday; check details", "summary": "Indian benchmark indices opened nearly 1% lower on Monday amid the broader weakness in the global markets. However, few largecap stocks have recouped the morning losses to trade in green and also crossed their 200 EMA levels. ", "articleLink": "https://upstox.com/news/market-news/stocks/bel-tech-mahindra-sbi-ntpc-and-power-grid-crossed-their-200-ema-on-monday-check-details-1/article-194960/", "publishedTime": 1780906190587, "sourceHash": "c2ec158afea8e4e7d041bbc28d47443d11e32184002ba448514c351d5fd33eef"}
{"isin": "INE752E01010", "instrumentKey": "NSE_EQ|INE752E01010", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE758E01017.jsonl
{"isin": "INE758E01017", "instrumentKey": "NSE_EQ|INE758E01017", "heading": "Top gainers and losers, June 9: IndiGo gains 4%, Jio Finserv up 2%, Titan Company down 2%; check list", "summary": "On June 9, the SENSEX surged by 394.50 points or 0.54% to close at 73,918.76, while the NIFTY50 ended higher by 119.10 points or 0.52% at 23,242.10.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-9-indi-go-gains-4-jio-finserv-up-2-titan-company-down-2-check-list/article-195047/", "publishedTime": 1781002812449, "sourceHash": "7e04998d430532babe1a97ace94ba9db4275cb7dbc3c4fa94bee3b8c2e070a2d"}
{"isin": "INE758E01017", "instrumentKey": "NSE_EQ|INE758E01017", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
{"isin": "INE758E01017", "instrumentKey": "NSE_EQ|INE758E01017", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
```

```jsonl
// File: user/news/instruments/INE758T01015.jsonl
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "SENSEX zooms 1,695 pts, NIFTY50 ends at 23,622 as US-Iran truce deal boosts sentiment; NIFTY Bank surges 3%", "summary": "NIFTY50 and SENSEX indices closed over 2% higher on Friday, June 12, after reports emerged of a draft MoU between the United States and Iran, which fuelled a 5% drop in global crude oil prices. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-zooms-1-695-pts-nifty-50-ends-at-23-622-as-us-iran-truce-deal-boosts-sentiment-nifty-bank-surges-3/article-195261/", "publishedTime": 1781264551315, "sourceHash": "0c4a031710dc6f44ea3815b599110b29844591556a82924e74e7b2ccbce4ba52"}
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "TCS, Zee Ent, DOMS Industries among buzzing stocks as SENSEX gains over 200 pts, NIFTY above 23,200", "summary": "Shares of Doms Industries climbed as much as 7.33% to hit an intraday high of \u20b92,273.90 per unit on the NSE on Thursday, after the company entered into an agreement to buy Reynolds Pens India Limited.\n", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-zee-ent-doms-industries-among-buzzing-stocks-as-sensex-gains-over-200-pts-nifty-above-23-200/article-195172/", "publishedTime": 1781163081692, "sourceHash": "85e04ca1d6ac8f3481e563d4459ed32df16783cec362ac9284ccbc8c88d68934"}
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "Zepto vs Blinkit vs Instamart: Which quick commerce platform leads in revenue and profitability ahead of Zepto IPO?", "summary": "Zepto IPO has moved one step closer to its market debut after the company filed its updated DRHP with SEBI. As Zepto prepares to enter the public markets, here\u2019s a comparative look at how the company stacks up against its listed quick commerce peers, Eternal\u2019s Blinkit and Swiggy\u2019s Instamart, across key metrics such as revenue, profitability, and dark stores.", "articleLink": "https://upstox.com/news/market-news/ipo/zepto-vs-blinkit-vs-instamart-which-quick-commerce-platform-leads-in-revenue-and-profitability-ahead-of-zepto-ipo/article-195149/", "publishedTime": 1781153861680, "sourceHash": "d951018e2b17a2bb241309f3b8225c070504d52b8f1364e5a91ebca66ae3e441"}
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "Stocks to watch, June 11: REC, PFC, Eternal, Power Grid, Lenskart Solutions, Meesho, Zee Entertainment", "summary": " Zomato and Blinkit's parent entity, Eternal, has received a \u20b99.63 crore GST demand notice, along with interest and penalty, from the Andhra Pradesh tax authorities.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-11-2026-rec-pfc-eternal-power-grid-lenskart-solutions-meesho-zee-entertainment/article-195142/", "publishedTime": 1781145776128, "sourceHash": "17bd3e3723e25c89d2e82c51456135f0bba039dd1e073a5a45763b9dc083915c"}
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "Eternal share price in focus: Zomato-parent gets GST demand notice of \u20b99.63 crore from Andhra Pradesh authorities", "summary": "Eternal shares will be in focus on Thursday after the firm received a GST order from Andhra Pradesh authorities after the market hours on June 10. Here's what investors should know.", "articleLink": "https://upstox.com/news/market-news/stocks/eternal-share-price-in-focus-zomato-parent-gets-gst-demand-notice-of-9-63-crore-from-andhra-pradesh-authorities/article-195124/", "publishedTime": 1781094559025, "sourceHash": "fcb495244a1b979274b317695e4d7328df7cfd951e7374acffc88c0304e9a21b"}
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "Top gainers and losers, June 8: Wipro tumbles 8%, Jio Finserv, Eternal down 4%, Max Healthcare up 3%; check list", "summary": "On June 8, the S&P BSE SENSEX closed 719.09 points, or 0.97% lower at 73,524.26. Meanwhile, NSE\u2019s NIFTY50 ended at 23,123, reflecting a 243.70-point, or 1.04% decline.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-8-wipro-tumbles-8-jio-finserv-eternal-down-4-max-healthcare-up-3-check-list/article-194980/", "publishedTime": 1780918146424, "sourceHash": "e78d9958ddec8e548a96198b575e79fb32327dc38470fe511fb3d1095293270a"}
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "NIFTY50, SENSEX end 1% lower on June 8 as weak global cues dent investor sentiment", "summary": "The NIFTY50 and SENSEX indices ended nearly around 1% lower after the trading session on Monday, June 8, as weak global cues dented the sentiment of investors in Indian equities amid continued outflows. ", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-end-up-to-1-lower-on-june-8-as-weak-global-cues-grip-investors-sentiment/article-194977/", "publishedTime": 1780917274477, "sourceHash": "0a148cccd34a222509e88b3f2f64d883bb27ad145d23d55007c6391e04599d1e"}
{"isin": "INE758T01015", "instrumentKey": "NSE_EQ|INE758T01015", "heading": "SENSEX drops over 800 pts, NIFTY50 opens at 23,080 amid Asian market sell-off; Wipro, Hindalco among top losers", "summary": "The benchmark NIFTY50 and SENSEX dropped after the opening bell on Monday, June 8, following the sell-off in Asian equities amid the latest round of attacks in West Asia. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-drops-over-800-pts-nifty-50-opens-at-23-080-amid-asian-market-sell-off-wipro-hindalco-tata-steel-among-top-losers/article-194937/", "publishedTime": 1780892719326, "sourceHash": "be1d7a5a3d27d39a34eed74596c103cbf73952989f6f70c2d7511ac570a3c966"}
```

```jsonl
// File: user/news/instruments/INE761H01022.jsonl

```

```jsonl
// File: user/news/instruments/INE765G01017.jsonl

```

```jsonl
// File: user/news/instruments/INE775A01035.jsonl
{"isin": "INE775A01035", "instrumentKey": "NSE_EQ|INE775A01035", "heading": "Nifty Auto rises 2.7%: Tata Motors PV, Maruti Suzuki, Ashok Leyland, other auto stocks lead gains amid lower oil prices", "summary": "Sector benchmark, Nifty Auto, rallied 2.7% on Monday's market after global oil prices continued their downtrend amid Trump's latest announcement of a 'complete' US-Iran peace deal.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-auto-rises-2-7-tata-motors-pv-maruti-suzuki-ashok-leyland-other-auto-stocks-lead-gains-amid-low-oil-prices/article-195315/", "publishedTime": 1781504955244, "sourceHash": "b4ccd0a413fa37bcc32f324b70aa97e740ae104944b320208ca85d31a0770d04"}
```

```jsonl
// File: user/news/instruments/INE776C01039.jsonl

```

```jsonl
// File: user/news/instruments/INE795G01014.jsonl
{"isin": "INE795G01014", "instrumentKey": "NSE_EQ|INE795G01014", "heading": "Top gainers and losers, June 15: Trent, HDFC Life, Shriram Finance surge 5%, NTPC falls 2%; check list", "summary": "On June 15, the SENSEX soared 736.38 points or 0.97% to close at 76,264.33. The 50-share NIFTY ended 231 points or 0.98% higher at 23,853.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-15-trent-hdfc-life-shriram-finance-surge-5-ntpc-falls-2-check-list/article-195357/", "publishedTime": 1781521262994, "sourceHash": "f00d08e42af4f49facdf759cce2287493d1b477d4374f0a4afec923b81bfdeea"}
```

```jsonl
// File: user/news/instruments/INE797F01020.jsonl

```

```jsonl
// File: user/news/instruments/INE811K01011.jsonl

```

```jsonl
// File: user/news/instruments/INE814H01029.jsonl

```

```jsonl
// File: user/news/instruments/INE848E01016.jsonl

```

```jsonl
// File: user/news/instruments/INE849A01020.jsonl
{"isin": "INE849A01020", "instrumentKey": "NSE_EQ|INE849A01020", "heading": "Top gainers and losers, June 15: Trent, HDFC Life, Shriram Finance surge 5%, NTPC falls 2%; check list", "summary": "On June 15, the SENSEX soared 736.38 points or 0.97% to close at 76,264.33. The 50-share NIFTY ended 231 points or 0.98% higher at 23,853.90.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-15-trent-hdfc-life-shriram-finance-surge-5-ntpc-falls-2-check-list/article-195357/", "publishedTime": 1781521262994, "sourceHash": "f00d08e42af4f49facdf759cce2287493d1b477d4374f0a4afec923b81bfdeea"}
{"isin": "INE849A01020", "instrumentKey": "NSE_EQ|INE849A01020", "heading": "NIFTY50, SENSEX rally over 1% on positive global cues; Trent, Shriram Finance, IndiGo lead gains", "summary": "At the opening bell, NIFTY50 surged 1%, while the SENSEX gained 876 points on Friday, June 12, as investors' focus was on positive global clues including low oil prices, US-Iran peace deal breakthrough among other key moves.", "articleLink": "https://upstox.com/news/market-news/stocks/nifty-50-sensex-rally-over-1-on-positive-global-cues-trent-shriram-finance-indi-go-lead-gains/article-195226/", "publishedTime": 1781237907785, "sourceHash": "0e69309ef0b73f00c8c754fa02b36cfb197f01716241f424ca2942a818a120ff"}
{"isin": "INE849A01020", "instrumentKey": "NSE_EQ|INE849A01020", "heading": "Adani Enterprises, Tata Steel, PNB, Tata Motors, Trent dividend record date on June 12: Last day to buy for payout", "summary": "Tata Steel has recommended a dividend of \u20b94 per share of face value of \u20b91 each to the shareholders of the company for FY2025-26. The record date is June 12.", "articleLink": "https://upstox.com/news/market-news/stocks/adani-enterprises-tata-steel-pnb-tata-motors-trent-dividend-record-date-on-june-12-last-day-to-buy-for-payout/article-195146/", "publishedTime": 1781166173232, "sourceHash": "45e05299d99d4e605c62f52b025606c13bf9c0622f055f5dd95531686b275320"}
{"isin": "INE849A01020", "instrumentKey": "NSE_EQ|INE849A01020", "heading": "SENSEX jumps over 500 points, NIFTY50 opens at 23,259 amid positive global cues; IndiGo, Tata Motors PV among gainers", "summary": "The NIFTY50 and BSE SENSEX opened higher on Tuesday, June 9, as the investors focused on a recovery on the backdrop of a mild cooldown in oil prices and a ceasefire between Iran and Israel after recent attacks. ", "articleLink": "https://upstox.com/news/market-news/stocks/sensex-jumps-over-500-points-nifty-50-opens-at-23-259-amid-positive-global-cues-indi-go-tata-motors-pv-among-gainers/article-195007/", "publishedTime": 1780978075718, "sourceHash": "499b4efe6fef3074040fb9d99de01b1084d47b659569d09ad01f3cd2adb8d748"}
```

```jsonl
// File: user/news/instruments/INE854D01024.jsonl

```

```jsonl
// File: user/news/instruments/INE860A01027.jsonl
{"isin": "INE860A01027", "instrumentKey": "NSE_EQ|INE860A01027", "heading": "Top gainers and losers, June 11: Infosys, HCLTech, Adani Ports fall 2%, M&M rises 2%; check list", "summary": "On June 11, the SENSEX slumped by 150.63 points or 0.20% to close at 73,832.55. Meanwhile, NIFTY50 ended at 23,161.60, down by 53.35 points or 0.23%.\n", "articleLink": "https://upstox.com/news/market-news/stocks/top-gainers-and-losers-june-11-infosys-hcl-tech-adani-ports-fall-2-m-and-m-rises-2-check-list/article-195194/", "publishedTime": 1781175583927, "sourceHash": "8662e58006add6cf8f6a88dd62606dfaf7ef21f98879d4a2f8d680cca29badb4"}
{"isin": "INE860A01027", "instrumentKey": "NSE_EQ|INE860A01027", "heading": "TCS, Infosys, HCLTech: IT stocks in focus as US court strikes down Trump\u2019s $100,000 H-1B visa fee rule", "summary": "A United States federal judge has struck down the $100,000 fee that US President Donald Trump imposed on new H-1B visas for highly skilled foreign workers, concluding that it constituted an unlawful tax that Congress never authorised.", "articleLink": "https://upstox.com/news/market-news/stocks/tcs-infosys-hcl-tech-it-stocks-in-focus-as-a-us-court-strikes-down-trump-s-100-000-h-1-b-visa-fee-rule/article-195008/", "publishedTime": 1780978356295, "sourceHash": "94b5c2a562a22ca1f6fc2472ae19b1e4a418299d33d3c09c8eeda068a16cb1bc"}
{"isin": "INE860A01027", "instrumentKey": "NSE_EQ|INE860A01027", "heading": "Stocks to watch, June 9: Bharti Airtel, Vodafone Idea, NLC India, banks, HCLTech, JSW Energy, Grasim, SAIL", "summary": "Shares of NLC India will be in focus as the government is planning to sell up to a 3% stake in the PSU through an offer for sale (OFS) at a floor price of \u20b9303 per share. The OFS opens for non-retail investors on Tuesday.", "articleLink": "https://upstox.com/news/market-news/stocks/stocks-to-watch-june-9-bharti-airtel-vodafone-idea-nlc-india-hcl-tech-jsw-energy-grasim-sail/article-194998/", "publishedTime": 1780975699014, "sourceHash": "9af36ebb0af7fe86ac20cbb37db130270ba63485b04ec054985702314e0c97a4"}
{"isin": "INE860A01027", "instrumentKey": "NSE_EQ|INE860A01027", "heading": "HCLTech shares in the spotlight on collaborating with Google Cloud to launch AI Innovation Zone ", "summary": "The AI Innovation Zone, as per the company, will provide a \u201cdedicated environment\u201d for HCLTech and its clients to design, build, and deploy AI-driven workflows and advance robotics-led innovation.\n", "articleLink": "https://upstox.com/news/market-news/stocks/hcl-tech-shares-in-the-spotlight-on-collaborating-with-google-cloud-to-launch-ai-innovation/article-194984/", "publishedTime": 1780923944477, "sourceHash": "f7d16e5ff41de961074a1291063ed397a16d5978d57222eee47071094f40f11c"}
```

```jsonl
// File: user/news/instruments/INE878B01027.jsonl

```

```jsonl
// File: user/news/instruments/INE881D01027.jsonl

```

```jsonl
// File: user/news/instruments/INE917I01010.jsonl
{"isin": "INE917I01010", "instrumentKey": "NSE_EQ|INE917I01010", "heading": "From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter", "summary": "HDFC Bank reported net profit of \u20b919,221 crore in Q4FY26, marking an increase of 8% from \u20b917,616.14 crore in the year-ago period.", "articleLink": "https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/", "publishedTime": 1780987251866, "sourceHash": "4048fe01ce51183a2647103708a97ce3b3c3e4b3068bd850f548cda6f4d7dbd2"}
```

```jsonl
// File: user/news/instruments/INE918I01026.jsonl

```

```jsonl
// File: user/news/instruments/INE918Z01012.jsonl

```

```jsonl
// File: user/news/instruments/INE931S01010.jsonl

```

```jsonl
// File: user/news/instruments/INE935A01035.jsonl

```

```jsonl
// File: user/news/instruments/INE935N01020.jsonl

```

```jsonl
// File: user/news/instruments/INE944F01028.jsonl
{"isin": "INE944F01028", "instrumentKey": "NSE_EQ|INE944F01028", "heading": "From HDFC Bank to Apollo Tyres, 36 NIFTY500 firms report profit growth for fourth straight quarter", "summary": "HDFC Bank reported net profit of \u20b919,221 crore in Q4FY26, marking an increase of 8% from \u20b917,616.14 crore in the year-ago period.", "articleLink": "https://upstox.com/news/market-news/stocks/from-hdfc-bank-to-apollo-tyres-36-nifty-500-firms-report-profit-growth-for-fourth-straight-quarter/article-195025/", "publishedTime": 1780987251866, "sourceHash": "4048fe01ce51183a2647103708a97ce3b3c3e4b3068bd850f548cda6f4d7dbd2"}
```

```jsonl
// File: user/news/instruments/INE947Q01028.jsonl

```

```jsonl
// File: user/news/instruments/INE949L01017.jsonl

```

```jsonl
// File: user/news/instruments/INE974X01010.jsonl

```

```jsonl
// File: user/news/instruments/INE976G01028.jsonl

```

```jsonl
// File: user/news/instruments/INE982J01020.jsonl
{"isin": "INE982J01020", "instrumentKey": "NSE_EQ|INE982J01020", "heading": "22 NIFTY500 stocks swing to profit in Q4FY26; Vodafone Idea, Paytm, PVR Inox lead turnaround", "summary": "Vodafone Idea reported a net profit of \u20b952,022 crore in March quarter compared with a loss of \u20b97,268 crore in the year-ago period and loss of \u20b95,324 crore in the previous quarter due to relief in statutory liabilities.", "articleLink": "https://upstox.com/news/market-news/stocks/22-nifty-500-stocks-swing-to-profit-in-q4-fy-26-vodafone-idea-paytm-pvr-inox-lead-turnaround/article-195101/", "publishedTime": 1781075957852, "sourceHash": "4cfc61b8d7241a5fbd871cb24a99f86b4eddf8d672df1c52ccc80b0f9bb76cef"}
```

```json
// File: user/news/metadata/INE002A01018.json
{
  "isin": "INE002A01018",
  "totalArticles": 6,
  "latestPublishedTime": 1781506978606,
  "lastUpdated": 1781524683836,
  "last_fetch": "2026-06-15T11:58:03.836280Z"
}
```

```json
// File: user/news/metadata/INE003A01024.json
{
  "isin": "INE003A01024",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692964,
  "last_fetch": "2026-06-15T11:58:12.964486Z"
}
```

```json
// File: user/news/metadata/INE006I01046.json
{
  "isin": "INE006I01046",
  "totalArticles": 1,
  "latestPublishedTime": 1781239380279,
  "lastUpdated": 1781524678552,
  "last_fetch": "2026-06-15T11:57:58.552836Z"
}
```

```json
// File: user/news/metadata/INE009A01021.json
{
  "isin": "INE009A01021",
  "totalArticles": 5,
  "latestPublishedTime": 1781518917990,
  "lastUpdated": 1781524676091,
  "last_fetch": "2026-06-15T11:57:56.091113Z"
}
```

```json
// File: user/news/metadata/INE00H001014.json
{
  "isin": "INE00H001014",
  "totalArticles": 2,
  "latestPublishedTime": 1781153861680,
  "lastUpdated": 1781524689738,
  "last_fetch": "2026-06-15T11:58:09.738860Z"
}
```

```json
// File: user/news/metadata/INE00R701025.json
{
  "isin": "INE00R701025",
  "totalArticles": 1,
  "latestPublishedTime": 1780918365081,
  "lastUpdated": 1781524674659,
  "last_fetch": "2026-06-15T11:57:54.659292Z"
}
```

```json
// File: user/news/metadata/INE010B01027.json
{
  "isin": "INE010B01027",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524686591,
  "last_fetch": "2026-06-15T11:58:06.591222Z"
}
```

```json
// File: user/news/metadata/INE016A01026.json
{
  "isin": "INE016A01026",
  "totalArticles": 2,
  "latestPublishedTime": 1781237178241,
  "lastUpdated": 1781524693194,
  "last_fetch": "2026-06-15T11:58:13.194071Z"
}
```

```json
// File: user/news/metadata/INE018A01030.json
{
  "isin": "INE018A01030",
  "totalArticles": 5,
  "latestPublishedTime": 1781517389502,
  "lastUpdated": 1781524691980,
  "last_fetch": "2026-06-15T11:58:11.980054Z"
}
```

```json
// File: user/news/metadata/INE018E01016.json
{
  "isin": "INE018E01016",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690443,
  "last_fetch": "2026-06-15T11:58:10.443984Z"
}
```

```json
// File: user/news/metadata/INE019A01038.json
{
  "isin": "INE019A01038",
  "totalArticles": 2,
  "latestPublishedTime": 1780992961868,
  "lastUpdated": 1781524683408,
  "last_fetch": "2026-06-15T11:58:03.408526Z"
}
```

```json
// File: user/news/metadata/INE01EA01019.json
{
  "isin": "INE01EA01019",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524676768,
  "last_fetch": "2026-06-15T11:57:56.768101Z"
}
```

```json
// File: user/news/metadata/INE020B01018.json
{
  "isin": "INE020B01018",
  "totalArticles": 3,
  "latestPublishedTime": 1781163081692,
  "lastUpdated": 1781524689361,
  "last_fetch": "2026-06-15T11:58:09.361658Z"
}
```

```json
// File: user/news/metadata/INE021A01026.json
{
  "isin": "INE021A01026",
  "totalArticles": 3,
  "latestPublishedTime": 1781496307908,
  "lastUpdated": 1781524681554,
  "last_fetch": "2026-06-15T11:58:01.554067Z"
}
```

```json
// File: user/news/metadata/INE022Q01020.json
{
  "isin": "INE022Q01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682063,
  "last_fetch": "2026-06-15T11:58:02.063568Z"
}
```

```json
// File: user/news/metadata/INE027H01010.json
{
  "isin": "INE027H01010",
  "totalArticles": 2,
  "latestPublishedTime": 1780918146424,
  "lastUpdated": 1781524679789,
  "last_fetch": "2026-06-15T11:57:59.789600Z"
}
```

```json
// File: user/news/metadata/INE028A01039.json
{
  "isin": "INE028A01039",
  "totalArticles": 1,
  "latestPublishedTime": 1780993166287,
  "lastUpdated": 1781524677227,
  "last_fetch": "2026-06-15T11:57:57.227437Z"
}
```

```json
// File: user/news/metadata/INE029A01011.json
{
  "isin": "INE029A01011",
  "totalArticles": 2,
  "latestPublishedTime": 1781490582777,
  "lastUpdated": 1781524693056,
  "last_fetch": "2026-06-15T11:58:13.056524Z"
}
```

```json
// File: user/news/metadata/INE030A01027.json
{
  "isin": "INE030A01027",
  "totalArticles": 3,
  "latestPublishedTime": 1781088957700,
  "lastUpdated": 1781524676899,
  "last_fetch": "2026-06-15T11:57:56.899228Z"
}
```

```json
// File: user/news/metadata/INE038A01020.json
{
  "isin": "INE038A01020",
  "totalArticles": 3,
  "latestPublishedTime": 1781088957700,
  "lastUpdated": 1781524688924,
  "last_fetch": "2026-06-15T11:58:08.924611Z"
}
```

```json
// File: user/news/metadata/INE040A01034.json
{
  "isin": "INE040A01034",
  "totalArticles": 3,
  "latestPublishedTime": 1781264551315,
  "lastUpdated": 1781524684583,
  "last_fetch": "2026-06-15T11:58:04.583767Z"
}
```

```json
// File: user/news/metadata/INE040H01021.json
{
  "isin": "INE040H01021",
  "totalArticles": 1,
  "latestPublishedTime": 1780994528822,
  "lastUpdated": 1781524691950,
  "last_fetch": "2026-06-15T11:58:11.950622Z"
}
```

```json
// File: user/news/metadata/INE044A01036.json
{
  "isin": "INE044A01036",
  "totalArticles": 2,
  "latestPublishedTime": 1781490582777,
  "lastUpdated": 1781524688684,
  "last_fetch": "2026-06-15T11:58:08.684142Z"
}
```

```json
// File: user/news/metadata/INE047A01021.json
{
  "isin": "INE047A01021",
  "totalArticles": 1,
  "latestPublishedTime": 1780978075718,
  "lastUpdated": 1781524680300,
  "last_fetch": "2026-06-15T11:58:00.300369Z"
}
```

```json
// File: user/news/metadata/INE04I401011.json
{
  "isin": "INE04I401011",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524689337,
  "last_fetch": "2026-06-15T11:58:09.337856Z"
}
```

```json
// File: user/news/metadata/INE053A01029.json
{
  "isin": "INE053A01029",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682991,
  "last_fetch": "2026-06-15T11:58:02.991366Z"
}
```

```json
// File: user/news/metadata/INE053F01010.json
{
  "isin": "INE053F01010",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524681322,
  "last_fetch": "2026-06-15T11:58:01.322748Z"
}
```

```json
// File: user/news/metadata/INE059A01026.json
{
  "isin": "INE059A01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687048,
  "last_fetch": "2026-06-15T11:58:07.048898Z"
}
```

```json
// File: user/news/metadata/INE061F01013.json
{
  "isin": "INE061F01013",
  "totalArticles": 1,
  "latestPublishedTime": 1781075957852,
  "lastUpdated": 1781524689303,
  "last_fetch": "2026-06-15T11:58:09.303240Z"
}
```

```json
// File: user/news/metadata/INE062A01020.json
{
  "isin": "INE062A01020",
  "totalArticles": 2,
  "latestPublishedTime": 1780906190587,
  "lastUpdated": 1781524679025,
  "last_fetch": "2026-06-15T11:57:59.025706Z"
}
```

```json
// File: user/news/metadata/INE066A01021.json
{
  "isin": "INE066A01021",
  "totalArticles": 1,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781524685192,
  "last_fetch": "2026-06-15T11:58:05.192328Z"
}
```

```json
// File: user/news/metadata/INE066F01020.json
{
  "isin": "INE066F01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524683786,
  "last_fetch": "2026-06-15T11:58:03.786234Z"
}
```

```json
// File: user/news/metadata/INE066P01011.json
{
  "isin": "INE066P01011",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524680880,
  "last_fetch": "2026-06-15T11:58:00.880843Z"
}
```

```json
// File: user/news/metadata/INE067A01029.json
{
  "isin": "INE067A01029",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692243,
  "last_fetch": "2026-06-15T11:58:12.243935Z"
}
```

```json
// File: user/news/metadata/INE070A01015.json
{
  "isin": "INE070A01015",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675267,
  "last_fetch": "2026-06-15T11:57:55.267823Z"
}
```

```json
// File: user/news/metadata/INE073K01018.json
{
  "isin": "INE073K01018",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524678327,
  "last_fetch": "2026-06-15T11:57:58.327095Z"
}
```

```json
// File: user/news/metadata/INE075A01022.json
{
  "isin": "INE075A01022",
  "totalArticles": 7,
  "latestPublishedTime": 1781332567326,
  "lastUpdated": 1781524679088,
  "last_fetch": "2026-06-15T11:57:59.088231Z"
}
```

```json
// File: user/news/metadata/INE079A01024.json
{
  "isin": "INE079A01024",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524684137,
  "last_fetch": "2026-06-15T11:58:04.137876Z"
}
```

```json
// File: user/news/metadata/INE07Y701011.json
{
  "isin": "INE07Y701011",
  "totalArticles": 1,
  "latestPublishedTime": 1781248424220,
  "lastUpdated": 1781524692431,
  "last_fetch": "2026-06-15T11:58:12.432009Z"
}
```

```json
// File: user/news/metadata/INE081A01020.json
{
  "isin": "INE081A01020",
  "totalArticles": 4,
  "latestPublishedTime": 1781166173232,
  "lastUpdated": 1781524678951,
  "last_fetch": "2026-06-15T11:57:58.951061Z"
}
```

```json
// File: user/news/metadata/INE084A01016.json
{
  "isin": "INE084A01016",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690117,
  "last_fetch": "2026-06-15T11:58:10.117355Z"
}
```

```json
// File: user/news/metadata/INE089A01031.json
{
  "isin": "INE089A01031",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524685922,
  "last_fetch": "2026-06-15T11:58:05.922951Z"
}
```

```json
// File: user/news/metadata/INE090A01021.json
{
  "isin": "INE090A01021",
  "totalArticles": 2,
  "latestPublishedTime": 1781332567326,
  "lastUpdated": 1781524691639,
  "last_fetch": "2026-06-15T11:58:11.639124Z"
}
```

```json
// File: user/news/metadata/INE092T01019.json
{
  "isin": "INE092T01019",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692002,
  "last_fetch": "2026-06-15T11:58:12.002143Z"
}
```

```json
// File: user/news/metadata/INE093I01010.json
{
  "isin": "INE093I01010",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524678120,
  "last_fetch": "2026-06-15T11:57:58.120968Z"
}
```

```json
// File: user/news/metadata/INE094A01015.json
{
  "isin": "INE094A01015",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524677837,
  "last_fetch": "2026-06-15T11:57:57.837417Z"
}
```

```json
// File: user/news/metadata/INE095A01012.json
{
  "isin": "INE095A01012",
  "totalArticles": 1,
  "latestPublishedTime": 1781075957852,
  "lastUpdated": 1781524688236,
  "last_fetch": "2026-06-15T11:58:08.236597Z"
}
```

```json
// File: user/news/metadata/INE095N01031.json
{
  "isin": "INE095N01031",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524683738,
  "last_fetch": "2026-06-15T11:58:03.738315Z"
}
```

```json
// File: user/news/metadata/INE0BS701011.json
{
  "isin": "INE0BS701011",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524689739,
  "last_fetch": "2026-06-15T11:58:09.739476Z"
}
```

```json
// File: user/news/metadata/INE0J1Y01017.json
{
  "isin": "INE0J1Y01017",
  "totalArticles": 1,
  "latestPublishedTime": 1780886006796,
  "lastUpdated": 1781524684211,
  "last_fetch": "2026-06-15T11:58:04.211873Z"
}
```

```json
// File: user/news/metadata/INE0V6F01027.json
{
  "isin": "INE0V6F01027",
  "totalArticles": 3,
  "latestPublishedTime": 1781077374640,
  "lastUpdated": 1781524686527,
  "last_fetch": "2026-06-15T11:58:06.527234Z"
}
```

```json
// File: user/news/metadata/INE101A01026.json
{
  "isin": "INE101A01026",
  "totalArticles": 4,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781524691341,
  "last_fetch": "2026-06-15T11:58:11.341881Z"
}
```

```json
// File: user/news/metadata/INE102D01028.json
{
  "isin": "INE102D01028",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675747,
  "last_fetch": "2026-06-15T11:57:55.747438Z"
}
```

```json
// File: user/news/metadata/INE111A01025.json
{
  "isin": "INE111A01025",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524678698,
  "last_fetch": "2026-06-15T11:57:58.698607Z"
}
```

```json
// File: user/news/metadata/INE114A01011.json
{
  "isin": "INE114A01011",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692789,
  "last_fetch": "2026-06-15T11:58:12.789186Z"
}
```

```json
// File: user/news/metadata/INE115A01026.json
{
  "isin": "INE115A01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682065,
  "last_fetch": "2026-06-15T11:58:02.065737Z"
}
```

```json
// File: user/news/metadata/INE117A01022.json
{
  "isin": "INE117A01022",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682495,
  "last_fetch": "2026-06-15T11:58:02.496007Z"
}
```

```json
// File: user/news/metadata/INE118A01012.json
{
  "isin": "INE118A01012",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524677671,
  "last_fetch": "2026-06-15T11:57:57.671619Z"
}
```

```json
// File: user/news/metadata/INE118H01025.json
{
  "isin": "INE118H01025",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524688429,
  "last_fetch": "2026-06-15T11:58:08.429158Z"
}
```

```json
// File: user/news/metadata/INE121A01024.json
{
  "isin": "INE121A01024",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524679820,
  "last_fetch": "2026-06-15T11:57:59.820439Z"
}
```

```json
// File: user/news/metadata/INE121E01018.json
{
  "isin": "INE121E01018",
  "totalArticles": 1,
  "latestPublishedTime": 1780975699014,
  "lastUpdated": 1781524683341,
  "last_fetch": "2026-06-15T11:58:03.341427Z"
}
```

```json
// File: user/news/metadata/INE121J01017.json
{
  "isin": "INE121J01017",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524683097,
  "last_fetch": "2026-06-15T11:58:03.097260Z"
}
```

```json
// File: user/news/metadata/INE123W01016.json
{
  "isin": "INE123W01016",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524677509,
  "last_fetch": "2026-06-15T11:57:57.509244Z"
}
```

```json
// File: user/news/metadata/INE127D01025.json
{
  "isin": "INE127D01025",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524681615,
  "last_fetch": "2026-06-15T11:58:01.615238Z"
}
```

```json
// File: user/news/metadata/INE129A01019.json
{
  "isin": "INE129A01019",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692699,
  "last_fetch": "2026-06-15T11:58:12.699249Z"
}
```

```json
// File: user/news/metadata/INE134E01011.json
{
  "isin": "INE134E01011",
  "totalArticles": 3,
  "latestPublishedTime": 1781163081692,
  "lastUpdated": 1781524675968,
  "last_fetch": "2026-06-15T11:57:55.968826Z"
}
```

```json
// File: user/news/metadata/INE138Y01010.json
{
  "isin": "INE138Y01010",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687225,
  "last_fetch": "2026-06-15T11:58:07.225599Z"
}
```

```json
// File: user/news/metadata/INE139A01034.json
{
  "isin": "INE139A01034",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524674695,
  "last_fetch": "2026-06-15T11:57:54.695584Z"
}
```

```json
// File: user/news/metadata/INE148I01020.json
{
  "isin": "INE148I01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687689,
  "last_fetch": "2026-06-15T11:58:07.689515Z"
}
```

```json
// File: user/news/metadata/INE148O01028.json
{
  "isin": "INE148O01028",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524691586,
  "last_fetch": "2026-06-15T11:58:11.586340Z"
}
```

```json
// File: user/news/metadata/INE154A01025.json
{
  "isin": "INE154A01025",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524691209,
  "last_fetch": "2026-06-15T11:58:11.209072Z"
}
```

```json
// File: user/news/metadata/INE155A01022.json
{
  "isin": "INE155A01022",
  "totalArticles": 6,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781524687978,
  "last_fetch": "2026-06-15T11:58:07.978830Z"
}
```

```json
// File: user/news/metadata/INE158A01026.json
{
  "isin": "INE158A01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524677404,
  "last_fetch": "2026-06-15T11:57:57.404150Z"
}
```

```json
// File: user/news/metadata/INE160A01022.json
{
  "isin": "INE160A01022",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675655,
  "last_fetch": "2026-06-15T11:57:55.655709Z"
}
```

```json
// File: user/news/metadata/INE171A01029.json
{
  "isin": "INE171A01029",
  "totalArticles": 3,
  "latestPublishedTime": 1781252951486,
  "lastUpdated": 1781524689933,
  "last_fetch": "2026-06-15T11:58:09.933792Z"
}
```

```json
// File: user/news/metadata/INE171Z01026.json
{
  "isin": "INE171Z01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675233,
  "last_fetch": "2026-06-15T11:57:55.233334Z"
}
```

```json
// File: user/news/metadata/INE176B01034.json
{
  "isin": "INE176B01034",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690841,
  "last_fetch": "2026-06-15T11:58:10.841736Z"
}
```

```json
// File: user/news/metadata/INE180A01020.json
{
  "isin": "INE180A01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687395,
  "last_fetch": "2026-06-15T11:58:07.395874Z"
}
```

```json
// File: user/news/metadata/INE192A01025.json
{
  "isin": "INE192A01025",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524677626,
  "last_fetch": "2026-06-15T11:57:57.626977Z"
}
```

```json
// File: user/news/metadata/INE192R01011.json
{
  "isin": "INE192R01011",
  "totalArticles": 3,
  "latestPublishedTime": 1781006386749,
  "lastUpdated": 1781524686971,
  "last_fetch": "2026-06-15T11:58:06.971654Z"
}
```

```json
// File: user/news/metadata/INE195A01028.json
{
  "isin": "INE195A01028",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524688467,
  "last_fetch": "2026-06-15T11:58:08.467729Z"
}
```

```json
// File: user/news/metadata/INE196A01026.json
{
  "isin": "INE196A01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682945,
  "last_fetch": "2026-06-15T11:58:02.945872Z"
}
```

```json
// File: user/news/metadata/INE200A01026.json
{
  "isin": "INE200A01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524684703,
  "last_fetch": "2026-06-15T11:58:04.703051Z"
}
```

```json
// File: user/news/metadata/INE200M01039.json
{
  "isin": "INE200M01039",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524693150,
  "last_fetch": "2026-06-15T11:58:13.150504Z"
}
```

```json
// File: user/news/metadata/INE202E01016.json
{
  "isin": "INE202E01016",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524685071,
  "last_fetch": "2026-06-15T11:58:05.071279Z"
}
```

```json
// File: user/news/metadata/INE205A01025.json
{
  "isin": "INE205A01025",
  "totalArticles": 8,
  "latestPublishedTime": 1781518000626,
  "lastUpdated": 1781524679598,
  "last_fetch": "2026-06-15T11:57:59.598748Z"
}
```

```json
// File: user/news/metadata/INE208A01029.json
{
  "isin": "INE208A01029",
  "totalArticles": 1,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781524691535,
  "last_fetch": "2026-06-15T11:58:11.535314Z"
}
```

```json
// File: user/news/metadata/INE211B01039.json
{
  "isin": "INE211B01039",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524680784,
  "last_fetch": "2026-06-15T11:58:00.784271Z"
}
```

```json
// File: user/news/metadata/INE213A01029.json
{
  "isin": "INE213A01029",
  "totalArticles": 4,
  "latestPublishedTime": 1781490582777,
  "lastUpdated": 1781524681096,
  "last_fetch": "2026-06-15T11:58:01.096782Z"
}
```

```json
// File: user/news/metadata/INE214T01019.json
{
  "isin": "INE214T01019",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690807,
  "last_fetch": "2026-06-15T11:58:10.807057Z"
}
```

```json
// File: user/news/metadata/INE216A01030.json
{
  "isin": "INE216A01030",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524678641,
  "last_fetch": "2026-06-15T11:57:58.641583Z"
}
```

```json
// File: user/news/metadata/INE226A01021.json
{
  "isin": "INE226A01021",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524680259,
  "last_fetch": "2026-06-15T11:58:00.259068Z"
}
```

```json
// File: user/news/metadata/INE237A01036.json
{
  "isin": "INE237A01036",
  "totalArticles": 1,
  "latestPublishedTime": 1781332567326,
  "lastUpdated": 1781524676580,
  "last_fetch": "2026-06-15T11:57:56.580760Z"
}
```

```json
// File: user/news/metadata/INE238A01034.json
{
  "isin": "INE238A01034",
  "totalArticles": 2,
  "latestPublishedTime": 1781332567326,
  "lastUpdated": 1781524688600,
  "last_fetch": "2026-06-15T11:58:08.600901Z"
}
```

```json
// File: user/news/metadata/INE239A01024.json
{
  "isin": "INE239A01024",
  "totalArticles": 6,
  "latestPublishedTime": 1781509425276,
  "lastUpdated": 1781524682578,
  "last_fetch": "2026-06-15T11:58:02.578433Z"
}
```

```json
// File: user/news/metadata/INE242A01010.json
{
  "isin": "INE242A01010",
  "totalArticles": 4,
  "latestPublishedTime": 1781496307908,
  "lastUpdated": 1781524688969,
  "last_fetch": "2026-06-15T11:58:08.969947Z"
}
```

```json
// File: user/news/metadata/INE245A01021.json
{
  "isin": "INE245A01021",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682945,
  "last_fetch": "2026-06-15T11:58:02.945589Z"
}
```

```json
// File: user/news/metadata/INE249Z01020.json
{
  "isin": "INE249Z01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690880,
  "last_fetch": "2026-06-15T11:58:10.880317Z"
}
```

```json
// File: user/news/metadata/INE257A01026.json
{
  "isin": "INE257A01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692626,
  "last_fetch": "2026-06-15T11:58:12.626733Z"
}
```

```json
// File: user/news/metadata/INE259A01022.json
{
  "isin": "INE259A01022",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692372,
  "last_fetch": "2026-06-15T11:58:12.372662Z"
}
```

```json
// File: user/news/metadata/INE260B01028.json
{
  "isin": "INE260B01028",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524684235,
  "last_fetch": "2026-06-15T11:58:04.235815Z"
}
```

```json
// File: user/news/metadata/INE262H01021.json
{
  "isin": "INE262H01021",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690156,
  "last_fetch": "2026-06-15T11:58:10.156234Z"
}
```

```json
// File: user/news/metadata/INE263A01024.json
{
  "isin": "INE263A01024",
  "totalArticles": 3,
  "latestPublishedTime": 1780917274477,
  "lastUpdated": 1781524689330,
  "last_fetch": "2026-06-15T11:58:09.330228Z"
}
```

```json
// File: user/news/metadata/INE267A01025.json
{
  "isin": "INE267A01025",
  "totalArticles": 1,
  "latestPublishedTime": 1781076230013,
  "lastUpdated": 1781524675163,
  "last_fetch": "2026-06-15T11:57:55.163524Z"
}
```

```json
// File: user/news/metadata/INE271C01023.json
{
  "isin": "INE271C01023",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524685483,
  "last_fetch": "2026-06-15T11:58:05.484019Z"
}
```

```json
// File: user/news/metadata/INE274J01014.json
{
  "isin": "INE274J01014",
  "totalArticles": 1,
  "latestPublishedTime": 1781232986902,
  "lastUpdated": 1781524688022,
  "last_fetch": "2026-06-15T11:58:08.022773Z"
}
```

```json
// File: user/news/metadata/INE280A01028.json
{
  "isin": "INE280A01028",
  "totalArticles": 3,
  "latestPublishedTime": 1781519633623,
  "lastUpdated": 1781524687781,
  "last_fetch": "2026-06-15T11:58:07.781799Z"
}
```

```json
// File: user/news/metadata/INE296A01032.json
{
  "isin": "INE296A01032",
  "totalArticles": 5,
  "latestPublishedTime": 1781264551315,
  "lastUpdated": 1781524683451,
  "last_fetch": "2026-06-15T11:58:03.451524Z"
}
```

```json
// File: user/news/metadata/INE298A01020.json
{
  "isin": "INE298A01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687832,
  "last_fetch": "2026-06-15T11:58:07.832888Z"
}
```

```json
// File: user/news/metadata/INE298J01013.json
{
  "isin": "INE298J01013",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524679740,
  "last_fetch": "2026-06-15T11:57:59.740482Z"
}
```

```json
// File: user/news/metadata/INE299U01018.json
{
  "isin": "INE299U01018",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524684535,
  "last_fetch": "2026-06-15T11:58:04.535091Z"
}
```

```json
// File: user/news/metadata/INE302A01020.json
{
  "isin": "INE302A01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524686708,
  "last_fetch": "2026-06-15T11:58:06.708361Z"
}
```

```json
// File: user/news/metadata/INE303R01014.json
{
  "isin": "INE303R01014",
  "totalArticles": 1,
  "latestPublishedTime": 1781519633623,
  "lastUpdated": 1781524691604,
  "last_fetch": "2026-06-15T11:58:11.604538Z"
}
```

```json
// File: user/news/metadata/INE318A01026.json
{
  "isin": "INE318A01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524685551,
  "last_fetch": "2026-06-15T11:58:05.551123Z"
}
```

```json
// File: user/news/metadata/INE323A01026.json
{
  "isin": "INE323A01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690641,
  "last_fetch": "2026-06-15T11:58:10.641752Z"
}
```

```json
// File: user/news/metadata/INE326A01037.json
{
  "isin": "INE326A01037",
  "totalArticles": 1,
  "latestPublishedTime": 1780909010412,
  "lastUpdated": 1781524680389,
  "last_fetch": "2026-06-15T11:58:00.389214Z"
}
```

```json
// File: user/news/metadata/INE338I01027.json
{
  "isin": "INE338I01027",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524688851,
  "last_fetch": "2026-06-15T11:58:08.851733Z"
}
```

```json
// File: user/news/metadata/INE343H01029.json
{
  "isin": "INE343H01029",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690988,
  "last_fetch": "2026-06-15T11:58:10.988923Z"
}
```

```json
// File: user/news/metadata/INE347G01014.json
{
  "isin": "INE347G01014",
  "totalArticles": 2,
  "latestPublishedTime": 1781509425276,
  "lastUpdated": 1781524692044,
  "last_fetch": "2026-06-15T11:58:12.044774Z"
}
```

```json
// File: user/news/metadata/INE356A01018.json
{
  "isin": "INE356A01018",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524681702,
  "last_fetch": "2026-06-15T11:58:01.702385Z"
}
```

```json
// File: user/news/metadata/INE361B01024.json
{
  "isin": "INE361B01024",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524676580,
  "last_fetch": "2026-06-15T11:57:56.580091Z"
}
```

```json
// File: user/news/metadata/INE364U01010.json
{
  "isin": "INE364U01010",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524688526,
  "last_fetch": "2026-06-15T11:58:08.526024Z"
}
```

```json
// File: user/news/metadata/INE371P01015.json
{
  "isin": "INE371P01015",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524684727,
  "last_fetch": "2026-06-15T11:58:04.727296Z"
}
```

```json
// File: user/news/metadata/INE376G01013.json
{
  "isin": "INE376G01013",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524676127,
  "last_fetch": "2026-06-15T11:57:56.127867Z"
}
```

```json
// File: user/news/metadata/INE377N01017.json
{
  "isin": "INE377N01017",
  "totalArticles": 2,
  "latestPublishedTime": 1781510482882,
  "lastUpdated": 1781524679317,
  "last_fetch": "2026-06-15T11:57:59.317904Z"
}
```

```json
// File: user/news/metadata/INE388Y01029.json
{
  "isin": "INE388Y01029",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682500,
  "last_fetch": "2026-06-15T11:58:02.500129Z"
}
```

```json
// File: user/news/metadata/INE397D01024.json
{
  "isin": "INE397D01024",
  "totalArticles": 8,
  "latestPublishedTime": 1781516419525,
  "lastUpdated": 1781524686793,
  "last_fetch": "2026-06-15T11:58:06.793255Z"
}
```

```json
// File: user/news/metadata/INE405E01023.json
{
  "isin": "INE405E01023",
  "totalArticles": 1,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781524677448,
  "last_fetch": "2026-06-15T11:57:57.448963Z"
}
```

```json
// File: user/news/metadata/INE406A01037.json
{
  "isin": "INE406A01037",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524691283,
  "last_fetch": "2026-06-15T11:58:11.283331Z"
}
```

```json
// File: user/news/metadata/INE414G01012.json
{
  "isin": "INE414G01012",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524680642,
  "last_fetch": "2026-06-15T11:58:00.642531Z"
}
```

```json
// File: user/news/metadata/INE415G01027.json
{
  "isin": "INE415G01027",
  "totalArticles": 1,
  "latestPublishedTime": 1780918365081,
  "lastUpdated": 1781524690544,
  "last_fetch": "2026-06-15T11:58:10.544326Z"
}
```

```json
// File: user/news/metadata/INE417T01026.json
{
  "isin": "INE417T01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690529,
  "last_fetch": "2026-06-15T11:58:10.529155Z"
}
```

```json
// File: user/news/metadata/INE423A01024.json
{
  "isin": "INE423A01024",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692423,
  "last_fetch": "2026-06-15T11:58:12.423987Z"
}
```

```json
// File: user/news/metadata/INE437A01024.json
{
  "isin": "INE437A01024",
  "totalArticles": 1,
  "latestPublishedTime": 1780892719326,
  "lastUpdated": 1781524682677,
  "last_fetch": "2026-06-15T11:58:02.677300Z"
}
```

```json
// File: user/news/metadata/INE451A01017.json
{
  "isin": "INE451A01017",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524686261,
  "last_fetch": "2026-06-15T11:58:06.261680Z"
}
```

```json
// File: user/news/metadata/INE455K01017.json
{
  "isin": "INE455K01017",
  "totalArticles": 2,
  "latestPublishedTime": 1780992961868,
  "lastUpdated": 1781524691250,
  "last_fetch": "2026-06-15T11:58:11.250639Z"
}
```

```json
// File: user/news/metadata/INE457L01029.json
{
  "isin": "INE457L01029",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524684157,
  "last_fetch": "2026-06-15T11:58:04.157988Z"
}
```

```json
// File: user/news/metadata/INE465A01025.json
{
  "isin": "INE465A01025",
  "totalArticles": 1,
  "latestPublishedTime": 1781514660635,
  "lastUpdated": 1781524676371,
  "last_fetch": "2026-06-15T11:57:56.371235Z"
}
```

```json
// File: user/news/metadata/INE466L01038.json
{
  "isin": "INE466L01038",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675234,
  "last_fetch": "2026-06-15T11:57:55.234091Z"
}
```

```json
// File: user/news/metadata/INE467B01029.json
{
  "isin": "INE467B01029",
  "totalArticles": 8,
  "latestPublishedTime": 1781253125381,
  "lastUpdated": 1781524683863,
  "last_fetch": "2026-06-15T11:58:03.863366Z"
}
```

```json
// File: user/news/metadata/INE472A01039.json
{
  "isin": "INE472A01039",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524691663,
  "last_fetch": "2026-06-15T11:58:11.663821Z"
}
```

```json
// File: user/news/metadata/INE476A01022.json
{
  "isin": "INE476A01022",
  "totalArticles": 1,
  "latestPublishedTime": 1780993166287,
  "lastUpdated": 1781524677049,
  "last_fetch": "2026-06-15T11:57:57.049645Z"
}
```

```json
// File: user/news/metadata/INE481G01011.json
{
  "isin": "INE481G01011",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692807,
  "last_fetch": "2026-06-15T11:58:12.807345Z"
}
```

```json
// File: user/news/metadata/INE484J01027.json
{
  "isin": "INE484J01027",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524679520,
  "last_fetch": "2026-06-15T11:57:59.520215Z"
}
```

```json
// File: user/news/metadata/INE494B01023.json
{
  "isin": "INE494B01023",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524686304,
  "last_fetch": "2026-06-15T11:58:06.304780Z"
}
```

```json
// File: user/news/metadata/INE498L01015.json
{
  "isin": "INE498L01015",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524679235,
  "last_fetch": "2026-06-15T11:57:59.235928Z"
}
```

```json
// File: user/news/metadata/INE522D01027.json
{
  "isin": "INE522D01027",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524674631,
  "last_fetch": "2026-06-15T11:57:54.631959Z"
}
```

```json
// File: user/news/metadata/INE522F01014.json
{
  "isin": "INE522F01014",
  "totalArticles": 2,
  "latestPublishedTime": 1781088957700,
  "lastUpdated": 1781524683410,
  "last_fetch": "2026-06-15T11:58:03.410223Z"
}
```

```json
// File: user/news/metadata/INE528G01035.json
{
  "isin": "INE528G01035",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524678080,
  "last_fetch": "2026-06-15T11:57:58.080652Z"
}
```

```json
// File: user/news/metadata/INE531F01023.json
{
  "isin": "INE531F01023",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524678051,
  "last_fetch": "2026-06-15T11:57:58.051323Z"
}
```

```json
// File: user/news/metadata/INE540L01014.json
{
  "isin": "INE540L01014",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524676468,
  "last_fetch": "2026-06-15T11:57:56.468328Z"
}
```

```json
// File: user/news/metadata/INE545U01014.json
{
  "isin": "INE545U01014",
  "totalArticles": 1,
  "latestPublishedTime": 1781511247213,
  "lastUpdated": 1781524690917,
  "last_fetch": "2026-06-15T11:58:10.917814Z"
}
```

```json
// File: user/news/metadata/INE562A01011.json
{
  "isin": "INE562A01011",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524688366,
  "last_fetch": "2026-06-15T11:58:08.366862Z"
}
```

```json
// File: user/news/metadata/INE572E01012.json
{
  "isin": "INE572E01012",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524688148,
  "last_fetch": "2026-06-15T11:58:08.148144Z"
}
```

```json
// File: user/news/metadata/INE584A01023.json
{
  "isin": "INE584A01023",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524680496,
  "last_fetch": "2026-06-15T11:58:00.496478Z"
}
```

```json
// File: user/news/metadata/INE585B01010.json
{
  "isin": "INE585B01010",
  "totalArticles": 1,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781524674753,
  "last_fetch": "2026-06-15T11:57:54.753367Z"
}
```

```json
// File: user/news/metadata/INE591G01025.json
{
  "isin": "INE591G01025",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675555,
  "last_fetch": "2026-06-15T11:57:55.555459Z"
}
```

```json
// File: user/news/metadata/INE596I01020.json
{
  "isin": "INE596I01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682127,
  "last_fetch": "2026-06-15T11:58:02.127722Z"
}
```

```json
// File: user/news/metadata/INE603J01030.json
{
  "isin": "INE603J01030",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524684985,
  "last_fetch": "2026-06-15T11:58:04.985413Z"
}
```

```json
// File: user/news/metadata/INE619A01035.json
{
  "isin": "INE619A01035",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524676160,
  "last_fetch": "2026-06-15T11:57:56.160367Z"
}
```

```json
// File: user/news/metadata/INE628A01036.json
{
  "isin": "INE628A01036",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524691910,
  "last_fetch": "2026-06-15T11:58:11.910060Z"
}
```

```json
// File: user/news/metadata/INE634S01028.json
{
  "isin": "INE634S01028",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524686705,
  "last_fetch": "2026-06-15T11:58:06.705478Z"
}
```

```json
// File: user/news/metadata/INE646L01027.json
{
  "isin": "INE646L01027",
  "totalArticles": 8,
  "latestPublishedTime": 1781502787537,
  "lastUpdated": 1781524686365,
  "last_fetch": "2026-06-15T11:58:06.365891Z"
}
```

```json
// File: user/news/metadata/INE647A01010.json
{
  "isin": "INE647A01010",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524689650,
  "last_fetch": "2026-06-15T11:58:09.650811Z"
}
```

```json
// File: user/news/metadata/INE663F01032.json
{
  "isin": "INE663F01032",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524685159,
  "last_fetch": "2026-06-15T11:58:05.159880Z"
}
```

```json
// File: user/news/metadata/INE669C01036.json
{
  "isin": "INE669C01036",
  "totalArticles": 3,
  "latestPublishedTime": 1781154186328,
  "lastUpdated": 1781524678519,
  "last_fetch": "2026-06-15T11:57:58.519285Z"
}
```

```json
// File: user/news/metadata/INE669E01016.json
{
  "isin": "INE669E01016",
  "totalArticles": 5,
  "latestPublishedTime": 1781511247213,
  "lastUpdated": 1781524680698,
  "last_fetch": "2026-06-15T11:58:00.698592Z"
}
```

```json
// File: user/news/metadata/INE670A01012.json
{
  "isin": "INE670A01012",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524693160,
  "last_fetch": "2026-06-15T11:58:13.160917Z"
}
```

```json
// File: user/news/metadata/INE670K01029.json
{
  "isin": "INE670K01029",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524677316,
  "last_fetch": "2026-06-15T11:57:57.316475Z"
}
```

```json
// File: user/news/metadata/INE674K01013.json
{
  "isin": "INE674K01013",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524677795,
  "last_fetch": "2026-06-15T11:57:57.795720Z"
}
```

```json
// File: user/news/metadata/INE685A01028.json
{
  "isin": "INE685A01028",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524689530,
  "last_fetch": "2026-06-15T11:58:09.530107Z"
}
```

```json
// File: user/news/metadata/INE692A01016.json
{
  "isin": "INE692A01016",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524689101,
  "last_fetch": "2026-06-15T11:58:09.101364Z"
}
```

```json
// File: user/news/metadata/INE702C01027.json
{
  "isin": "INE702C01027",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524682286,
  "last_fetch": "2026-06-15T11:58:02.286070Z"
}
```

```json
// File: user/news/metadata/INE704P01025.json
{
  "isin": "INE704P01025",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524681253,
  "last_fetch": "2026-06-15T11:58:01.253988Z"
}
```

```json
// File: user/news/metadata/INE721A01047.json
{
  "isin": "INE721A01047",
  "totalArticles": 5,
  "latestPublishedTime": 1781521262994,
  "lastUpdated": 1781524677089,
  "last_fetch": "2026-06-15T11:57:57.089393Z"
}
```

```json
// File: user/news/metadata/INE726G01019.json
{
  "isin": "INE726G01019",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524678211,
  "last_fetch": "2026-06-15T11:57:58.211716Z"
}
```

```json
// File: user/news/metadata/INE732I01021.json
{
  "isin": "INE732I01021",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687191,
  "last_fetch": "2026-06-15T11:58:07.191933Z"
}
```

```json
// File: user/news/metadata/INE733E01010.json
{
  "isin": "INE733E01010",
  "totalArticles": 4,
  "latestPublishedTime": 1781521262994,
  "lastUpdated": 1781524681828,
  "last_fetch": "2026-06-15T11:58:01.828439Z"
}
```

```json
// File: user/news/metadata/INE736A01011.json
{
  "isin": "INE736A01011",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675621,
  "last_fetch": "2026-06-15T11:57:55.621339Z"
}
```

```json
// File: user/news/metadata/INE742F01042.json
{
  "isin": "INE742F01042",
  "totalArticles": 1,
  "latestPublishedTime": 1780894555448,
  "lastUpdated": 1781524685354,
  "last_fetch": "2026-06-15T11:58:05.354987Z"
}
```

```json
// File: user/news/metadata/INE745G01043.json
{
  "isin": "INE745G01043",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524677029,
  "last_fetch": "2026-06-15T11:57:57.029668Z"
}
```

```json
// File: user/news/metadata/INE749A01030.json
{
  "isin": "INE749A01030",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675297,
  "last_fetch": "2026-06-15T11:57:55.297662Z"
}
```

```json
// File: user/news/metadata/INE752E01010.json
{
  "isin": "INE752E01010",
  "totalArticles": 3,
  "latestPublishedTime": 1780917274477,
  "lastUpdated": 1781524685415,
  "last_fetch": "2026-06-15T11:58:05.415056Z"
}
```

```json
// File: user/news/metadata/INE758E01017.json
{
  "isin": "INE758E01017",
  "totalArticles": 3,
  "latestPublishedTime": 1781002812449,
  "lastUpdated": 1781524685802,
  "last_fetch": "2026-06-15T11:58:05.802327Z"
}
```

```json
// File: user/news/metadata/INE758T01015.json
{
  "isin": "INE758T01015",
  "totalArticles": 9,
  "latestPublishedTime": 1781264551315,
  "lastUpdated": 1781524691222,
  "last_fetch": "2026-06-15T11:58:11.222313Z"
}
```

```json
// File: user/news/metadata/INE761H01022.json
{
  "isin": "INE761H01022",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524686185,
  "last_fetch": "2026-06-15T11:58:06.185870Z"
}
```

```json
// File: user/news/metadata/INE765G01017.json
{
  "isin": "INE765G01017",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692471,
  "last_fetch": "2026-06-15T11:58:12.471358Z"
}
```

```json
// File: user/news/metadata/INE775A01035.json
{
  "isin": "INE775A01035",
  "totalArticles": 1,
  "latestPublishedTime": 1781504955244,
  "lastUpdated": 1781524690108,
  "last_fetch": "2026-06-15T11:58:10.108643Z"
}
```

```json
// File: user/news/metadata/INE776C01039.json
{
  "isin": "INE776C01039",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690501,
  "last_fetch": "2026-06-15T11:58:10.501567Z"
}
```

```json
// File: user/news/metadata/INE795G01014.json
{
  "isin": "INE795G01014",
  "totalArticles": 1,
  "latestPublishedTime": 1781521262994,
  "lastUpdated": 1781524687650,
  "last_fetch": "2026-06-15T11:58:07.650919Z"
}
```

```json
// File: user/news/metadata/INE797F01020.json
{
  "isin": "INE797F01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687225,
  "last_fetch": "2026-06-15T11:58:07.225886Z"
}
```

```json
// File: user/news/metadata/INE811K01011.json
{
  "isin": "INE811K01011",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524676635,
  "last_fetch": "2026-06-15T11:57:56.635057Z"
}
```

```json
// File: user/news/metadata/INE814H01029.json
{
  "isin": "INE814H01029",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524675686,
  "last_fetch": "2026-06-15T11:57:55.686940Z"
}
```

```json
// File: user/news/metadata/INE848E01016.json
{
  "isin": "INE848E01016",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524685977,
  "last_fetch": "2026-06-15T11:58:05.977765Z"
}
```

```json
// File: user/news/metadata/INE849A01020.json
{
  "isin": "INE849A01020",
  "totalArticles": 4,
  "latestPublishedTime": 1781521262994,
  "lastUpdated": 1781524678574,
  "last_fetch": "2026-06-15T11:57:58.574515Z"
}
```

```json
// File: user/news/metadata/INE854D01024.json
{
  "isin": "INE854D01024",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524680081,
  "last_fetch": "2026-06-15T11:58:00.081143Z"
}
```

```json
// File: user/news/metadata/INE860A01027.json
{
  "isin": "INE860A01027",
  "totalArticles": 4,
  "latestPublishedTime": 1781175583927,
  "lastUpdated": 1781524689690,
  "last_fetch": "2026-06-15T11:58:09.690608Z"
}
```

```json
// File: user/news/metadata/INE878B01027.json
{
  "isin": "INE878B01027",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690258,
  "last_fetch": "2026-06-15T11:58:10.258279Z"
}
```

```json
// File: user/news/metadata/INE881D01027.json
{
  "isin": "INE881D01027",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687603,
  "last_fetch": "2026-06-15T11:58:07.603264Z"
}
```

```json
// File: user/news/metadata/INE917I01010.json
{
  "isin": "INE917I01010",
  "totalArticles": 1,
  "latestPublishedTime": 1780987251866,
  "lastUpdated": 1781524674733,
  "last_fetch": "2026-06-15T11:57:54.733869Z"
}
```

```json
// File: user/news/metadata/INE918I01026.json
{
  "isin": "INE918I01026",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524685780,
  "last_fetch": "2026-06-15T11:58:05.780443Z"
}
```

```json
// File: user/news/metadata/INE918Z01012.json
{
  "isin": "INE918Z01012",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524676042,
  "last_fetch": "2026-06-15T11:57:56.042156Z"
}
```

```json
// File: user/news/metadata/INE931S01010.json
{
  "isin": "INE931S01010",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524681123,
  "last_fetch": "2026-06-15T11:58:01.123873Z"
}
```

```json
// File: user/news/metadata/INE935A01035.json
{
  "isin": "INE935A01035",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524692828,
  "last_fetch": "2026-06-15T11:58:12.828456Z"
}
```

```json
// File: user/news/metadata/INE935N01020.json
{
  "isin": "INE935N01020",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524688854,
  "last_fetch": "2026-06-15T11:58:08.854656Z"
}
```

```json
// File: user/news/metadata/INE944F01028.json
{
  "isin": "INE944F01028",
  "totalArticles": 1,
  "latestPublishedTime": 1780987251866,
  "lastUpdated": 1781524688054,
  "last_fetch": "2026-06-15T11:58:08.054756Z"
}
```

```json
// File: user/news/metadata/INE947Q01028.json
{
  "isin": "INE947Q01028",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524685630,
  "last_fetch": "2026-06-15T11:58:05.630860Z"
}
```

```json
// File: user/news/metadata/INE949L01017.json
{
  "isin": "INE949L01017",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524687449,
  "last_fetch": "2026-06-15T11:58:07.449059Z"
}
```

```json
// File: user/news/metadata/INE974X01010.json
{
  "isin": "INE974X01010",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524679983,
  "last_fetch": "2026-06-15T11:57:59.983740Z"
}
```

```json
// File: user/news/metadata/INE976G01028.json
{
  "isin": "INE976G01028",
  "totalArticles": 0,
  "latestPublishedTime": 0,
  "lastUpdated": 1781524690067,
  "last_fetch": "2026-06-15T11:58:10.067556Z"
}
```

```json
// File: user/news/metadata/INE982J01020.json
{
  "isin": "INE982J01020",
  "totalArticles": 1,
  "latestPublishedTime": 1781075957852,
  "lastUpdated": 1781524686132,
  "last_fetch": "2026-06-15T11:58:06.132421Z"
}
```

```jsonl
// File: user/news/positions.jsonl

```

```json
// File: user/news/state/holdings_snapshot.json
[]
```

```json
// File: user/news/state/positions_snapshot.json
[]
```
