package com.doublespy.resilientreverseproxy;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final MetricsService metricsService;

    public RateLimitFilter(RateLimiter rateLimiter, MetricsService metricsService) {
        this.rateLimiter = rateLimiter;
        this.metricsService = metricsService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        metricsService.recordRequest();
        if (!rateLimiter.allowRequest(request.getRemoteAddr())) {
            metricsService.recordRateLimitedRequest();
            response.sendError(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "Rate limit exceeded");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
