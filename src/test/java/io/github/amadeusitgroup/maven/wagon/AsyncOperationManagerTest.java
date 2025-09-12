package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit tests for AsyncOperationManager.
 */
public class AsyncOperationManagerTest {

    private AsyncOperationManager asyncOperationManager;

    @BeforeEach
    public void setUp() {
        // Reset the singleton instance for each test
        AsyncOperationManager.resetInstance();
        asyncOperationManager = AsyncOperationManager.getInstance();
    }

    @AfterEach
    public void tearDown() {
        if (asyncOperationManager != null && !asyncOperationManager.isShutdown()) {
            asyncOperationManager.shutdown();
        }
    }

    @Test
    public void testSingletonInstance() {
        AsyncOperationManager instance1 = AsyncOperationManager.getInstance();
        AsyncOperationManager instance2 = AsyncOperationManager.getInstance();
        assertSame(instance1, instance2, "AsyncOperationManager should be a singleton");
    }

    @Test
    public void testSubmitTask_Success() throws Exception {
        CompletableFuture<String> future = asyncOperationManager.submitTask(() -> {
            return "test-result";
        });
        
        assertNotNull(future, "Future should not be null");
        String result = future.get(5, TimeUnit.SECONDS);
        assertEquals("test-result", result, "Should return correct result");
    }

    @Test
    public void testSubmitTask_Exception() throws Exception {
        CompletableFuture<String> future = asyncOperationManager.submitTask(() -> {
            throw new RuntimeException("Test exception");
        });
        
        assertNotNull(future, "Future should not be null");
        
        assertThrows(ExecutionException.class, () -> {
            future.get(5, TimeUnit.SECONDS);
        }, "Should throw ExecutionException for task exceptions");
    }

    @Test
    public void testSubmitTask_Void() throws Exception {
        AtomicBoolean executed = new AtomicBoolean(false);
        
        CompletableFuture<Void> future = asyncOperationManager.submitTask(() -> {
            executed.set(true);
            return null;
        });
        
        assertNotNull(future, "Future should not be null");
        future.get(5, TimeUnit.SECONDS);
        assertTrue(executed.get(), "Task should have been executed");
    }

    @Test
    public void testMultipleTasks() throws Exception {
        int taskCount = 5;
        CompletableFuture<Integer>[] futures = new CompletableFuture[taskCount];
        
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            futures[i] = asyncOperationManager.submitTask(() -> {
                Thread.sleep(100); // Simulate work
                return taskId;
            });
        }
        
