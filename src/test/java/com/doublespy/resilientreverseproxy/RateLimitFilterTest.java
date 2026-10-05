package com.doublespy.resilientreverseproxy;

import java.util.List;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RateLimitFilterTest {

    @Test
    void ignoresForwardedAddressFromUntrustedPeer() throws Exception {
        ProxyConfig config = config(false, List.of("10.0.0.1"));
        RateLimiter limiter = new RateLimiter(config);
        RateLimitFilter filter = new RateLimitFilter(limiter, mock(MetricsService.class), config);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("10.0.0.2", "198.51.100.1");

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request, first, chain);
        MockHttpServletRequest secondRequest = request("10.0.0.2", "198.51.100.2");
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(secondRequest, second, chain);

        assertEquals(200, first.getStatus());
        assertEquals(429, second.getStatus());
    }

    @Test
    void honorsForwardedAddressFromTrustedPeer() throws Exception {
        ProxyConfig config = config(true, List.of("10.0.0.2"));
        RateLimiter limiter = new RateLimiter(config);
        RateLimitFilter filter = new RateLimitFilter(limiter, mock(MetricsService.class), config);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("10.0.0.2", "198.51.100.1");

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request, first, chain);
        MockHttpServletRequest secondRequest = request("10.0.0.2", "198.51.100.2");
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(secondRequest, second, chain);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(secondRequest, response, chain);

        assertEquals(200, first.getStatus());
        assertEquals(200, second.getStatus());
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
    }

    private static MockHttpServletRequest request(String peer, String forwardedIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwardedIp);
        return request;
    }

    private static ProxyConfig config(boolean trustForwarded, List<String> trustedProxies) {
        ProxyConfig config = new ProxyConfig();
        config.setRateLimitPerMinute(1);
        config.setTrustForwardedHeaders(trustForwarded);
        config.setTrustedProxies(trustedProxies);
        return config;
    }
}
