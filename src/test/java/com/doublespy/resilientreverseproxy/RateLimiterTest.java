package com.doublespy.resilientreverseproxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
