package io.github.amadeusitgroup.maven.wagon;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Handles retry logic with exponential backoff for failed GitHub API operations.
 */
public class RetryHandler {
    
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_BASE_DELAY_MS = 1000; // 1 second
    private static final long DEFAULT_MAX_DELAY_MS = 30000; // 30 seconds
    private static final double DEFAULT_JITTER_FACTOR = 0.1; // 10% jitter
    
    private static volatile RetryHandler instance;
    
    private final int maxRetries;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterFactor;
    private final Random random;
    
    private RetryHandler() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MS, DEFAULT_MAX_DELAY_MS, DEFAULT_JITTER_FACTOR);
    }
    
    private RetryHandler(int maxRetries, long baseDelayMs, long maxDelayMs, double jitterFactor) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitterFactor = jitterFactor;
        this.random = new Random();
    }
    
    public static RetryHandler getInstance() {
        if (instance == null) {
            synchronized (RetryHandler.class) {
                if (instance == null) {
                    instance = new RetryHandler();
                }
            }
        }
        return instance;
    }
    
    /**
     * Execute operation with retry logic
     */
    public <T> T executeWithRetry(RetryableOperation<T> operation) throws IOException, InterruptedException {
        IOException lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return operation.execute();
            } catch (IOException e) {
                lastException = e;
                
                // Don't retry on last attempt
                if (attempt == maxRetries) {
                    break;
                }
                
                // Check if error is retryable
                if (!isRetryableError(e)) {
                    throw e;
                }
                
                // Calculate delay and wait
                long delay = calculateDelay(attempt);
                Thread.sleep(delay);
            }
        }
        
        throw lastException;
    }
    
    /**
     * Execute HTTP operation with retry logic and rate limit handling
     */
    public <T> T executeHttpWithRetry(HttpRetryableOperation<T> operation) throws IOException, InterruptedException {
        IOException lastException = null;
        RateLimitHandler rateLimitHandler = RateLimitHandler.getInstance();
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // Check rate limits before making request
                if (!rateLimitHandler.canMakeRequest()) {
                    long waitTime = rateLimitHandler.getWaitTimeMs();
                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }
                }
                
                // Apply intelligent throttling
                rateLimitHandler.throttleRequest();
                
                HttpURLConnection connection = operation.createConnection();
                T result = operation.executeRequest(connection);
                
                // Update rate limit info from successful response
                rateLimitHandler.updateFromResponse(connection);
                
                return result;
                
            } catch (IOException e) {
                lastException = e;
                
                // Handle rate limit exceeded specifically
                if (isRateLimitError(e)) {
                    HttpURLConnection connection = operation.getLastConnection();
                    if (connection != null) {
                        long waitTime = rateLimitHandler.handleRateLimitExceeded(connection);
                        if (waitTime > 0) {
                            Thread.sleep(waitTime);
                            continue; // Don't count as retry attempt for rate limits
                        }
                    }
                }
                
                // Don't retry on last attempt
                if (attempt == maxRetries) {
                    break;
                }
                
                // Check if error is retryable
                if (!isRetryableError(e)) {
                    throw e;
                }
                
                // Calculate delay and wait
                long delay = calculateDelay(attempt);
                Thread.sleep(delay);
            }
        }
        
        throw lastException;
    }
    
    /**
     * Calculate exponential backoff delay with jitter
     */
    private long calculateDelay(int attempt) {
        // Exponential backoff: baseDelay * 2^attempt
        long delay = baseDelayMs * (1L << attempt);
        
        // Cap at maximum delay
        delay = Math.min(delay, maxDelayMs);
        
        // Add jitter to avoid thundering herd
        if (jitterFactor > 0) {
            double jitter = (random.nextDouble() * 2 - 1) * jitterFactor; // -jitterFactor to +jitterFactor
            delay = (long) (delay * (1 + jitter));
        }
        
        return Math.max(delay, 0);
    }
    
    /**
     * Check if an error is retryable
     */
    private boolean isRetryableError(IOException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        message = message.toLowerCase();
        
        // Network-related errors that are typically transient
        return message.contains("timeout") ||
               message.contains("connection reset") ||
               message.contains("connection refused") ||
               message.contains("temporary failure") ||
               message.contains("failure") ||
               message.contains("service unavailable") ||
               message.contains("server error") ||
               message.contains("502") ||
               message.contains("503") ||
               message.contains("504");
    }
    
    /**
     * Check if error is specifically a rate limit error
     */
    private boolean isRateLimitError(IOException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        message = message.toLowerCase();
        return message.contains("rate limit") ||
               message.contains("403") ||
               message.contains("too many requests") ||
               message.contains("429");
    }
    
    /**
     * Get retry statistics for monitoring
     */
    public Object getStatistics() {
        return "Retry handler active with max retries: " + maxRetries;
    }
    
    /**
     * Reset retry handler state
     */
    public void reset() {
        // Reset any internal state if needed
    }
    
    /**
     * Get retry statistics for monitoring
     */
    public RetryStats getStats(int totalAttempts, long totalDelayMs) {
        return new RetryStats(maxRetries, totalAttempts, totalDelayMs);
    }
    
    /**
     * Interface for retryable operations
     */
    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws IOException;
    }
    
    /**
     * Interface for HTTP retryable operations with connection access
     */
    public interface HttpRetryableOperation<T> {
        HttpURLConnection createConnection() throws IOException;
        T executeRequest(HttpURLConnection connection) throws IOException;
        HttpURLConnection getLastConnection();
    }
    
    /**
     * Retry statistics for monitoring
     */
    public static class RetryStats {
        private final int maxRetries;
        private final int totalAttempts;
        private final long totalDelayMs;
        
        public RetryStats(int maxRetries, int totalAttempts, long totalDelayMs) {
            this.maxRetries = maxRetries;
            this.totalAttempts = totalAttempts;
            this.totalDelayMs = totalDelayMs;
        }
        
        public int getMaxRetries() { return maxRetries; }
        public int getTotalAttempts() { return totalAttempts; }
        public long getTotalDelayMs() { return totalDelayMs; }
        public int getRetryCount() { return Math.max(0, totalAttempts - 1); }
        
        @Override
        public String toString() {
            return String.format("RetryStats{maxRetries=%d, attempts=%d, retries=%d, totalDelay=%dms}", 
                maxRetries, totalAttempts, getRetryCount(), totalDelayMs);
        }
    }
}
