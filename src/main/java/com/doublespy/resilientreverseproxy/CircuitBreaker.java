package com.doublespy.resilientreverseproxy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicLong openedAtNanos = new AtomicLong();
    private final AtomicBoolean halfOpenProbeInProgress = new AtomicBoolean();
    private final String targetUrl;
    private final int failureThreshold;
    private final long openRetryDelayNanos;
    private final LongSupplier nanoTime;
    private final ReentrantLock transitionLock = new ReentrantLock();

    public CircuitBreaker() {
        this("unknown", 3, Duration.ofSeconds(10));
    }

    public CircuitBreaker(String targetUrl) {
        this(targetUrl, 3, Duration.ofSeconds(10));
    }

    public CircuitBreaker(String targetUrl, int failureThreshold, Duration openRetryDelay) {
        this(targetUrl, failureThreshold, openRetryDelay, System::nanoTime);
    }

    public CircuitBreaker(
            String targetUrl,
            int failureThreshold,
            Duration openRetryDelay,
            LongSupplier nanoTime) {
        if (failureThreshold <= 0 || openRetryDelay.isNegative() || openRetryDelay.isZero()) {
            throw new IllegalArgumentException("Circuit breaker settings must be positive");
        }
        this.targetUrl = targetUrl;
        this.failureThreshold = failureThreshold;
        this.openRetryDelayNanos = openRetryDelay.toNanos();
        this.nanoTime = nanoTime;
    }

    public boolean allowRequest() {
        State currentState = state.get();

        if (currentState == State.CLOSED) {
            return true;
        }

        if (currentState == State.OPEN) {
            long elapsedNanos = nanoTime.getAsLong() - openedAtNanos.get();
            if (elapsedNanos < openRetryDelayNanos
                    || !state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                return false;
            }
        }

        return halfOpenProbeInProgress.compareAndSet(false, true);
    }

    public boolean isRequestEligible() {
        State currentState = state.get();
        if (currentState == State.CLOSED) {
            return true;
        }
        if (currentState == State.OPEN) {
            return nanoTime.getAsLong() - openedAtNanos.get() >= openRetryDelayNanos;
        }
        return !halfOpenProbeInProgress.get();
    }

    public void recordFailure() {
        transitionLock.lock();
        try {
            State currentState = state.get();

            if (currentState == State.HALF_OPEN) {
                open();
                return;
            }

            if (currentState != State.CLOSED) {
                return;
            }

            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                openedAtNanos.set(nanoTime.getAsLong());
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    log.warn("Circuit breaker tripped to OPEN for backend {}", targetUrl);
                }
            }
        } finally {
            transitionLock.unlock();
        }
    }

    public void recordSuccess() {
        transitionLock.lock();
        try {
            failureCount.set(0);
            halfOpenProbeInProgress.set(false);
            state.set(State.CLOSED);
        } finally {
            transitionLock.unlock();
        }
    }

    public State getState() {
        return state.get();
    }

    private void open() {
        openedAtNanos.set(nanoTime.getAsLong());
        if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            halfOpenProbeInProgress.set(false);
            failureCount.set(failureThreshold);
            log.warn("Circuit breaker tripped to OPEN for backend {}", targetUrl);
        }
    }
}
