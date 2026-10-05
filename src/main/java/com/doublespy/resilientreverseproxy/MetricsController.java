package com.doublespy.resilientreverseproxy;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public Map<String, Long> metrics() {
        return Map.of(
                "total_requests", metricsService.getTotalRequests(),
                "rate_limited_requests", metricsService.getRateLimitedRequests(),
                "circuit_breaker_rejections", metricsService.getCircuitBreakerRejections());
    }
}
