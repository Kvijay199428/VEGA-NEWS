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

