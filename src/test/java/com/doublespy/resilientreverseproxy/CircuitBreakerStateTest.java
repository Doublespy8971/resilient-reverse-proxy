package com.doublespy.resilientreverseproxy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerStateTest {

    @Test
    void transitionsFromClosedToOpenAtConfiguredFailureThreshold() {
        CircuitBreaker breaker = new CircuitBreaker(
                "http://backend", 3, Duration.ofSeconds(10));

        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        breaker.recordFailure();

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    void rejectsBeforeOpenDelayAndAllowsOneProbeAfterDelay() {
        AtomicLong now = new AtomicLong(100);
        CircuitBreaker breaker = new CircuitBreaker(
                "http://backend", 1, Duration.ofNanos(10), now::get);
        breaker.recordFailure();

        assertFalse(breaker.allowRequest());
        now.set(110);
        assertTrue(breaker.allowRequest());
    }

    @Test
    void successfulProbeClosesCircuitAndFourXxProbeReleasesSlot() {
        AtomicLong now = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(
                "http://backend", 1, Duration.ofNanos(1), now::get);
        breaker.recordFailure();
        now.incrementAndGet();

        assertTrue(breaker.allowRequest());
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());

        breaker.recordFailure();
        now.incrementAndGet();
        assertTrue(breaker.allowRequest());
        breaker.recordSuccess(); // A 4xx response is a successful probe outcome.
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void failedProbeReopensCircuit() {
        AtomicLong now = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(
                "http://backend", 1, Duration.ofNanos(1), now::get);
        breaker.recordFailure();
        now.incrementAndGet();

        assertTrue(breaker.allowRequest());
        breaker.recordFailure();

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void exactlyOneOfThirtyTwoConcurrentCallsGetsHalfOpenProbe() throws InterruptedException {
        AtomicLong now = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(
                "http://backend", 1, Duration.ofNanos(1), now::get);
        breaker.recordFailure();
        now.incrementAndGet();

        int callers = 32;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        Thread[] threads = new Thread[callers];
        IntStream.range(0, callers).forEach(index -> threads[index] = new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                if (breaker.allowRequest()) {
                    allowed.incrementAndGet();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        for (Thread thread : threads) {
            thread.start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, allowed.get());
    }
}
