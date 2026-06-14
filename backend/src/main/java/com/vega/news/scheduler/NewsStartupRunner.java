package com.vega.news.scheduler;

import com.vega.news.service.PortfolioNewsBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsStartupRunner implements CommandLineRunner {

    private final PortfolioNewsBuilderService builderService;

    @Override
    public void run(String... args) {
        log.info("Vega News Application started. Initializing views...");
        builderService.buildHoldingsView();
        builderService.buildPositionsView();
        log.info("Initialization complete.");
    }
}
