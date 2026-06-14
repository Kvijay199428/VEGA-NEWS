package com.vega.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        Path holdingsPath = Paths.get("/root/news/storage/user/holdings/holdings.jsonl");
        if (!Files.exists(holdingsPath)) {
            return isins;
        }

        try {
            String content = Files.readString(holdingsPath);
            JsonNode node = objectMapper.readTree(content);
            JsonNode dataArray = node.path("data");
            if (dataArray.isArray()) {
                for (JsonNode holding : dataArray) {
                    if (holding.has("isin")) {
                        isins.add(holding.path("isin").asText());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to read/parse holdings", e);
        }
        return isins;
    }

    public Set<String> readPositionsIsins() {
        Set<String> isins = new HashSet<>();
        Path positionsPath = Paths.get("/root/news/storage/user/positions/positions.jsonl");
        if (!Files.exists(positionsPath)) {
            return isins;
        }

        try {
            String content = Files.readString(positionsPath);
            JsonNode node = objectMapper.readTree(content);
            JsonNode dataArray = node.path("data");
            if (dataArray.isArray()) {
                for (JsonNode position : dataArray) {
                    int quantity = position.path("quantity").asInt(0);
                    String instrumentToken = position.path("instrument_token").asText("");
                    
                    if (quantity > 0 && instrumentToken.contains("_EQ|")) {
                        String[] parts = instrumentToken.split("\\|");
                        if (parts.length == 2) {
                            isins.add(parts[1]);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to read/parse positions", e);
        }
        return isins;
    }
}
