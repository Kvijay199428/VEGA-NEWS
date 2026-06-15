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

        if (instrumentLoaded && storageReachable) {
            return Health.up()
                    .withDetail("instrumentsLoaded", true)
                    .withDetail("storageReachable", true)
                    .build();
        }

        return Health.down()
                .withDetail("instrumentsLoaded", instrumentLoaded)
                .withDetail("storageReachable", storageReachable)
                .build();
    }
}
