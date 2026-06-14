package com.vega.news.service;

import com.vega.news.client.UpstoxNewsClient;
import com.vega.news.model.NewsArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentNewsService {

    private final UpstoxNewsClient newsClient;
    private final NewsInstrumentArchiveService archiveService;
    private final InstrumentService instrumentService;

    public List<NewsArticle> getInstrumentNews(String isin) {
        // 1. Check archive first
        List<NewsArticle> archivedArticles = archiveService.loadArchive(isin);

        // 2. Fetch missing / latest from upstox
        String instrumentKey = instrumentService.getInstrumentKeyByIsin(isin);
        if (instrumentKey != null) {
            List<NewsArticle> fetchedArticles = newsClient.fetchNews("instrument_keys", isin, instrumentKey);
            if (!fetchedArticles.isEmpty()) {
                archiveService.appendNewArticles(isin, fetchedArticles);
                // Reload after append to return the full unified list
                archivedArticles = archiveService.loadArchive(isin);
            }
        }

        // Return latest first
        archivedArticles.sort((a, b) -> Long.compare(b.getPublishedTime(), a.getPublishedTime()));
        return archivedArticles;
    }
}
