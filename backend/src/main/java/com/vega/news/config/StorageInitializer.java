package com.vega.news.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageInitializer {

    private final NewsProperties properties;

    @PostConstruct
    public void verifyStorage() {
        log.info("Verifying storage directories...");
        
        File storageRoot = new File(properties.getStorage().getRoot());
        ensureDirectory(storageRoot, "Storage Root");

        ensureDirectory(new File(storageRoot, "instruments"), "Instruments Archive");
        ensureDirectory(new File(storageRoot, "metadata"), "Metadata");
        ensureDirectory(new File(storageRoot, "state"), "Collector State");

        // Note: holdings.jsonl and positions.jsonl are created by the collector,
        // so we don't necessarily want to create them as directories here.
        // But we should log if they are missing.
        
        checkFile(new File(properties.getStorage().getHoldingsView()), "Holdings View");
        checkFile(new File(properties.getStorage().getPositionsView()), "Positions View");
    }

    private void ensureDirectory(File dir, String name) {
        if (!dir.exists()) {
            log.warn("{} directory missing. Attempting to create: {}", name, dir.getAbsolutePath());
            if (dir.mkdirs()) {
                log.info("Successfully created {} directory.", name);
            } else {
                log.error("Failed to create {} directory!", name);
            }
        } else if (!dir.isDirectory()) {
            log.error("{} path exists but is NOT a directory: {}", name, dir.getAbsolutePath());
        } else {
            log.info("{} directory verified: {}", name, dir.getAbsolutePath());
        }
    }

    private void checkFile(File file, String name) {
        if (!file.exists()) {
            log.warn("{} file missing: {}. This will be created by the collector.", name, file.getAbsolutePath());
        } else {
            log.info("{} file verified: {}", name, file.getAbsolutePath());
        }
    }
}
