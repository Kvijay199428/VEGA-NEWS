package com.vega.news.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NewsRefreshCoordinator {
    private final AtomicBoolean holdingsRunning = new AtomicBoolean(false);
    private final AtomicBoolean positionsRunning = new AtomicBoolean(false);

    public boolean tryLockHoldings() {
        return holdingsRunning.compareAndSet(false, true);
    }

    public void unlockHoldings() {
        holdingsRunning.set(false);
    }

    public boolean tryLockPositions() {
        return positionsRunning.compareAndSet(false, true);
    }

    public void unlockPositions() {
        positionsRunning.set(false);
    }
}
