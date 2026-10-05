package com.doublespy.resilientreverseproxy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FailoverTest {

    @Test
    void skipsUnhealthyNodeAndRecoversWhenItIsMarkedHealthy() {
        UpstreamRegistry registry = registry("http://one", "http://two");
        registry.markUnhealthy("http://one");

        assertEquals(List.of("http://two"), registry.getEligibleNodes());

        registry.markHealthy("http://one");
        assertTrue(registry.getEligibleNodes().contains("http://one"));
    }

    @Test
    void skipsOpenCircuitNode() {
        UpstreamRegistry registry = registry("http://one", "http://two");
        registry.getCircuitBreaker("http://one").recordFailure();

        assertEquals(List.of("http://two"), registry.getEligibleNodes());
    }

    @Test
    void retriesOnceOnConnectFailureUsingAnotherBackend() throws Exception {
        UpstreamRegistry registry = registry("http://one", "http://two");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> success = mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(200);
        when(success.headers()).thenReturn(HttpHeadersAdapter.empty());
        when(success.body()).thenReturn("ok".getBytes());
        when(httpClient.<byte[]>send(any(), any())).thenThrow(new IOException("connect failed"))
                .thenReturn(success);

        ProxyController controller = new ProxyController(
                registry, mock(MetricsService.class), httpClient);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/");

        assertEquals(200, controller.proxyGet(request).getStatusCode().value());
    }

    @Test
    void returns503WithRetryAfterWhenAllNodesAreDown() {
        UpstreamRegistry registry = registry("http://one", "http://two");
        registry.markUnhealthy("http://one");
        registry.markUnhealthy("http://two");

        ProxyController controller = new ProxyController(
                registry, mock(MetricsService.class), mock(HttpClient.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/");

        var response = controller.proxyGet(request);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("10", response.getHeaders().getFirst("Retry-After"));
    }

    private static UpstreamRegistry registry(String... backends) {
        ProxyConfig config = new ProxyConfig();
        config.setBackends(List.of(backends));
        config.setFailureThreshold(1);
        config.setOpenDelay(Duration.ofMinutes(1));
        return new UpstreamRegistry(config);
    }

    private static final class HttpHeadersAdapter {
        private static java.net.http.HttpHeaders empty() {
            return java.net.http.HttpHeaders.of(
                    java.util.Map.of(), (name, value) -> true);
        }
    }
}
