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

        File instrumentsDir = new File(storageRoot, "instruments");
        boolean instrumentsExists = instrumentsDir.exists() && instrumentsDir.isDirectory();

        boolean allHealthy = instrumentLoaded && storageReachable && holdingsExists && positionsExists && metadataExists && instrumentsExists;

        Health.Builder builder = allHealthy ? Health.up() : Health.down();
        return builder
                .withDetail("instrumentsLoaded", instrumentLoaded)
                .withDetail("storageReachable", storageReachable)
                .withDetail("holdingsExists", holdingsExists)
                .withDetail("positionsExists", positionsExists)
                .withDetail("metadataExists", metadataExists)
                .withDetail("instrumentsDirExists", instrumentsExists)
                .build();
    }
}
