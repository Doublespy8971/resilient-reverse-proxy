package com.doublespy.resilientreverseproxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.List;

import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@RestController
@Slf4j
public class ProxyController {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final UpstreamRegistry upstreamRegistry;
    private final MetricsService metricsService;
    private final HttpClient httpClient;

    @Autowired
    public ProxyController(UpstreamRegistry upstreamRegistry, MetricsService metricsService) {
        this(upstreamRegistry, metricsService, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    ProxyController(
            UpstreamRegistry upstreamRegistry,
            MetricsService metricsService,
            HttpClient httpClient) {
        this.upstreamRegistry = upstreamRegistry;
        this.metricsService = metricsService;
        this.httpClient = httpClient;
    }

    @GetMapping("/**")
    public ResponseEntity<byte[]> proxyGet(HttpServletRequest incomingRequest) {
        List<String> eligibleNodes = upstreamRegistry.getEligibleNodes();
        for (int attempt = 0; attempt < Math.min(2, eligibleNodes.size()); attempt++) {
            String upstreamUrl = eligibleNodes.get(attempt);
            CircuitBreaker circuitBreaker = upstreamRegistry.getCircuitBreaker(upstreamUrl);
            if (!circuitBreaker.allowRequest()) {
                continue;
            }

            Timer.Sample timerSample = metricsService.startRequestTimer();
            try {
            HttpRequest.Builder upstreamRequest = HttpRequest.newBuilder()
                    .uri(buildUpstreamUri(incomingRequest, upstreamUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            copyRequestHeaders(incomingRequest, upstreamRequest);

            HttpResponse<byte[]> upstreamResponse = httpClient.send(
                    upstreamRequest.build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            if (upstreamResponse.statusCode() < 500) {
                circuitBreaker.recordSuccess();
            } else {
                circuitBreaker.recordFailure();
            }

            HttpHeaders responseHeaders = new HttpHeaders();
            upstreamResponse.headers().map().forEach(responseHeaders::put);
            Set<String> responseHeadersToRemove = new HashSet<>();
            responseHeaders.forEach((name, values) -> {
                if (isHopByHopHeader(name)) {
                    responseHeadersToRemove.add(name);
                }
            });
            responseHeadersToRemove.forEach(responseHeaders::remove);

            if (upstreamResponse.statusCode() >= 500 && attempt == 0
                    && eligibleNodes.size() > 1) {
                metricsService.recordBackendRequest(
                        upstreamUrl, upstreamResponse.statusCode(), timerSample);
                continue;
            }

            metricsService.recordBackendRequest(
                    upstreamUrl, upstreamResponse.statusCode(), timerSample);
            return ResponseEntity.status(upstreamResponse.statusCode())
                    .headers(responseHeaders)
                    .body(upstreamResponse.body());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                circuitBreaker.recordFailure();
                metricsService.recordBackendRequest(upstreamUrl, 502, timerSample);
                throw new ResponseStatusException(BAD_GATEWAY, "Upstream request was interrupted", exception);
            } catch (IOException exception) {
                circuitBreaker.recordFailure();
                metricsService.recordBackendRequest(upstreamUrl, 502, timerSample);
                if (attempt == 0 && eligibleNodes.size() > 1) {
                    continue;
                }
                log.warn("Unable to reach any upstream backend", exception);
                return ResponseEntity.status(SERVICE_UNAVAILABLE)
                        .header("Retry-After", "10")
                        .build();
            } catch (RuntimeException exception) {
                circuitBreaker.recordFailure();
                metricsService.recordBackendRequest(upstreamUrl, 500, timerSample);
                throw exception;
            }
        }

        metricsService.recordCircuitBreakerRejection();
        log.warn("No eligible upstream backend available");
        return ResponseEntity.status(SERVICE_UNAVAILABLE)
                .header("Retry-After", "10")
                .build();
    }

    private URI buildUpstreamUri(HttpServletRequest incomingRequest, String upstreamUrl) {
        String query = incomingRequest.getQueryString();
        String path = incomingRequest.getRequestURI();
        return URI.create(upstreamUrl + path + (query == null ? "" : "?" + query));
    }

    private void copyRequestHeaders(
            HttpServletRequest incomingRequest,
            HttpRequest.Builder upstreamRequest) {
        Enumeration<String> headerNames = incomingRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (isHopByHopHeader(headerName)) {
                continue;
            }

            Enumeration<String> headerValues = incomingRequest.getHeaders(headerName);
            while (headerValues.hasMoreElements()) {
                upstreamRequest.header(headerName, headerValues.nextElement());
            }
        }
        addForwardedForHeader(incomingRequest, upstreamRequest);
    }

    private void addForwardedForHeader(
            HttpServletRequest incomingRequest,
            HttpRequest.Builder upstreamRequest) {
        String clientIp = incomingRequest.getRemoteAddr();
        String forwardedFor = incomingRequest.getHeader("X-Forwarded-For");
        upstreamRequest.header(
                "X-Forwarded-For",
                forwardedFor == null || forwardedFor.isBlank()
                        ? clientIp
                        : forwardedFor + ", " + clientIp);
    }

    private boolean isHopByHopHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }
}
