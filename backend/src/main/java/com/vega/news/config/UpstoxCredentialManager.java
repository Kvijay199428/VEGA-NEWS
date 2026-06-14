package com.vega.news.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

@Component
@Slf4j
public class UpstoxCredentialManager {

    @Value("${upstox.auth.credential-file}")
    private String envPath;

    private String accessToken;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        loadCredentials();
    }

    public synchronized void loadCredentials() {
        try {
            File file = new File(envPath);
            if (!file.exists()) {
                log.error("Upstox credentials file not found at: {}", envPath);
                return;
            }

            JsonNode root = objectMapper.readTree(file);
            JsonNode accounts = root.path("accounts");
            
            // Try to find the first account with an accessToken
            if (accounts.isObject()) {
                accounts.fields().forEachRemaining(entry -> {
                    JsonNode account = entry.getValue();
                    if (account.has("accessToken")) {
                        this.accessToken = account.get("accessToken").asText();
                    }
                });
            }

            if (this.accessToken == null || this.accessToken.isEmpty()) {
                log.error("No access token found in Upstox credentials file.");
            } else {
                log.info("Upstox access token loaded successfully.");
            }
        } catch (IOException e) {
            log.error("Failed to load Upstox credentials: {}", e.getMessage());
        }
    }

    public String getAccessToken() {
        return accessToken;
    }
}
