package com.doublespy.resilientreverseproxy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class CircuitBreaker {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_RETRY_DELAY_NANOS = Duration.ofSeconds(10).toNanos();

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicLong openedAtNanos = new AtomicLong();
    private final AtomicBoolean halfOpenProbeInProgress = new AtomicBoolean();

    public boolean allowRequest() {
        State currentState = state.get();

        if (currentState == State.CLOSED) {
            return true;
        }

        if (currentState == State.OPEN) {
            long elapsedNanos = System.nanoTime() - openedAtNanos.get();
            if (elapsedNanos < OPEN_RETRY_DELAY_NANOS
                    || !state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                return false;
            }
        }

        return halfOpenProbeInProgress.compareAndSet(false, true);
    }

    public void recordFailure() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            open();
            return;
        }

        if (currentState != State.CLOSED) {
            return;
        }

        int failures = failureCount.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            openedAtNanos.set(System.nanoTime());
            state.compareAndSet(State.CLOSED, State.OPEN);
        }
    }

    public void recordSuccess() {
        failureCount.set(0);
        halfOpenProbeInProgress.set(false);
        state.set(State.CLOSED);
    }

    public State getState() {
        return state.get();
    }

    private void open() {
        openedAtNanos.set(System.nanoTime());
        if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            halfOpenProbeInProgress.set(false);
            failureCount.set(FAILURE_THRESHOLD);
        }
    }
}
