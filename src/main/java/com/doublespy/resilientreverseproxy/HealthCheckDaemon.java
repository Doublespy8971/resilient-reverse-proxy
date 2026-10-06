package com.doublespy.resilientreverseproxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HealthCheckDaemon {

    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(2);

    private final UpstreamRegistry upstreamRegistry;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HEALTH_CHECK_TIMEOUT)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
    private final ExecutorService healthCheckExecutor = Executors.newFixedThreadPool(4);

    public HealthCheckDaemon(UpstreamRegistry upstreamRegistry) {
        this.upstreamRegistry = upstreamRegistry;
    }

    @Scheduled(fixedRate = 5000)
    public void checkUpstreams() {
        upstreamRegistry.getNodes().forEach(node ->
                healthCheckExecutor.submit(() -> checkNode(node)));
    }

    private void checkNode(String node) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(node + "/health"))
                .timeout(HEALTH_CHECK_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 500 && response.statusCode() < 600) {
                upstreamRegistry.markUnhealthy(node);
                log.warn("Health check failed for backend {} with status {}", node, response.statusCode());
            } else {
                upstreamRegistry.markHealthy(node);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            upstreamRegistry.markUnhealthy(node);
            log.warn("Health check interrupted for backend {}", node, exception);
        } catch (IOException exception) {
            upstreamRegistry.markUnhealthy(node);
            log.warn("Health check failed for backend {}", node, exception);
        }
    }

    @PreDestroy
    void shutdown() {
        healthCheckExecutor.shutdownNow();
    }
}
