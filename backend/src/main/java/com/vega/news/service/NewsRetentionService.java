package com.vega.news.service;

import com.vega.news.config.NewsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsRetentionService {

    private final NewsInstrumentArchiveService archiveService;
    private final NewsProperties properties;

    // Run at 1 AM every day
    @Scheduled(cron = "0 0 1 * * ?")
    public void runRetentionCleanup() {
        log.info("Starting scheduled news retention cleanup");
        int retentionDays = properties.getRetention().getDays();
        long cutoffTime = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);

        List<String> isins = archiveService.getAllArchivedIsins();
        log.info("Checking retention for {} archived ISINs. Cutoff time: {}", isins.size(), cutoffTime);

        for (String isin : isins) {
            try {
                archiveService.performRetentionCleanup(isin, cutoffTime);
            } catch (Exception e) {
                log.error("Failed to perform retention cleanup for ISIN: {}", isin, e);
            }
        }
        log.info("Finished news retention cleanup");
    }
}
