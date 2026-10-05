package com.doublespy.resilientreverseproxy;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void rejectsRequestsAfterConfiguredBucketLimit() {
        ProxyConfig proxyConfig = new ProxyConfig();
        proxyConfig.setRateLimitPerMinute(100);
        RateLimiter rateLimiter = new RateLimiter(proxyConfig);

        for (int request = 0; request < 100; request++) {
            assertTrue(rateLimiter.allowRequest("192.0.2.1"));
        }

        assertFalse(rateLimiter.allowRequest("192.0.2.1"));
    }

    @Test
    void refillsTokensUsingInjectedClock() {
        AtomicLong now = new AtomicLong();
        ProxyConfig config = config(2);
        RateLimiter rateLimiter = new RateLimiter(config, now::get);

        assertTrue(rateLimiter.allowRequest("one"));
        assertTrue(rateLimiter.allowRequest("one"));
        assertFalse(rateLimiter.allowRequest("one"));

        now.set(30_000_000_000L);
        assertTrue(rateLimiter.allowRequest("one"));
    }

    @Test
    void keepsBucketsIsolatedPerIp() {
        RateLimiter rateLimiter = new RateLimiter(config(1), System::nanoTime);

        assertTrue(rateLimiter.allowRequest("one"));
        assertFalse(rateLimiter.allowRequest("one"));
        assertTrue(rateLimiter.allowRequest("two"));
    }

    @Test
    void evictsBucketsIdleForOneMinute() {
        AtomicLong now = new AtomicLong();
        RateLimiter rateLimiter = new RateLimiter(config(1), now::get);

        assertTrue(rateLimiter.allowRequest("one"));
        assertEquals(1, rateLimiter.bucketCount());
        now.set(Duration.ofMinutes(1).toNanos());
        rateLimiter.evictIdleBuckets();

        assertEquals(0, rateLimiter.bucketCount());
    }

    private static ProxyConfig config(int limit) {
        ProxyConfig config = new ProxyConfig();
        config.setRateLimitPerMinute(limit);
        config.setBackends(List.of("http://backend"));
        return config;
    }
}
