package com.vega.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioReaderService {

    private final ObjectMapper objectMapper;

    public Set<String> readHoldingsIsins() {
        Set<String> isins = new HashSet<>();
        Path holdingsPath = Paths.get("storage/user/holdings/holdings.jsonl");
        if (!Files.exists(holdingsPath)) {
            return isins;
        }

        try (BufferedReader reader = Files.newBufferedReader(holdingsPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode node = objectMapper.readTree(line);
                    if (node.has("isin")) {
                        isins.add(node.get("isin").asText());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse holding line", e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read holdings", e);
        }
        return isins;
    }

    public Set<String> readPositionsIsins() {
        Set<String> isins = new HashSet<>();
        Path positionsPath = Paths.get("storage/user/positions/positions.jsonl");
        if (!Files.exists(positionsPath)) {
            return isins;
        }

        try (BufferedReader reader = Files.newBufferedReader(positionsPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode node = objectMapper.readTree(line);
                    int quantity = node.path("quantity").asInt(0);
                    String instrumentToken = node.path("instrument_token").asText("");
                    
                    if (quantity > 0 && instrumentToken.contains("_EQ|")) {
                        if (node.has("isin")) {
                            isins.add(node.get("isin").asText());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse position line", e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read positions", e);
        }
        return isins;
    }
}
