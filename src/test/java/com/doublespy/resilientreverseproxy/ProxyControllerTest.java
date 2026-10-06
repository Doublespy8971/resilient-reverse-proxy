package com.doublespy.resilientreverseproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ProxyControllerTest {

    @Test
    void forwardsRequestWithConnectionHeaderWithoutFailing() throws IOException {
        AtomicReference<String> forwardedFor = new AtomicReference<>();
        AtomicReference<String> connection = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            forwardedFor.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            connection.set(exchange.getRequestHeaders().getFirst("Connection"));
            byte[] response = "ok".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (var outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();

        try {
            String backendUrl = "http://localhost:" + server.getAddress().getPort();
            UpstreamRegistry registry = mock(UpstreamRegistry.class);
            MetricsService metricsService = mock(MetricsService.class);
            CircuitBreaker circuitBreaker = new CircuitBreaker(backendUrl);
            when(registry.getEligibleNodes()).thenReturn(List.of(backendUrl));
            when(registry.getCircuitBreaker(backendUrl)).thenReturn(circuitBreaker);

            ProxyController controller = new ProxyController(
                    registry, metricsService, HttpClient.newHttpClient());
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/health");
            request.setRemoteAddr("192.0.2.10");
            request.addHeader("Connection", "keep-alive");

            var response = controller.proxyGet(request);

            assertEquals(200, response.getStatusCode().value());
            assertEquals("192.0.2.10", forwardedFor.get());
            assertNotEquals("keep-alive", connection.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsBadRequestBeforeAllowingCircuitForInvalidUpstreamUri() {
        String invalidBackendUrl = "http://[invalid";
        UpstreamRegistry registry = mock(UpstreamRegistry.class);
        MetricsService metricsService = mock(MetricsService.class);
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(registry.getEligibleNodes()).thenReturn(List.of(invalidBackendUrl));
        when(registry.getCircuitBreaker(invalidBackendUrl)).thenReturn(circuitBreaker);

        ProxyController controller = new ProxyController(
                registry, metricsService, HttpClient.newHttpClient());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/health");

        var response = controller.proxyGet(request);

        assertEquals(400, response.getStatusCode().value());
        verify(circuitBreaker, never()).allowRequest();
        verify(circuitBreaker, never()).recordFailure();
        verify(metricsService, never()).recordCircuitBreakerRejection();
    }
}
