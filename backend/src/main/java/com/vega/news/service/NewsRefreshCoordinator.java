package com.vega.news.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NewsRefreshCoordinator {
    private final AtomicBoolean globalRefreshRunning = new AtomicBoolean(false);

    public boolean tryLockRefresh() {
        return globalRefreshRunning.compareAndSet(false, true);
    }

    public void unlockRefresh() {
        globalRefreshRunning.set(false);
    }
}
