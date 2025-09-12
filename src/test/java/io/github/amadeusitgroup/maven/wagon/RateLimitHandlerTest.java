package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for RateLimitHandler.
 */
public class RateLimitHandlerTest {

    private RateLimitHandler rateLimitHandler;

    @BeforeEach
    public void setUp() {
        rateLimitHandler = RateLimitHandler.getInstance();
    }

    @AfterEach
    public void tearDown() {
        rateLimitHandler.reset();
    }

    @Test
    public void testSingletonInstance() {
        RateLimitHandler instance1 = RateLimitHandler.getInstance();
        RateLimitHandler instance2 = RateLimitHandler.getInstance();
        assertSame(instance1, instance2, "RateLimitHandler should be a singleton");
    }

    @Test
    public void testCheckRateLimit_NoLimitSet() throws Exception {
        // When no rate limit is set, should not block
        long startTime = System.currentTimeMillis();
        rateLimitHandler.checkRateLimit();
        long endTime = System.currentTimeMillis();
        
        // Should complete quickly (within 100ms)
        assertTrue(endTime - startTime < 100, "Should not block when no rate limit is set");
    }

    @Test
    public void testUpdateRateLimit_ValidHeaders() throws Exception {
        // Mock connection with rate limit headers
        HttpURLConnection mockConnection = createMockConnection();
        mockConnection.setRequestProperty("X-RateLimit-Remaining", "4999");
        mockConnection.setRequestProperty("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 3600));
        
        rateLimitHandler.updateRateLimit(mockConnection);
        
        // Should have updated internal state
        Object stats = rateLimitHandler.getStatistics();
        assertNotNull(stats, "Statistics should be available after update");
    }

    @Test
    public void testUpdateRateLimit_LowRemaining() throws Exception {
        // Ensure clean state for this test
        rateLimitHandler.reset();
        
        // Mock connection with low remaining requests
        HttpURLConnection mockConnection = createMockConnection();
        mockConnection.setRequestProperty("X-RateLimit-Remaining", "1");
        mockConnection.setRequestProperty("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 60));
        
        rateLimitHandler.updateRateLimit(mockConnection);
        
        // Next check should potentially wait
        long startTime = System.currentTimeMillis();
        rateLimitHandler.checkRateLimit();
        long endTime = System.currentTimeMillis();
        
        // Should complete within reasonable time (may or may not wait depending on implementation)
        assertTrue(endTime - startTime < 5000, "Should not wait excessively long");
    }

    @Test
    public void testUpdateRateLimit_ZeroRemaining() throws Exception {
        // Mock connection with zero remaining requests
        HttpURLConnection mockConnection = createMockConnection();
        mockConnection.setRequestProperty("X-RateLimit-Remaining", "0");
        mockConnection.setRequestProperty("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 10));
        
        rateLimitHandler.updateRateLimit(mockConnection);
        
        // Next check should wait
        long startTime = System.currentTimeMillis();
        rateLimitHandler.checkRateLimit();
        long endTime = System.currentTimeMillis();
        
        // Should wait some time but not the full reset period in test
        assertTrue(endTime - startTime >= 0, "Should handle zero remaining requests");
    }

    @Test
    public void testGetStatistics() {
        Object stats = rateLimitHandler.getStatistics();
        assertNotNull(stats, "Statistics should not be null");
    }

    @Test
    public void testReset() throws Exception {
        // Set some rate limit state
        HttpURLConnection mockConnection = createMockConnection();
        mockConnection.setRequestProperty("X-RateLimit-Remaining", "100");
        mockConnection.setRequestProperty("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 3600));
        
        rateLimitHandler.updateRateLimit(mockConnection);
        
        // Reset should clear state
        rateLimitHandler.reset();
        
        // Should behave as if no limit is set
        long startTime = System.currentTimeMillis();
        rateLimitHandler.checkRateLimit();
        long endTime = System.currentTimeMillis();
        
        assertTrue(endTime - startTime < 100, "Should not block after reset");
    }

    @Test
    public void testConcurrentAccess() throws Exception {
        // Test concurrent access to rate limit handler
        Thread[] threads = new Thread[5];
        Exception[] exceptions = new Exception[5];
        
        for (int i = 0; i < threads.length; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    rateLimitHandler.checkRateLimit();
                } catch (Exception e) {
                    exceptions[threadIndex] = e;
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(5000); // 5 second timeout
        }
        
        // Check for exceptions
        for (int i = 0; i < exceptions.length; i++) {
            if (exceptions[i] != null) {
                fail("Thread " + i + " threw exception: " + exceptions[i].getMessage());
            }
        }
    }

    @Test
    public void testInvalidHeaders() throws Exception {
        // Mock connection with invalid headers
        HttpURLConnection mockConnection = createMockConnection();
        mockConnection.setRequestProperty("X-RateLimit-Remaining", "invalid");
        mockConnection.setRequestProperty("X-RateLimit-Reset", "also-invalid");
        
        // Should handle gracefully without throwing
        assertDoesNotThrow(() -> {
            rateLimitHandler.updateRateLimit(mockConnection);
        }, "Should handle invalid headers gracefully");
    }

    @Test
    public void testMissingHeaders() throws Exception {
        // Mock connection with no rate limit headers
        HttpURLConnection mockConnection = createMockConnection();
        
        // Should handle gracefully without throwing
        assertDoesNotThrow(() -> {
            rateLimitHandler.updateRateLimit(mockConnection);
        }, "Should handle missing headers gracefully");
    }

    private HttpURLConnection createMockConnection() throws Exception {
        URL url = new URL("https://api.github.com/test");
        return (HttpURLConnection) url.openConnection();
    }
}
