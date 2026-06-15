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
            try {
                JsonNode root = objectMapper.readTree(hashPath.toFile());
                JsonNode hashesNode = root.path("hashes");
                if (hashesNode.isArray()) {
                    Set<String> hashes = new HashSet<>();
                    for (JsonNode node : hashesNode) {
                        hashes.add(node.asText());
                    }
                    return hashes;
                }
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
            Map<String, Object> content = new HashMap<>();
            content.put("isin", isin);
            content.put("hashes", hashes);
            Files.writeString(hashPath, objectMapper.writeValueAsString(content));
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
        return Paths.get(properties.getStorage().getRoot(), "metadata", "hashes", isin + ".json");
    }
}
