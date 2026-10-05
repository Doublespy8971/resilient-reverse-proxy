package com.doublespy.resilientreverseproxy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

@Component
public class UpstreamRegistry {

    private final List<String> nodes = List.of(
            "http://localhost:8081",
            "http://localhost:8082");
    private final AtomicInteger nextNodeIndex = new AtomicInteger();

    public String getNextNode() {
        int nodeIndex = Math.floorMod(nextNodeIndex.getAndIncrement(), nodes.size());
        return nodes.get(nodeIndex);
    }
}
