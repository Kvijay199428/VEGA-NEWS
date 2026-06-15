package com.vega.news.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PortfolioNewsBuilderService {

    // Views are now built entirely by the collector process.
    // This service is retained as per architectural directives,
    // potentially for future real-time view generation.
}
