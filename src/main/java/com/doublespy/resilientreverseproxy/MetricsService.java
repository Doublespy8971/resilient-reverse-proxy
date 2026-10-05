package com.doublespy.resilientreverseproxy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong rateLimitedRequests = new AtomicLong();
    private final AtomicLong circuitBreakerRejections = new AtomicLong();
    private final Counter rateLimitedRequestsMeter;
    private final Counter circuitBreakerRejectionsMeter;
    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry, UpstreamRegistry upstreamRegistry) {
        this.meterRegistry = meterRegistry;
        rateLimitedRequestsMeter = Counter.builder("proxy.rate.limited.requests")
                .description("Requests rejected by the rate limiter")
                .register(meterRegistry);
        circuitBreakerRejectionsMeter = Counter.builder("proxy.circuit.rejected.requests")
                .description("Requests rejected because no circuit-eligible backend was available")
                .register(meterRegistry);

        upstreamRegistry.getNodes().forEach(backend -> {
            meterRegistry.gauge(
                    "proxy.circuit.state",
                    Tags.of("backend", backend),
                    upstreamRegistry.getCircuitBreaker(backend),
                    circuitBreaker -> switch (circuitBreaker.getState()) {
                        case CLOSED -> 0.0;
                        case HALF_OPEN -> 0.5;
                        case OPEN -> 1.0;
                    });
            meterRegistry.gauge(
                    "proxy.backend.health",
                    Tags.of("backend", backend),
                    upstreamRegistry,
                    registry -> registry.isHealthy(backend) ? 1.0 : 0.0);
        });
    }

    public void recordRequest() {
        totalRequests.incrementAndGet();
    }

    public void recordRateLimitedRequest() {
        rateLimitedRequests.incrementAndGet();
        rateLimitedRequestsMeter.increment();
    }

    public void recordCircuitBreakerRejection() {
        circuitBreakerRejections.incrementAndGet();
        circuitBreakerRejectionsMeter.increment();
    }

    public Timer.Sample startRequestTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordBackendRequest(
            String backend, int statusCode, Timer.Sample timerSample) {
        String statusClass = (statusCode / 100) + "xx";
        Counter.builder("proxy.requests")
                .description("Requests handled by each backend and HTTP status class")
                .tags("backend", backend, "status_class", statusClass)
                .register(meterRegistry)
                .increment();
        timerSample.stop(Timer.builder("proxy.request.duration")
                .description("Proxy request duration")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .tags("backend", backend)
                .register(meterRegistry));
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
