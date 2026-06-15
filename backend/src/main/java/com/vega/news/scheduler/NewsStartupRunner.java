package com.vega.news.scheduler;

import com.vega.news.service.PortfolioNewsBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

@Slf4j
@Component
@RequiredArgsConstructor
public class NewsStartupRunner implements CommandLineRunner {

    private final PortfolioNewsBuilderService builderService;
    private final ExecutorService virtualThreadExecutor;

    @Override
    public void run(String... args) {
        log.info("Vega News Application started. Initializing views in background...");
        virtualThreadExecutor.submit(() -> {
            try {
                builderService.buildHoldingsView();
                builderService.buildPositionsView();
                log.info("Initial background view generation complete.");
            } catch (Exception e) {
                log.error("Failed to perform initial news view build", e);
            }
        });
        log.info("Startup runner submitted background initialization tasks.");
    }
}
