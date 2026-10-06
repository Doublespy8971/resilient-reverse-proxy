package com.doublespy.resilientreverseproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProxyIntegrationTest {

    private static final StubBackend FIRST = new StubBackend("first");
    private static final StubBackend SECOND = new StubBackend("second");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    static {
        try {
            FIRST.start();
            SECOND.start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @LocalServerPort
    private int proxyPort;

    @Autowired
    private UpstreamRegistry upstreamRegistry;

    @BeforeAll
    static void startBackends() throws IOException {
        FIRST.start();
        SECOND.start();
    }

    @BeforeEach
    void ensureBackendsAreRunning() throws IOException {
        FIRST.start();
        SECOND.start();
    }

    @AfterAll
    static void stopBackends() {
        FIRST.stop();
        SECOND.stop();
    }

    @DynamicPropertySource
    static void backendProperties(DynamicPropertyRegistry registry) {
        registry.add("proxy.backends", ProxyIntegrationTest::backendUrls);
        registry.add("proxy.rate-limit-per-minute", () -> 1000);
        registry.add("proxy.open-delay", () -> "1m");
        registry.add("management.server.port", () -> 0);
    }

    private static List<String> backendUrls() {
        try {
            FIRST.start();
            SECOND.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start stub backends", exception);
        }
        return List.of(FIRST.url(), SECOND.url());
    }

    @Test
    void distributesRequestsRoundRobinAcrossBackends() throws Exception {
        FIRST.reset(200, "first");
        SECOND.reset(200, "second");

        assertEquals("first", get("/round-robin"));
        assertEquals("second", get("/round-robin"));
        assertEquals("first", get("/round-robin"));
        assertEquals("second", get("/round-robin"));
    }

    @Test
    void continuesServingWhenOneBackendStopsMidTest() throws Exception {
        FIRST.reset(200, "first");
        SECOND.reset(200, "second");

        assertEquals(200, request("/before-stop").statusCode());
        FIRST.stop();

        for (int request = 0; request < 4; request++) {
            assertEquals(200, request("/after-stop").statusCode());
        }
    }

    @Test
    void returns503WhenAllBackendsAreDown() throws Exception {
        FIRST.stop();
        SECOND.stop();

        assertEquals(503, request("/all-down").statusCode());

        FIRST.start();
        SECOND.start();
    }

    @Test
    void opensCircuitAfterThreeBackend500Responses() throws Exception {
        FIRST.reset(500, "failure");
        SECOND.reset(500, "failure");
        upstreamRegistry.markUnhealthy(SECOND.url());

        for (int request = 0; request < 3; request++) {
            assertEquals(500, request("/failure").statusCode());
        }

        assertEquals(
                CircuitBreaker.State.OPEN,
                upstreamRegistry.getCircuitBreaker(FIRST.url()).getState());
    }

    @Test
    void keepsCircuitClosedFor404Responses() throws Exception {
        FIRST.reset(404, "not-found");
        SECOND.reset(404, "not-found");

        assertEquals(404, request("/missing").statusCode());

        assertEquals(
                CircuitBreaker.State.CLOSED,
                upstreamRegistry.getCircuitBreaker(FIRST.url()).getState());
        assertEquals(
                CircuitBreaker.State.CLOSED,
                upstreamRegistry.getCircuitBreaker(SECOND.url()).getState());
    }

    private String get(String path) throws Exception {
        return request(path).body();
    }

    private HttpResponse<String> request(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + proxyPort + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class StubBackend {
        private final String name;
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicInteger status = new AtomicInteger();
        private HttpServer server;

        private StubBackend(String name) {
            this.name = name;
        }

        private void start() throws IOException {
            if (server != null) {
                return;
            }
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private void stop() {
            if (server != null) {
                server.stop(0);
                server = null;
            }
        }

        private String url() {
            assertTrue(server != null, name + " backend is not running");
            return "http://localhost:" + server.getAddress().getPort();
        }

        private void reset(int responseStatus, String responseBody) throws IOException {
            start();
            status.set(responseStatus);
            body.set(responseBody);
        }

        private void handle(HttpExchange exchange) throws IOException {
            int responseStatus = exchange.getRequestURI().getPath().equals("/health")
                    ? 200
                    : status.get();
            byte[] response = exchange.getRequestURI().getPath().equals("/health")
                    ? "ok".getBytes()
                    : body.get().getBytes();
            exchange.sendResponseHeaders(responseStatus, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        }
    }
}
