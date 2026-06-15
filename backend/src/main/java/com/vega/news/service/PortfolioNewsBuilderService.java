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
    private final NewsMergeService mergeService;
    private final ObjectMapper objectMapper;
    private final NewsProperties properties;
    private final PortfolioSnapshotService snapshotService;

    public void buildHoldingsView() {
        log.info("Building Holdings News View...");
        Set<String> currentIsins = readerService.readHoldingsIsins();
        Set<String> previousIsins = snapshotService.loadSnapshot("holdings");

        // Detect changes
        java.util.Set<String> addedIsins = new java.util.HashSet<>(currentIsins);
        addedIsins.removeAll(previousIsins);

        if (!addedIsins.isEmpty()) {
            log.info("Detected {} new ISINs in holdings. Refreshing news for them.", addedIsins.size());
            instrumentNewsService.refreshNews(addedIsins);
        } else {
            log.info("No new ISINs detected in holdings. Skipping API fetch.");
        }

        buildView(currentIsins, Paths.get(properties.getStorage().getHoldingsView()));
        snapshotService.saveSnapshot("holdings", currentIsins);
    }

    public void buildPositionsView() {
        log.info("Building Positions News View...");
        Set<String> currentIsins = readerService.readPositionsIsins();
        Set<String> previousIsins = snapshotService.loadSnapshot("positions");

        // Detect changes
        java.util.Set<String> addedIsins = new java.util.HashSet<>(currentIsins);
        addedIsins.removeAll(previousIsins);

        if (!addedIsins.isEmpty()) {
            log.info("Detected {} new ISINs in positions. Refreshing news for them.", addedIsins.size());
            instrumentNewsService.refreshNews(addedIsins);
        } else {
            log.info("No new ISINs detected in positions. Skipping API fetch.");
        }

        buildView(currentIsins, Paths.get(properties.getStorage().getPositionsView()));
        snapshotService.saveSnapshot("positions", currentIsins);
    }

    private void buildView(Set<String> isins, Path outputPath) {
        if (isins.isEmpty()) {
            log.info("No ISINs found for view: {}", outputPath);
            return;
        }

        List<List<NewsArticle>> allArchives = new ArrayList<>(instrumentNewsService.getArchivedNews(isins).values());

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