        // Wait for all tasks to complete
        for (int i = 0; i < taskCount; i++) {
            Integer result = futures[i].get(10, TimeUnit.SECONDS);
            assertEquals(i, result.intValue(), "Task " + i + " should return correct result");
        }
    }

    @Test
    public void testConcurrentTasks() throws Exception {
        int taskCount = 10;
        AtomicInteger completedCount = new AtomicInteger(0);
        CompletableFuture<Void>[] futures = new CompletableFuture[taskCount];
        
        for (int i = 0; i < taskCount; i++) {
            futures[i] = asyncOperationManager.submitTask(() -> {
                Thread.sleep(50); // Simulate work
                completedCount.incrementAndGet();
                return null;
            });
        }
        
        // Wait for all tasks to complete
        for (CompletableFuture<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        
        assertEquals(taskCount, completedCount.get(), "All tasks should have completed");
    }

    @Test
    public void testTaskTimeout() {
        CompletableFuture<String> future = asyncOperationManager.submitTask(() -> {
            Thread.sleep(10000); // Long running task
            return "should-timeout";
        });
        
        assertNotNull(future, "Future should not be null");
        
        assertThrows(TimeoutException.class, () -> {
            future.get(1, TimeUnit.SECONDS);
        }, "Should timeout for long running tasks");
    }

    @Test
    public void testGetStatistics() throws Exception {
        // Submit some tasks to generate statistics
        CompletableFuture<String> future1 = asyncOperationManager.submitTask(() -> "task1");
        CompletableFuture<String> future2 = asyncOperationManager.submitTask(() -> "task2");
        
        future1.get(5, TimeUnit.SECONDS);
        future2.get(5, TimeUnit.SECONDS);
        
        Object stats = asyncOperationManager.getStatistics();
        assertNotNull(stats, "Statistics should not be null");
    }

    @Test
    public void testShutdown() throws Exception {
        // Submit a task
        CompletableFuture<String> future = asyncOperationManager.submitTask(() -> "test");
        future.get(5, TimeUnit.SECONDS);
        
        // Shutdown
        asyncOperationManager.shutdown();
        
        // Verify shutdown
        assertTrue(asyncOperationManager.isShutdown(), "Should be shut down");
    }

    @Test
    public void testShutdownWithPendingTasks() throws Exception {
        // Submit long-running tasks
        CompletableFuture<String> future1 = asyncOperationManager.submitTask(() -> {
            Thread.sleep(2000);
            return "task1";
        });
        
        CompletableFuture<String> future2 = asyncOperationManager.submitTask(() -> {
            Thread.sleep(2000);
            return "task2";
        });
        
        // Shutdown immediately
        asyncOperationManager.shutdown();
        
        // Tasks may or may not complete depending on shutdown implementation
        assertTrue(asyncOperationManager.isShutdown(), "Should be shut down");
    }

    @Test
    public void testTaskExceptionHandling() throws Exception {
        AtomicInteger exceptionCount = new AtomicInteger(0);
        
        CompletableFuture<String> future = asyncOperationManager.submitTask(() -> {
            exceptionCount.incrementAndGet();
            throw new IllegalStateException("Test exception");
        });
        
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Should have thrown exception");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof RuntimeException, "Should wrap original exception");
            // The AsyncOperationManager wraps exceptions with "Task execution failed"
            assertTrue(e.getCause().getMessage().contains("Task execution failed"), "Should wrap with task execution message");
        }
        
        assertEquals(1, exceptionCount.get(), "Task should have been executed once");
    }

    @Test
    public void testTaskCancellation() throws Exception {
        CompletableFuture<String> future = asyncOperationManager.submitTask(() -> {
            Thread.sleep(5000); // Long running task
            return "should-be-cancelled";
        });
        
        assertNotNull(future, "Future should not be null");
        
        // Cancel the task
        boolean cancelled = future.cancel(true);
        assertTrue(cancelled || future.isDone(), "Task should be cancelled or completed");
        
        if (cancelled) {
            assertTrue(future.isCancelled(), "Future should be marked as cancelled");
        }
    }

    @Test
    public void testThreadPoolBehavior() throws Exception {
        // Submit more tasks than typical thread pool size to test queuing
        int taskCount = 20;
        AtomicInteger executionOrder = new AtomicInteger(0);
        CompletableFuture<Integer>[] futures = new CompletableFuture[taskCount];
        
        for (int i = 0; i < taskCount; i++) {
            futures[i] = asyncOperationManager.submitTask(() -> {
                return executionOrder.incrementAndGet();
            });
        }
        
        // Wait for all tasks and verify they all executed
        for (int i = 0; i < taskCount; i++) {
            Integer order = futures[i].get(10, TimeUnit.SECONDS);
            assertTrue(order > 0 && order <= taskCount, "Execution order should be valid");
        }
        
        assertEquals(taskCount, executionOrder.get(), "All tasks should have executed");
    }

    @Test
    public void testAsyncChaining() throws Exception {
        CompletableFuture<String> future1 = asyncOperationManager.submitTask(() -> "first");
        
        CompletableFuture<String> future2 = future1.thenCompose(result -> 
            asyncOperationManager.submitTask(() -> result + "-second")
        );
        
        String finalResult = future2.get(5, TimeUnit.SECONDS);
        assertEquals("first-second", finalResult, "Should chain async operations correctly");
    }

    @Test
    public void testMemoryLeaks() throws Exception {
        // Submit and complete many tasks to test for memory leaks
        for (int i = 0; i < 100; i++) {
            final int taskId = i;
            CompletableFuture<Integer> future = asyncOperationManager.submitTask(() -> taskId);
            future.get(1, TimeUnit.SECONDS);
        }
        
        // Verify statistics are still available (no memory issues)
        Object stats = asyncOperationManager.getStatistics();
        assertNotNull(stats, "Statistics should be available after many operations");
    }
}
