package io.github.amadeusitgroup.maven.wagon;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles GitHub API rate limiting with intelligent backoff and monitoring.
 */
public class RateLimitHandler {
    
    private static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RATE_LIMIT_RESET_HEADER = "X-RateLimit-Reset";
    private static final String RATE_LIMIT_LIMIT_HEADER = "X-RateLimit-Limit";
    private static final String RETRY_AFTER_HEADER = "Retry-After";
    
    private final AtomicInteger remainingRequests;
    private final AtomicLong resetTime;
    private final AtomicInteger totalLimit;
    private final AtomicLong lastRequestTime;
    
    private static volatile RateLimitHandler instance;
    
    private RateLimitHandler() {
        this.remainingRequests = new AtomicInteger(5000); // Default GitHub limit
        this.resetTime = new AtomicLong(0);
        this.totalLimit = new AtomicInteger(5000);
        this.lastRequestTime = new AtomicLong(0);
    }
    
    /**
     * Get singleton instance of rate limit handler
     */
    public static RateLimitHandler getInstance() {
        if (instance == null) {
            synchronized (RateLimitHandler.class) {
                if (instance == null) {
                    instance = new RateLimitHandler();
                }
            }
        }
        return instance;
    }
    
    /**
     * Check if we should proceed with the request or wait
     */
    public boolean canMakeRequest() {
        long currentTime = System.currentTimeMillis() / 1000;
        long resetTimeSeconds = resetTime.get();
        
        // If reset time has passed, we can make requests
        if (currentTime >= resetTimeSeconds) {
            return true;
        }
        
        // Check remaining requests
        int remaining = remainingRequests.get();
        
        // If we have remaining requests, allow it
        if (remaining > 0) {
            return true;
        }
        
        // Rate limited - calculate wait time
        return false;
    }
    
    /**
     * Calculate how long to wait before next request (in milliseconds)
     */
    public long getWaitTimeMs() {
        long currentTime = System.currentTimeMillis() / 1000;
        long resetTimeSeconds = resetTime.get();
        
        if (currentTime >= resetTimeSeconds) {
            return 0;
        }
        
        int remaining = remainingRequests.get();
        if (remaining > 0) {
            return 0;
        }
        
        // Wait until reset time
        return (resetTimeSeconds - currentTime) * 1000;
    }
    
    /**
     * Update rate limit information from response headers
     */
    public void updateFromResponse(HttpURLConnection connection) {
        try {
            String remainingHeader = connection.getHeaderField(RATE_LIMIT_REMAINING_HEADER);
            String resetHeader = connection.getHeaderField(RATE_LIMIT_RESET_HEADER);
            String limitHeader = connection.getHeaderField(RATE_LIMIT_LIMIT_HEADER);
            
            if (remainingHeader != null) {
                remainingRequests.set(Integer.parseInt(remainingHeader));
            }
            
            if (resetHeader != null) {
                resetTime.set(Long.parseLong(resetHeader));
            }
            
            if (limitHeader != null) {
                totalLimit.set(Integer.parseInt(limitHeader));
            }
            
            lastRequestTime.set(System.currentTimeMillis());
            
        } catch (NumberFormatException e) {
            // Ignore parsing errors, keep existing values
        }
    }
    
    /**
     * Handle rate limit exceeded response (HTTP 403 with rate limit headers)
     */
    public long handleRateLimitExceeded(HttpURLConnection connection) throws IOException {
        updateFromResponse(connection);
        
        // Check for Retry-After header (secondary rate limits)
        String retryAfter = connection.getHeaderField(RETRY_AFTER_HEADER);
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter) * 1000; // Convert seconds to milliseconds
            } catch (NumberFormatException e) {
                // Ignore parsing error
            }
        }
        
        return getWaitTimeMs();
    }
    
    /**
     * Get current rate limit status
     */
    public RateLimitStatus getStatus() {
        long currentTime = System.currentTimeMillis() / 1000;
        long resetTimeSeconds = resetTime.get();
        int remaining = remainingRequests.get();
        int limit = totalLimit.get();
        
        boolean isLimited = remaining <= 0 && currentTime < resetTimeSeconds;
        long waitTime = isLimited ? getWaitTimeMs() : 0;
        
        return new RateLimitStatus(remaining, limit, resetTimeSeconds, isLimited, waitTime);
    }
    
    /**
     * Check rate limit and wait if necessary
     */
    public void checkRateLimit() throws InterruptedException {
        int remaining = remainingRequests.get();
        long currentTime = System.currentTimeMillis() / 1000;
        long resetTimeSeconds = resetTime.get();
        
        // If we have remaining requests, allow the request
        if (remaining > 0) {
            return;
        }
        
        // If reset time has passed, allow the request
        if (currentTime >= resetTimeSeconds) {
            return;
        }
        
        // Only wait if we're truly rate limited (0 remaining and before reset time)
        long waitTime = (resetTimeSeconds - currentTime) * 1000;
        
        // Cap wait time to prevent excessive delays in tests
        if (waitTime > 0 && waitTime <= 300000) { // Max 5 minutes
            Thread.sleep(waitTime);
        }
    }
    
    /**
     * Update rate limit information from HTTP connection
     */
    public void updateRateLimit(HttpURLConnection connection) {
        updateFromResponse(connection);
    }
    
    /**
     * Get statistics for monitoring
     */
    public Object getStatistics() {
        RateLimitStatus status = getStatus();
        return status.toString();
    }
    
    /**
     * Reset rate limit handler state
     */
    public void reset() {
        remainingRequests.set(5000);
        resetTime.set(0);
        totalLimit.set(5000);
        lastRequestTime.set(0);
    }
    
    /**
     * Implement intelligent request spacing to avoid hitting limits
     */
    public void throttleRequest() throws InterruptedException {
        int remaining = remainingRequests.get();
        long resetTimeSeconds = resetTime.get();
        long currentTime = System.currentTimeMillis() / 1000;
        
        // If we're getting close to the limit, add some delay
        if (remaining > 0 && remaining < 100) {
            long timeUntilReset = resetTimeSeconds - currentTime;
            if (timeUntilReset > 0) {
                // Spread remaining requests over remaining time
                long delayMs = (timeUntilReset * 1000) / remaining;
                delayMs = Math.min(delayMs, 5000); // Max 5 second delay
                delayMs = Math.max(delayMs, 100);  // Min 100ms delay
                
                Thread.sleep(delayMs);
            }
        }
    }
    
    /**
     * Rate limit status information
     */
    public static class RateLimitStatus {
        private final int remaining;
        private final int limit;
        private final long resetTime;
        private final boolean isLimited;
        private final long waitTimeMs;
        
        public RateLimitStatus(int remaining, int limit, long resetTime, boolean isLimited, long waitTimeMs) {
            this.remaining = remaining;
            this.limit = limit;
            this.resetTime = resetTime;
            this.isLimited = isLimited;
            this.waitTimeMs = waitTimeMs;
        }
        
        public int getRemaining() { return remaining; }
        public int getLimit() { return limit; }
        public long getResetTime() { return resetTime; }
        public boolean isLimited() { return isLimited; }
        public long getWaitTimeMs() { return waitTimeMs; }
        
        public String getResetTimeFormatted() {
            return Instant.ofEpochSecond(resetTime)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        }
        
        @Override
        public String toString() {
            return String.format("RateLimit{remaining=%d/%d, reset=%s, limited=%s, wait=%dms}", 
                remaining, limit, getResetTimeFormatted(), isLimited, waitTimeMs);
        }
    }
}
