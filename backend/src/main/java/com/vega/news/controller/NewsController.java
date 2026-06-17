package com.vega.news.controller;

import com.vega.news.config.NewsProperties;
import com.vega.news.model.NewsErrorResponse;
import com.vega.news.service.InstrumentService;
import jakarta.servlet.http.HttpServletRequest;
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
        
        File[] archives = instrumentsDir.listFiles((d, name) -> name.endsWith(".jsonl"));
        long emptyArchives = 0;
        long totalArticles = 0;
        if (archives != null) {
            for (File f : archives) {
                if (f.length() == 0) {
                    emptyArchives++;
                } else {
                    totalArticles += countLines(f);
                }
            }
        }
        
        stats.put("status", "healthy");
        stats.put("fnoInstruments", expectedFno);
        stats.put("archives", archiveCount);
        stats.put("metadata", metadataCount);
        stats.put("emptyArchives", emptyArchives);
        stats.put("archivesWithNews", archiveCount - emptyArchives);
        stats.put("totalArticles", totalArticles);
        stats.put("missingArchives", Math.max(0, expectedFno - archiveCount));
        stats.put("storageRoot", storageRoot.getAbsolutePath());
        
        return ResponseEntity.ok(stats);
    }

    private long countFiles(File dir, String extension) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles((d, name) -> name.endsWith(extension));
        return files != null ? files.length : 0;
    }

    private long countLines(File file) {
        try {
            return java.nio.file.Files.lines(file.toPath()).count();
        } catch (Exception e) {
            return 0;
        }
    }

    @GetMapping(value = "/instrument/{isin}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getInstrumentNews(@PathVariable String isin, HttpServletRequest request) {
        long startTime = System.currentTimeMillis();
        InstrumentService.InstrumentInfo info = instrumentService.getInstrument(isin);
        String symbol = info != null ? info.getSymbol() : "UNKNOWN";
        String company = info != null ? info.getName() : "UNKNOWN";

        log.info("[REQUEST] Type=InstrumentNews ISIN={} Symbol={} Company={} RemoteIP={}", 
                isin, symbol, company, request.getRemoteAddr());
        
        if (isin == null || isin.trim().isEmpty() || !isin.matches("^[A-Z0-9]{12}$")) {
            log.warn("[INVALID_REQUEST] Input={} Reason=INVALID_ISIN", isin);
            return ResponseEntity.badRequest().body(NewsErrorResponse.builder()
                    .status("error")
                    .code("INVALID_ISIN")
                    .isin(isin)
                    .message("The provided ISIN is invalid.")
                    .build());
        }
        
        File file = new File(properties.getStorage().getRoot() + "/instruments/" + isin + ".jsonl");

        if (!file.exists()) {
            log.info("[NOT_FOUND] ISIN={} Symbol={} ArchiveExists=false", isin, symbol);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NewsErrorResponse.builder()
                    .status("error")
                    .code("ARCHIVE_NOT_FOUND")
                    .isin(isin)
                    .message("News archive not found for the requested ISIN.")
                    .build());
        }

        if (file.length() == 0) {
            log.info("[RESPONSE] ISIN={} Status=200 ArchiveExists=true Articles=0 Duration={}ms", 
                    isin, System.currentTimeMillis() - startTime);
            return ResponseEntity.ok().body(NewsErrorResponse.builder()
                    .status("success")
                    .code("NO_ARTICLES")
                    .isin(isin)
                    .message("No articles currently available.")
                    .build());
        }
        
        long articleCount = countLines(file);
        log.info("[RESPONSE] ISIN={} Status=200 ArchiveExists=true Articles={} Bytes={} Duration={}ms", 
                isin, articleCount, file.length(), System.currentTimeMillis() - startTime);

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "/holdings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getHoldingsNews(HttpServletRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("[REQUEST] Type=HoldingsNews RemoteIP={}", request.getRemoteAddr());
        
        File file = new File(properties.getStorage().getHoldingsView());

        if (!file.exists() || file.length() == 0) {
            log.info("[RESPONSE] Type=HoldingsNews Status=404 ArchiveExists={} Duration={}ms", 
                    file.exists(), System.currentTimeMillis() - startTime);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NewsErrorResponse.builder()
                    .status("error")
                    .code("VIEW_NOT_FOUND")
                    .message("Holdings news view not found or empty.")
                    .build());
        }
        
        long articleCount = countLines(file);
        log.info("[HOLDINGS] Articles={} Size={}KB", articleCount, file.length() / 1024);
        log.info("[RESPONSE] Type=HoldingsNews Status=200 Articles={} Bytes={} Duration={}ms", 
                articleCount, file.length(), System.currentTimeMillis() - startTime);

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "/positions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getPositionsNews(HttpServletRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("[REQUEST] Type=PositionsNews RemoteIP={}", request.getRemoteAddr());
        
        File file = new File(properties.getStorage().getPositionsView());

        if (!file.exists() || file.length() == 0) {
            log.info("[RESPONSE] Type=PositionsNews Status=404 ArchiveExists={} Duration={}ms", 
                    file.exists(), System.currentTimeMillis() - startTime);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NewsErrorResponse.builder()
                    .status("error")
                    .code("VIEW_NOT_FOUND")
                    .message("Positions news view not found or empty.")
                    .build());
        }
        
        long articleCount = countLines(file);
        log.info("[POSITIONS] Articles={} Size={}KB", articleCount, file.length() / 1024);
        log.info("[RESPONSE] Type=PositionsNews Status=200 Articles={} Bytes={} Duration={}ms", 
                articleCount, file.length(), System.currentTimeMillis() - startTime);

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
