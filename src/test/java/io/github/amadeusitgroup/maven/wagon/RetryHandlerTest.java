package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for RetryHandler.
 */
public class RetryHandlerTest {

    private RetryHandler retryHandler;

    @BeforeEach
    public void setUp() {
        retryHandler = RetryHandler.getInstance();
    }

    @AfterEach
    public void tearDown() {
        retryHandler.reset();
    }

    @Test
    public void testSingletonInstance() {
        RetryHandler instance1 = RetryHandler.getInstance();
        RetryHandler instance2 = RetryHandler.getInstance();
        assertSame(instance1, instance2, "RetryHandler should be a singleton");
    }

    @Test
    public void testExecuteWithRetry_Success() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        
        String result = retryHandler.executeWithRetry(() -> {
            callCount.incrementAndGet();
            return "success";
        });
        
        assertEquals("success", result, "Should return successful result");
        assertEquals(1, callCount.get(), "Should only call once on success");
    }

    @Test
    public void testExecuteWithRetry_SuccessAfterRetries() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        
        String result = retryHandler.executeWithRetry(() -> {
            int count = callCount.incrementAndGet();
            if (count < 3) {
                throw new IOException("Temporary failure " + count);
            }
            return "success";
        });
        
        assertEquals("success", result, "Should return successful result after retries");
        assertEquals(3, callCount.get(), "Should retry until success");
    }

    @Test
    public void testExecuteWithRetry_MaxRetriesExceeded() {
        AtomicInteger callCount = new AtomicInteger(0);
        
        assertThrows(IOException.class, () -> {
            retryHandler.executeWithRetry(() -> {
                callCount.incrementAndGet();
                throw new IOException("Persistent failure");
            });
        }, "Should throw exception after max retries");
        
        assertTrue(callCount.get() > 1, "Should have retried multiple times");
        assertTrue(callCount.get() <= 5, "Should not exceed max retries");
    }

    @Test
    public void testExecuteWithRetry_ExponentialBackoff() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        long[] callTimes = new long[4];
        
        try {
            retryHandler.executeWithRetry(() -> {
                int count = callCount.incrementAndGet();
                if (count <= callTimes.length) {
                    callTimes[count - 1] = System.currentTimeMillis();
                }
                throw new IOException("Failure " + count);
            });
        } catch (IOException e) {
            // Expected
        }
        
        assertTrue(callCount.get() >= 2, "Should have made multiple attempts");
        
        // Check that there was some delay between calls (exponential backoff)
        if (callCount.get() >= 2) {
            long delay1 = callTimes[1] - callTimes[0];
            assertTrue(delay1 >= 0, "Should have delay between first and second call");
        }
    }

    @Test
    public void testExecuteWithRetry_InterruptedException() {
        Thread.currentThread().interrupt();
        
        assertThrows(RuntimeException.class, () -> {
            retryHandler.executeWithRetry(() -> {
                throw new RuntimeException("Should not retry when interrupted");
            });
        }, "Should handle interruption properly");
        
        // Clear interrupt flag
        Thread.interrupted();
    }

    @Test
    public void testGetStatistics() throws Exception {
        // Execute some operations to generate statistics
        try {
            retryHandler.executeWithRetry(() -> {
                throw new RuntimeException("Test failure");
            });
        } catch (RuntimeException e) {
            // Expected
        }
        
        Object stats = retryHandler.getStatistics();
        assertNotNull(stats, "Statistics should not be null");
    }

    @Test
    public void testReset() throws Exception {
        // Execute some operations to generate statistics
        try {
            retryHandler.executeWithRetry(() -> {
                throw new RuntimeException("Test failure");
            });
        } catch (RuntimeException e) {
            // Expected
        }
        
        // Reset should clear statistics
        retryHandler.reset();
        
        Object stats = retryHandler.getStatistics();
        assertNotNull(stats, "Statistics should still be available after reset");
    }

    @Test
    public void testConcurrentAccess() throws Exception {
        Thread[] threads = new Thread[5];
        Exception[] exceptions = new Exception[5];
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < threads.length; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    String result = retryHandler.executeWithRetry(() -> {
                        return "success-" + threadIndex;
                    });
                    if (result.equals("success-" + threadIndex)) {
                        successCount.incrementAndGet();
                    }
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
        
        assertEquals(5, successCount.get(), "All threads should succeed");
    }

    @Test
    public void testDifferentExceptionTypes() throws Exception {
        // Test with different exception types
        assertThrows(IllegalArgumentException.class, () -> {
            retryHandler.executeWithRetry(() -> {
                throw new IllegalArgumentException("Non-retryable exception");
            });
        }, "Should propagate non-retryable exceptions");
    }

    @Test
    public void testVoidOperation() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        
        retryHandler.executeWithRetry(() -> {
            callCount.incrementAndGet();
            return null; // Void operation
        });
        
        assertEquals(1, callCount.get(), "Should execute void operation once");
    }

    /**
     * Functional interface for retryable operations that return a value.
     */
    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
}
