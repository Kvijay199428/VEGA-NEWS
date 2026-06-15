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
        log.info("Instrument news request received. ISIN={}", isin);
        
        if (isin == null || isin.trim().isEmpty() || !isin.matches("^[A-Z0-9]{12}$")) {
            return ResponseEntity.badRequest().build();
        }
        
        File file = new File(properties.getStorage().getRoot() + "/instruments/" + isin + ".jsonl");
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        
        log.info("Serving archive file {}", file.getAbsolutePath());
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "/holdings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> getHoldingsNews() {
        log.info("Holdings news request received.");
        File file = new File(properties.getStorage().getHoldingsView());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        
        log.info("Serving holdings file {}", file.getAbsolutePath());
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping(value = "/positions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> getPositionsNews() {
        log.info("Positions news request received.");
        File file = new File(properties.getStorage().getPositionsView());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        
        log.info("Serving positions file {}", file.getAbsolutePath());
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
