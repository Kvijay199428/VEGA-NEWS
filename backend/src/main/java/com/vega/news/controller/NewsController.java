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
