package com.vega.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Service
@Slf4j
public class InstrumentService {

    private final String instrumentPath;
    private final ObjectMapper objectMapper;
    private final Map<String, InstrumentInfo> isinMap = new HashMap<>();
    private final Map<String, InstrumentInfo> instrumentKeyMap = new HashMap<>();
    private final Map<String, InstrumentInfo> fnoEquities = new HashMap<>();

    public InstrumentService(@Value("${upstox.instrument-path}") String instrumentPath, ObjectMapper objectMapper) {
        this.instrumentPath = instrumentPath;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        log.info("Loading instruments from {}", instrumentPath);
        File file = new File(instrumentPath);
        if (!file.exists()) {
            log.warn("Instrument file not found at {}", instrumentPath);
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(file);
            if (root.isArray()) {
                Set<String> fnoUnderlyingKeys = new HashSet<>();
                for (JsonNode node : root) {
                    String isin = node.path("isin").asText(null);
                    String instrumentKey = node.path("instrument_key").asText(null);
                    String segment = node.path("segment").asText("");
                    String underlyingType = node.path("underlying_type").asText("");
                    String underlyingKey = node.path("underlying_key").asText("");

                    if ("NSE_FO".equals(segment) && "EQUITY".equals(underlyingType) && !underlyingKey.isEmpty()) {
                        fnoUnderlyingKeys.add(underlyingKey);
                    }

                    InstrumentInfo info = new InstrumentInfo();
                    info.setIsin(isin);
                    info.setSymbol(node.path("trading_symbol").asText(node.path("asset_symbol").asText()));
                    info.setName(node.path("name").asText(null));
                    info.setExchange(node.path("exchange").asText());
                    info.setInstrumentKey(instrumentKey);
                    info.setSegment(segment);

                    if (isin != null && !isin.isEmpty()) {
                        isinMap.put(isin, info);
                    }
                    if (instrumentKey != null && !instrumentKey.isEmpty()) {
                        instrumentKeyMap.put(instrumentKey, info);
                    }
                }

                // Identify F&O linked equities
                for (String key : fnoUnderlyingKeys) {
                    InstrumentInfo eqInfo = instrumentKeyMap.get(key);
                    if (eqInfo != null && "NSE_EQ".equals(eqInfo.getSegment()) && eqInfo.getIsin() != null) {
                        fnoEquities.put(eqInfo.getIsin(), eqInfo);
                    }
                }
            }
            log.info("Loaded {} instruments (ISINs: {}, Keys: {}) from {}", 
                    isinMap.size(), isinMap.size(), instrumentKeyMap.size(), instrumentPath);
            log.info("Identified {} F&O linked equities.", fnoEquities.size());
        } catch (IOException e) {
            log.error("Failed to load instruments: {}", e.getMessage());
        }
    }

    public Map<String, InstrumentInfo> getFnoEquities() {
        return Collections.unmodifiableMap(fnoEquities);
    }

    public InstrumentInfo getInstrument(String isin) {
        return isinMap.get(isin);
    }

    public String getInstrumentKeyByIsin(String isin) {
        InstrumentInfo info = isinMap.get(isin);
        return info != null ? info.getInstrumentKey() : null;
    }

    public InstrumentInfo getByInstrumentKey(String instrumentKey) {
        return instrumentKeyMap.get(instrumentKey);
    }

    public String getCompetitorInstrumentKey(String isin) {
        InstrumentInfo info = isinMap.get(isin);
        if (info == null) {
            return null;
        }
        if (info.getInstrumentKey() != null && !info.getInstrumentKey().isBlank()) {
            return info.getInstrumentKey();
        }
        return info.getExchange() + "|" + isin;
    }

    public boolean isLoaded() {
        return !isinMap.isEmpty();
    }

    @Data
    public static class InstrumentInfo {
        private String isin;
        private String symbol;
        private String name;
        private String exchange;
        private String instrumentKey;
        private String segment;
    }
}
