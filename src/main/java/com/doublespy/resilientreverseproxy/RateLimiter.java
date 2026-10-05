package com.doublespy.resilientreverseproxy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final long IDLE_BUCKET_EXPIRY_NANOS = NANOS_PER_MINUTE;

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();
    private final int rateLimitPerMinute;
    private final LongSupplier nanoTime;

    @Autowired
    public RateLimiter(ProxyConfig proxyConfig) {
        this(proxyConfig, System::nanoTime);
    }

    RateLimiter(ProxyConfig proxyConfig, LongSupplier nanoTime) {
        if (proxyConfig.getRateLimitPerMinute() <= 0) {
            throw new IllegalArgumentException("Rate limit must be positive");
        }
        this.rateLimitPerMinute = proxyConfig.getRateLimitPerMinute();
        this.nanoTime = nanoTime;
    }

    public boolean allowRequest(String clientIp) {
        return buckets.computeIfAbsent(clientIp, ignored -> new BucketState(nanoTime.getAsLong()))
                .tryConsume(nanoTime.getAsLong());
    }

    public long retryAfterSeconds(String clientIp) {
        BucketState bucket = buckets.get(clientIp);
        if (bucket == null) {
            return 1;
        }
        return bucket.retryAfterSeconds(nanoTime.getAsLong());
    }

    @Scheduled(fixedRate = 60_000)
    public void evictIdleBuckets() {
        long now = nanoTime.getAsLong();
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccessNanos() >= IDLE_BUCKET_EXPIRY_NANOS);
    }

    int bucketCount() {
        return buckets.size();
    }

    private final class BucketState {
        private final ReentrantLock lock = new ReentrantLock();
        private final double capacity = rateLimitPerMinute;
        private final double tokensPerNano = capacity / NANOS_PER_MINUTE;
        private double tokens = capacity;
        private long lastRefillNanos;
        private long lastAccessNanos;

        private BucketState(long now) {
            lastRefillNanos = now;
            lastAccessNanos = now;
        }

        private boolean tryConsume(long now) {
            lock.lock();
            try {
                refill(now);
                lastAccessNanos = now;
                if (tokens < 1) {
                    return false;
                }
                tokens--;
                return true;
            } finally {
                lock.unlock();
            }
        }

        private long retryAfterSeconds(long now) {
            lock.lock();
            try {
                refill(now);
                if (tokens >= 1) {
                    return 1;
                }
                return Math.max(1, (long) Math.ceil((1 - tokens) / tokensPerNano / 1_000_000_000d));
            } finally {
                lock.unlock();
            }
        }

        private long lastAccessNanos() {
            lock.lock();
            try {
                return lastAccessNanos;
            } finally {
                lock.unlock();
            }
        }

        private void refill(long now) {
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
                lastRefillNanos = now;
            }
        }
    }
}
