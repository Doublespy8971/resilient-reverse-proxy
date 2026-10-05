package com.doublespy.resilientreverseproxy;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong rateLimitedRequests = new AtomicLong();
    private final AtomicLong circuitBreakerRejections = new AtomicLong();

    public void recordRequest() {
        totalRequests.incrementAndGet();
    }

    public void recordRateLimitedRequest() {
        rateLimitedRequests.incrementAndGet();
    }

    public void recordCircuitBreakerRejection() {
        circuitBreakerRejections.incrementAndGet();
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    public long getRateLimitedRequests() {
        return rateLimitedRequests.get();
    }

    public long getCircuitBreakerRejections() {
        return circuitBreakerRejections.get();
    }
}
