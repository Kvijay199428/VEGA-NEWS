package com.vega.news.scheduler;

import com.vega.news.service.PortfolioNewsBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsRefreshScheduler {

    private final PortfolioNewsBuilderService builderService;

    // Refresh holdings every 15 minutes
    @Scheduled(fixedDelayString = "PT15M")
    public void refreshHoldingsNews() {
        log.info("Running scheduled refresh for Holdings News");
        builderService.buildHoldingsView();
    }

    // Refresh positions every 15 minutes
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    public void refreshPositionsNews() {
        log.info("Running scheduled refresh for Positions News");
        builderService.buildPositionsView();
    }
}
