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
