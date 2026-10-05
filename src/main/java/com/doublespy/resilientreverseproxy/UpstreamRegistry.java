package com.doublespy.resilientreverseproxy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class UpstreamRegistry {

    private final List<String> nodes = Arrays.stream(System.getenv()
                    .getOrDefault(
                            "UPSTREAM_URLS",
                            "http://localhost:8081,http://localhost:8082")
                    .split(","))
            .map(String::trim)
            .filter(node -> !node.isEmpty())
            .toList();
    private final Map<String, CircuitBreaker> circuitBreakers = nodes.stream()
            .collect(Collectors.toUnmodifiableMap(node -> node, node -> new CircuitBreaker()));
    private final Map<String, Boolean> nodeHealth = new ConcurrentHashMap<>();
    private final AtomicInteger nextNodeIndex = new AtomicInteger();

    public UpstreamRegistry() {
        nodes.forEach(node -> nodeHealth.put(node, true));
    }

    public String getNextNode() {
        for (int attempts = 0; attempts < nodes.size(); attempts++) {
            int nodeIndex = Math.floorMod(nextNodeIndex.getAndIncrement(), nodes.size());
            String node = nodes.get(nodeIndex);
            if (isHealthy(node)) {
                return node;
            }
        }

        throw new IllegalStateException("No healthy upstream nodes available");
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
