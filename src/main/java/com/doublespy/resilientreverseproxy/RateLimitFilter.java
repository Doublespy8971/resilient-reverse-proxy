package com.doublespy.resilientreverseproxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final MetricsService metricsService;
    private final ProxyConfig proxyConfig;
    private final Set<String> trustedProxies;

    public RateLimitFilter(
            RateLimiter rateLimiter, MetricsService metricsService, ProxyConfig proxyConfig) {
        this.rateLimiter = rateLimiter;
        this.metricsService = metricsService;
        this.proxyConfig = proxyConfig;
        this.trustedProxies = proxyConfig.getTrustedProxies().stream()
                .map(this::normalizeIp)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        metricsService.recordRequest();
        String clientIp = resolveClientIp(request);
        if (!rateLimiter.allowRequest(clientIp)) {
            metricsService.recordRateLimitedRequest();
            long retryAfter = rateLimiter.retryAfterSeconds(clientIp);
            log.info("Rate-limited request from client IP {}", clientIp);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.sendError(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "Rate limit exceeded");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String directPeer = request.getRemoteAddr();
        if (!proxyConfig.isTrustForwardedHeaders()
                || !isTrustedProxy(directPeer)) {
            return directPeer;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return directPeer;
        }
        String[] addresses = forwardedFor.split(",");
        for (int index = addresses.length - 1; index >= 0; index--) {
            String candidate = normalizeIp(addresses[index].trim());
            if (candidate != null && !isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return directPeer;
    }

    private boolean isTrustedProxy(String address) {
        String normalizedAddress = normalizeIp(address);
        return normalizedAddress != null
                && trustedProxies.contains(normalizedAddress);
    }

    private String normalizeIp(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }

        String candidate = address.trim();
        if (candidate.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) {
            for (String octet : candidate.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        } else if (!candidate.matches("[0-9a-fA-F:.]+")
                || candidate.chars().filter(character -> character == ':').count() < 2) {
            return null;
        }

        try {
            return InetAddress.getByName(candidate).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
