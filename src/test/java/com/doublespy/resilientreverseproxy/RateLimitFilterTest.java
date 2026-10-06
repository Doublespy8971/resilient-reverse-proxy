package com.doublespy.resilientreverseproxy;

import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

    @Test
    void usesRightmostUntrustedAddressFromForwardedChain() throws Exception {
        ProxyConfig config = config(true, List.of("10.0.0.2", "10.0.0.3"));
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimiter(config), mock(MetricsService.class), config);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = request("10.0.0.2", "1.2.3.4, 198.51.100.10, 10.0.0.3");
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request, first, chain);
        MockHttpServletRequest sameClient = request("10.0.0.2", "1.2.3.4, 198.51.100.10, 10.0.0.3");
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(sameClient, second, chain);

        assertEquals(200, first.getStatus());
        assertEquals(429, second.getStatus());
    }

    @ParameterizedTest
    @MethodSource("invalidForwardedAddresses")
    void rejectsNonLiteralForwardedAddresses(String invalidAddress) throws Exception {
        ProxyConfig config = config(true, List.of("10.0.0.2"));
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimiter(config), mock(MetricsService.class), config);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest firstRequest = request("10.0.0.2", invalidAddress);
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(firstRequest, first, chain);
        MockHttpServletRequest secondRequest = request("10.0.0.2", invalidAddress);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(secondRequest, second, chain);

        assertEquals(200, first.getStatus());
        assertEquals(429, second.getStatus());
    }

    private static Stream<String> invalidForwardedAddresses() {
        return Stream.of("beef", "abc", "dead", "localhost");
    }

    @Test
    void normalizesEquivalentIpv6LoopbackAddresses() throws Exception {
        ProxyConfig config = config(true, List.of("::1"));
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimiter(config), mock(MetricsService.class), config);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest firstRequest = request(
                "0:0:0:0:0:0:0:1", "198.51.100.10");
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(firstRequest, first, chain);
        MockHttpServletRequest secondRequest = request("::1", "198.51.100.10");
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(secondRequest, second, chain);

        assertEquals(200, first.getStatus());
        assertEquals(429, second.getStatus());
    }

    @Test
    void rateLimitsActuatorPathsOnProxyPort() throws Exception {
        ProxyConfig config = config(true, List.of("10.0.0.2"));
        RateLimitFilter filter = new RateLimitFilter(
                new RateLimiter(config), mock(MetricsService.class), config);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest firstRequest = new MockHttpServletRequest();
        firstRequest.setRequestURI("/actuator/anything");
        firstRequest.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        secondRequest.setRequestURI("/actuator/anything");
        secondRequest.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(firstRequest, first, chain);
        filter.doFilter(secondRequest, second, chain);

        assertEquals(200, first.getStatus());
        assertEquals(429, second.getStatus());
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwardedFor);
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
