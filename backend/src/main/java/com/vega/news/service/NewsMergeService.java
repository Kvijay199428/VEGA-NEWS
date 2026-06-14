package com.vega.news.service;

import com.vega.news.model.NewsArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class NewsMergeService {

    public List<NewsArticle> mergeArchives(List<List<NewsArticle>> archives) {
        List<NewsArticle> merged = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();

        for (List<NewsArticle> archive : archives) {
            for (NewsArticle article : archive) {
                if (seenHashes.add(article.getSourceHash())) {
                    merged.add(article);
                }
            }
        }

        merged.sort((a, b) -> Long.compare(b.getPublishedTime(), a.getPublishedTime()));
        return merged;
    }
}
