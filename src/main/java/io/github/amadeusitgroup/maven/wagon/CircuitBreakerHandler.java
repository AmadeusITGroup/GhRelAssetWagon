package io.github.amadeusitgroup.maven.wagon;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker pattern implementation for GitHub API to fail-fast during outages
 * and prevent cascading failures.
 */
public class CircuitBreakerHandler {
    
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final long DEFAULT_TIMEOUT_MS = 60000; // 1 minute
    private static final int DEFAULT_SUCCESS_THRESHOLD = 3;
    
    private final int failureThreshold;
    private final long timeoutMs;
    private final int successThreshold;
    
    private final AtomicInteger failureCount;
    private final AtomicInteger successCount;
    private final AtomicLong lastFailureTime;
    private final AtomicReference<State> state;
    
    private static volatile CircuitBreakerHandler instance;
    
    public enum State {
        CLOSED,    // Normal operation
        OPEN,      // Circuit is open, failing fast
        HALF_OPEN  // Testing if service is back
    }
    
    private CircuitBreakerHandler() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_TIMEOUT_MS, DEFAULT_SUCCESS_THRESHOLD);
    }
    
    public CircuitBreakerHandler(int failureThreshold, long timeoutMs, int successThreshold) {
        this.failureThreshold = failureThreshold;
        this.timeoutMs = timeoutMs;
        this.successThreshold = successThreshold;
        this.failureCount = new AtomicInteger(0);
        this.successCount = new AtomicInteger(0);
        this.lastFailureTime = new AtomicLong(0);
        this.state = new AtomicReference<>(State.CLOSED);
    }
    
    /**
     * Get singleton instance of circuit breaker
     */
    public static CircuitBreakerHandler getInstance() {
        if (instance == null) {
            synchronized (CircuitBreakerHandler.class) {
                if (instance == null) {
                    instance = new CircuitBreakerHandler();
                }
            }
        }
        return instance;
    }
    
    /**
     * Execute operation with circuit breaker protection
     */
    public <T> T execute(CircuitBreakerOperation<T> operation) throws IOException {
        if (!canExecute()) {
            throw new IOException("Circuit breaker is OPEN - GitHub API appears to be unavailable");
        }
        
        try {
            T result = operation.execute();
            recordSuccess();
            return result;
        } catch (IOException e) {
            recordFailure();
            throw e;
        }
    }
    
    /**
     * Check if operation can be executed based on circuit breaker state
     */
    public boolean canExecute() {
        State currentState = state.get();
        
        switch (currentState) {
            case CLOSED:
                return true;
                
            case OPEN:
                // Check if timeout has passed to move to HALF_OPEN
                if (System.currentTimeMillis() - lastFailureTime.get() >= timeoutMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        successCount.set(0);
                        return true;
                    }
                }
                return false;
                
            case HALF_OPEN:
                return true;
                
            default:
                return false;
        }
    }
    
    /**
     * Check if circuit is open
     */
    public boolean isCircuitOpen() {
        return state.get() == State.OPEN;
    }
    
    /**
     * Record successful operation (public method)
     */
    public void onSuccess() {
        recordSuccess();
    }
    
    /**
     * Record failed operation (public method)
     */
    public void onFailure() {
        recordFailure();
    }
    
    /**
     * Get statistics for monitoring
     */
    public Object getStatistics() {
        CircuitBreakerStatus status = getStatus();
        return status.toString();
    }
    
    /**
     * Record successful operation (internal)
     */
    private void recordSuccess() {
        State currentState = state.get();
        
        if (currentState == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            if (successes >= successThreshold) {
                // Enough successes to close circuit
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    failureCount.set(0);
                    successCount.set(0);
                }
            }
        } else if (currentState == State.CLOSED) {
            // Reset failure count on success in closed state
            failureCount.set(0);
        }
    }
    
    /**
     * Record failed operation (internal)
     */
    private void recordFailure() {
        State currentState = state.get();
        lastFailureTime.set(System.currentTimeMillis());
        
        if (currentState == State.HALF_OPEN) {
            // Failure in half-open state immediately opens circuit
            state.compareAndSet(State.HALF_OPEN, State.OPEN);
            successCount.set(0);
        } else if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                // Too many failures, open circuit
                state.compareAndSet(State.CLOSED, State.OPEN);
            }
        }
    }
    
    /**
     * Get current circuit breaker status
     */
    public CircuitBreakerStatus getStatus() {
        State currentState = state.get();
        int failures = failureCount.get();
        int successes = successCount.get();
        long lastFailure = lastFailureTime.get();
        
        long timeUntilRetry = 0;
        if (currentState == State.OPEN) {
            timeUntilRetry = Math.max(0, timeoutMs - (System.currentTimeMillis() - lastFailure));
        }
        
        return new CircuitBreakerStatus(currentState, failures, successes, 
            lastFailure, timeUntilRetry, failureThreshold, successThreshold);
    }
    
    /**
     * Manually reset circuit breaker to closed state
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(0);
    }
    
    /**
     * Manually open circuit breaker
     */
    public void forceOpen() {
        state.set(State.OPEN);
        lastFailureTime.set(System.currentTimeMillis());
    }
    
    /**
     * Interface for circuit breaker protected operations
     */
    @FunctionalInterface
    public interface CircuitBreakerOperation<T> {
        T execute() throws IOException;
    }
    
    /**
     * Circuit breaker status information
     */
    public static class CircuitBreakerStatus {
        private final State state;
        private final int failureCount;
        private final int successCount;
        private final long lastFailureTime;
        private final long timeUntilRetryMs;
        private final int failureThreshold;
        private final int successThreshold;
        
        public CircuitBreakerStatus(State state, int failureCount, int successCount, 
                                  long lastFailureTime, long timeUntilRetryMs,
                                  int failureThreshold, int successThreshold) {
            this.state = state;
            this.failureCount = failureCount;
            this.successCount = successCount;
            this.lastFailureTime = lastFailureTime;
            this.timeUntilRetryMs = timeUntilRetryMs;
            this.failureThreshold = failureThreshold;
            this.successThreshold = successThreshold;
        }
        
        public State getState() { return state; }
        public int getFailureCount() { return failureCount; }
        public int getSuccessCount() { return successCount; }
        public long getLastFailureTime() { return lastFailureTime; }
        public long getTimeUntilRetryMs() { return timeUntilRetryMs; }
        public int getFailureThreshold() { return failureThreshold; }
        public int getSuccessThreshold() { return successThreshold; }
        
        public boolean isOpen() { return state == State.OPEN; }
        public boolean isClosed() { return state == State.CLOSED; }
        public boolean isHalfOpen() { return state == State.HALF_OPEN; }
        
        @Override
        public String toString() {
            return String.format("CircuitBreaker{state=%s, failures=%d/%d, successes=%d/%d, retryIn=%dms}", 
                state, failureCount, failureThreshold, successCount, successThreshold, timeUntilRetryMs);
        }
    }
}
