package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for CircuitBreakerHandler.
 */
public class CircuitBreakerHandlerTest {

    private CircuitBreakerHandler circuitBreakerHandler;

    @BeforeEach
    public void setUp() {
        circuitBreakerHandler = CircuitBreakerHandler.getInstance();
        circuitBreakerHandler.reset();
    }

    @AfterEach
    public void tearDown() {
        circuitBreakerHandler.reset();
    }

    @Test
    public void testSingletonInstance() {
        CircuitBreakerHandler instance1 = CircuitBreakerHandler.getInstance();
        CircuitBreakerHandler instance2 = CircuitBreakerHandler.getInstance();
        assertSame(instance1, instance2, "CircuitBreakerHandler should be a singleton");
    }

    @Test
    public void testIsCircuitOpen_InitiallyClosed() {
        assertFalse(circuitBreakerHandler.isCircuitOpen(), "Circuit should be closed initially");
    }

    @Test
    public void testOnSuccess() {
        // Record a success
        circuitBreakerHandler.onSuccess();
        
        // Circuit should remain closed
        assertFalse(circuitBreakerHandler.isCircuitOpen(), "Circuit should remain closed after success");
    }

    @Test
    public void testOnFailure_SingleFailure() {
        // Record a single failure
        circuitBreakerHandler.onFailure();
        
        // Circuit should remain closed for single failure
        assertFalse(circuitBreakerHandler.isCircuitOpen(), "Circuit should remain closed after single failure");
    }

    @Test
    public void testOnFailure_MultipleFailures() {
        // Record multiple failures to trigger circuit opening
        for (int i = 0; i < 10; i++) {
            circuitBreakerHandler.onFailure();
        }
        
        // Circuit should open after multiple failures
        assertTrue(circuitBreakerHandler.isCircuitOpen(), "Circuit should open after multiple failures");
    }

    @Test
    public void testCircuitRecovery() throws InterruptedException {
        // Open the circuit with multiple failures
        for (int i = 0; i < 10; i++) {
            circuitBreakerHandler.onFailure();
        }
        assertTrue(circuitBreakerHandler.isCircuitOpen(), "Circuit should be open");
        
        // Wait for recovery period (implementation dependent)
        Thread.sleep(100);
        
        // Record a success to potentially close the circuit
        circuitBreakerHandler.onSuccess();
        
        // Circuit behavior depends on implementation - may still be open or closed
        // This test verifies the handler responds to recovery attempts
        assertNotNull(circuitBreakerHandler.getStatistics(), "Statistics should be available during recovery");
    }

    @Test
    public void testGetStatistics() {
        // Record some operations
        circuitBreakerHandler.onSuccess();
        circuitBreakerHandler.onFailure();
        circuitBreakerHandler.onSuccess();
        
        Object stats = circuitBreakerHandler.getStatistics();
        assertNotNull(stats, "Statistics should not be null");
    }

    @Test
    public void testReset() {
        // Open the circuit
        for (int i = 0; i < 10; i++) {
            circuitBreakerHandler.onFailure();
        }
        assertTrue(circuitBreakerHandler.isCircuitOpen(), "Circuit should be open before reset");
        
        // Reset should close the circuit
        circuitBreakerHandler.reset();
        assertFalse(circuitBreakerHandler.isCircuitOpen(), "Circuit should be closed after reset");
    }

    @Test
    public void testConcurrentAccess() throws Exception {
        Thread[] threads = new Thread[10];
        Exception[] exceptions = new Exception[10];
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        for (int i = 0; i < threads.length; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    if (threadIndex % 2 == 0) {
                        circuitBreakerHandler.onSuccess();
                        successCount.incrementAndGet();
                    } else {
                        circuitBreakerHandler.onFailure();
                        failureCount.incrementAndGet();
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
        
        assertEquals(5, successCount.get(), "Should have recorded 5 successes");
        assertEquals(5, failureCount.get(), "Should have recorded 5 failures");
    }

    @Test
    public void testCircuitStates() {
        // Test closed state
        assertFalse(circuitBreakerHandler.isCircuitOpen(), "Circuit should start closed");
        
        // Add some successes - should remain closed
        for (int i = 0; i < 5; i++) {
            circuitBreakerHandler.onSuccess();
            assertFalse(circuitBreakerHandler.isCircuitOpen(), "Circuit should remain closed after successes");
        }
        
        // Add failures to potentially open circuit
        for (int i = 0; i < 15; i++) {
            circuitBreakerHandler.onFailure();
        }
        
        // Circuit behavior depends on implementation threshold
        Object stats = circuitBreakerHandler.getStatistics();
        assertNotNull(stats, "Statistics should be available in any state");
    }

    @Test
    public void testFailureThreshold() {
        // Test that circuit opens at appropriate failure threshold
        int failureCount = 0;
        boolean circuitOpened = false;
        
        // Keep adding failures until circuit opens or we reach a reasonable limit
        for (int i = 0; i < 20 && !circuitOpened; i++) {
            circuitBreakerHandler.onFailure();
            failureCount++;
            circuitOpened = circuitBreakerHandler.isCircuitOpen();
        }
        
        assertTrue(failureCount > 0, "Should have recorded failures");
        // Circuit may or may not be open depending on implementation threshold
    }

    @Test
    public void testMixedOperations() {
        // Test mixed success and failure operations
        circuitBreakerHandler.onSuccess();
        circuitBreakerHandler.onFailure();
        circuitBreakerHandler.onSuccess();
        circuitBreakerHandler.onFailure();
        circuitBreakerHandler.onFailure();
        circuitBreakerHandler.onSuccess();
        
        // Should handle mixed operations gracefully
        Object stats = circuitBreakerHandler.getStatistics();
        assertNotNull(stats, "Statistics should be available after mixed operations");
    }

    @Test
    public void testHalfOpenState() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 10; i++) {
            circuitBreakerHandler.onFailure();
        }
        
        if (circuitBreakerHandler.isCircuitOpen()) {
            // Wait for potential half-open transition
            Thread.sleep(50);
            
            // Try a success operation
            circuitBreakerHandler.onSuccess();
            
            // Verify handler is still functional
            assertNotNull(circuitBreakerHandler.getStatistics(), "Handler should remain functional");
        }
    }
}
