package com.vega.news.config;

import com.vega.news.service.InstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@RequiredArgsConstructor
public class VegaNewsHealthIndicator implements HealthIndicator {

    private final InstrumentService instrumentService;
    private final NewsProperties properties;

    @Override
    public Health health() {
        boolean instrumentLoaded = instrumentService.isLoaded();
        
        File storageRoot = new File(properties.getStorage().getRoot());
        boolean storageReachable = storageRoot.exists() && storageRoot.isDirectory() && storageRoot.canRead();

        File holdingsView = new File(properties.getStorage().getHoldingsView());
        boolean holdingsExists = holdingsView.exists() && holdingsView.isFile();

        File positionsView = new File(properties.getStorage().getPositionsView());
        boolean positionsExists = positionsView.exists() && positionsView.isFile();

        File metadataDir = new File(storageRoot, "metadata");
        boolean metadataExists = metadataDir.exists() && metadataDir.isDirectory();
        long metadataCount = metadataExists ? countFiles(metadataDir, ".json") : 0;

        File instrumentsDir = new File(storageRoot, "instruments");
        boolean instrumentsExists = instrumentsDir.exists() && instrumentsDir.isDirectory();
        long archiveCount = instrumentsExists ? countFiles(instrumentsDir, ".jsonl") : 0;

        int expectedFno = instrumentService.getFnoInstrumentCount();

        // Health is UP if core infrastructure is ready, even if collector hasn't run yet.
        boolean allHealthy = instrumentLoaded && storageReachable && metadataExists && instrumentsExists;

        Health.Builder builder = allHealthy ? Health.up() : Health.down();
        return builder
                .withDetail("instrumentsLoaded", instrumentLoaded)
                .withDetail("storageReachable", storageReachable)
                .withDetail("metadataDirExists", metadataExists)
                .withDetail("metadataCount", metadataCount)
                .withDetail("instrumentsDirExists", instrumentsExists)
                .withDetail("archiveCount", archiveCount)
                .withDetail("expectedFno", expectedFno)
                .withDetail("holdingsExists", holdingsExists)
                .withDetail("positionsExists", positionsExists)
                .build();
    }

    private long countFiles(File dir, String extension) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(extension));
        return files != null ? files.length : 0;
    }
}
