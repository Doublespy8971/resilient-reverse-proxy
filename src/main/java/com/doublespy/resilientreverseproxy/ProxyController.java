package com.doublespy.resilientreverseproxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@RestController
public class ProxyController {

    private final UpstreamRegistry upstreamRegistry;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ProxyController(UpstreamRegistry upstreamRegistry) {
        this.upstreamRegistry = upstreamRegistry;
    }

    @GetMapping("/**")
    public ResponseEntity<byte[]> proxyGet(HttpServletRequest incomingRequest) {
        HttpRequest.Builder upstreamRequest = HttpRequest.newBuilder()
                .uri(buildUpstreamUri(incomingRequest))
                .timeout(Duration.ofSeconds(30))
                .GET();

        copyRequestHeaders(incomingRequest, upstreamRequest);

        try {
            HttpResponse<byte[]> upstreamResponse = httpClient.send(
                    upstreamRequest.build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            HttpHeaders responseHeaders = new HttpHeaders();
            upstreamResponse.headers().map().forEach(responseHeaders::put);

            return ResponseEntity.status(upstreamResponse.statusCode())
                    .headers(responseHeaders)
                    .body(upstreamResponse.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "Upstream request was interrupted", exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(BAD_GATEWAY, "Unable to reach upstream service", exception);
        }
    }

    private URI buildUpstreamUri(HttpServletRequest incomingRequest) {
        String query = incomingRequest.getQueryString();
        String path = incomingRequest.getRequestURI();
        String upstreamUrl = upstreamRegistry.getNextNode();
        return URI.create(upstreamUrl + path + (query == null ? "" : "?" + query));
    }

    private void copyRequestHeaders(
            HttpServletRequest incomingRequest,
            HttpRequest.Builder upstreamRequest) {
        Enumeration<String> headerNames = incomingRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (headerName.equalsIgnoreCase("host")
                    || headerName.equalsIgnoreCase("content-length")) {
                continue;
            }

            Enumeration<String> headerValues = incomingRequest.getHeaders(headerName);
            while (headerValues.hasMoreElements()) {
                upstreamRequest.header(headerName, headerValues.nextElement());
            }
        }
    }
}
