package com.doublespy.resilientreverseproxy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private static final long REFILL_INTERVAL_NANOS = 60_000_000_000L;

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();
    private final int rateLimitPerMinute;

    public RateLimiter(ProxyConfig proxyConfig) {
        rateLimitPerMinute = proxyConfig.getRateLimitPerMinute();
    }

    public boolean allowRequest(String clientIp) {
        BucketState bucket = buckets.computeIfAbsent(
                clientIp, ignored -> new BucketState(rateLimitPerMinute));
        return bucket.tryConsume();
    }

    private static final class BucketState {

        private final ReentrantLock lock = new ReentrantLock();
        private final double bucketCapacity;
        private final double tokensPerNano;
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        private BucketState(int rateLimitPerMinute) {
            bucketCapacity = rateLimitPerMinute;
            tokensPerNano = bucketCapacity / REFILL_INTERVAL_NANOS;
            tokens = bucketCapacity;
        }

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

            tokens = Math.min(bucketCapacity, tokens + elapsedNanos * tokensPerNano);
            lastRefillNanos = now;
        }
    }
}
