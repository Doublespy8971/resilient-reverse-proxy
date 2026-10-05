package com.doublespy.resilientreverseproxy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class UpstreamRegistry {

    private final List<String> nodes;
    private final Map<String, CircuitBreaker> circuitBreakers;
    private final Map<String, Boolean> nodeHealth = new ConcurrentHashMap<>();
    private final AtomicInteger nextNodeIndex = new AtomicInteger();

    public UpstreamRegistry(ProxyConfig proxyConfig) {
        nodes = List.copyOf(proxyConfig.getBackends());
        circuitBreakers = nodes.stream()
                .collect(Collectors.toUnmodifiableMap(
                        node -> node,
                        node -> new CircuitBreaker(
                                node,
                                proxyConfig.getFailureThreshold(),
                                proxyConfig.getOpenDelay())));
        nodes.forEach(node -> nodeHealth.put(node, true));
    }

    public String getNextNode() {
        return getEligibleNodes().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No eligible upstream nodes available"));
    }

    public List<String> getEligibleNodes() {
        if (nodes.isEmpty()) {
            return List.of();
        }

        int startIndex = Math.floorMod(nextNodeIndex.getAndIncrement(), nodes.size());
        List<String> eligibleNodes = new java.util.ArrayList<>();
        for (int offset = 0; offset < nodes.size(); offset++) {
            String node = nodes.get((startIndex + offset) % nodes.size());
            CircuitBreaker circuitBreaker = circuitBreakers.get(node);
            if (isHealthy(node) && circuitBreaker.isRequestEligible()) {
                eligibleNodes.add(node);
            }
        }
        return eligibleNodes;
    }

    public List<String> getNodes() {
        return nodes;
    }

    public void markHealthy(String node) {
        nodeHealth.computeIfPresent(node, (ignored, current) -> true);
    }

    public void markUnhealthy(String node) {
        nodeHealth.computeIfPresent(node, (ignored, current) -> false);
    }

    public boolean isHealthy(String node) {
        return Boolean.TRUE.equals(nodeHealth.get(node));
    }

    public CircuitBreaker getCircuitBreaker(String node) {
        return circuitBreakers.get(node);
    }
}
