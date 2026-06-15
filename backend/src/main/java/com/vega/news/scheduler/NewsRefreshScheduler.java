package com.vega.news.scheduler;

import com.vega.news.service.PortfolioNewsBuilderService;
import com.vega.news.service.NewsRefreshCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsRefreshScheduler {

    private final PortfolioNewsBuilderService builderService;
    private final NewsRefreshCoordinator coordinator;

    // Refresh holdings every 15 minutes
    @Scheduled(fixedDelayString = "PT15M")
    public void refreshHoldingsNews() {
        if (!coordinator.tryLockRefresh()) {
            log.warn("Skipping Holdings News refresh: already running");
            return;
        }
        try {
            log.info("Running scheduled refresh for Holdings News");
            builderService.buildHoldingsView();
        } finally {
            coordinator.unlockRefresh();
        }
    }

    // Refresh positions every 15 minutes
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public void refreshPositionsNews() {
        if (!coordinator.tryLockRefresh()) {
            log.warn("Skipping Positions News refresh: already running");
            return;
        }
        try {
            log.info("Running scheduled refresh for Positions News");
            builderService.buildPositionsView();
        } finally {
            coordinator.unlockRefresh();
        }
    }
}
