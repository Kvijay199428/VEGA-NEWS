package com.vega.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "news")
public class NewsProperties {

    private final Refresh refresh = new Refresh();
    private final Retention retention = new Retention();
    private final Upstox upstox = new Upstox();
    private final Storage storage = new Storage();

    public Refresh getRefresh() { return refresh; }
    public Retention getRetention() { return retention; }
    public Upstox getUpstox() { return upstox; }
    public Storage getStorage() { return storage; }

    public static class Refresh {
        private String interval = "15m";
        public String getInterval() { return interval; }
        public void setInterval(String interval) { this.interval = interval; }
    }

    public static class Retention {
        private int days = 3650;
        public int getDays() { return days; }
        public void setDays(int days) { this.days = days; }
    }

    public static class Upstox {
        private int pageSize = 100;
        public int getPageSize() { return pageSize; }
        public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    }

    public static class Storage {
        private String root = "storage/news";
        private String holdingsView = "storage/news/holdings.jsonl";
        private String positionsView = "storage/news/positions.jsonl";

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public String getHoldingsView() { return holdingsView; }
        public void setHoldingsView(String holdingsView) { this.holdingsView = holdingsView; }
        public String getPositionsView() { return positionsView; }
        public void setPositionsView(String positionsView) { this.positionsView = positionsView; }
    }
}
