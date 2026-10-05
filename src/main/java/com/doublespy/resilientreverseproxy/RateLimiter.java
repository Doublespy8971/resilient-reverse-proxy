package com.doublespy.resilientreverseproxy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private static final double BUCKET_CAPACITY = 100;
    private static final long REFILL_INTERVAL_NANOS = 60_000_000_000L;
    private static final double TOKENS_PER_NANO = BUCKET_CAPACITY / REFILL_INTERVAL_NANOS;

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    public boolean allowRequest(String clientIp) {
        BucketState bucket = buckets.computeIfAbsent(clientIp, ignored -> new BucketState());
        return bucket.tryConsume();
    }

    private static final class BucketState {

        private final ReentrantLock lock = new ReentrantLock();
        private double tokens = BUCKET_CAPACITY;
        private long lastRefillNanos = System.nanoTime();

        private boolean tryConsume() {
            lock.lock();
            try {
                refill();
                if (tokens < 1) {
                    return false;
                }

                tokens--;
                return true;
            } finally {
                lock.unlock();
            }
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }

            tokens = Math.min(BUCKET_CAPACITY, tokens + elapsedNanos * TOKENS_PER_NANO);
            lastRefillNanos = now;
        }
    }
}
