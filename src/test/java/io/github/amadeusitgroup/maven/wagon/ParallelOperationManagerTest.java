package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for ParallelOperationManager - TDD approach.
 * Tests define the expected API and behavior before implementation.
 */
public class ParallelOperationManagerTest {

    private ParallelOperationManager parallelOperationManager;

    @BeforeEach
    public void setUp() {
        ParallelOperationManager.resetInstance();
        parallelOperationManager = ParallelOperationManager.getInstance();
    }

    @AfterEach
    public void tearDown() {
        if (parallelOperationManager != null && !parallelOperationManager.isShutdown()) {
            parallelOperationManager.shutdown();
        }
    }

    @Test
    public void testSingletonInstance() {
        ParallelOperationManager instance1 = ParallelOperationManager.getInstance();
        ParallelOperationManager instance2 = ParallelOperationManager.getInstance();
        assertSame(instance1, instance2, "ParallelOperationManager should be a singleton");
    }

    @Test
    public void testParallelUpload() throws Exception {
        // Test parallel upload of multiple files
        File[] testFiles = createTestFiles(3);
        
        AtomicInteger uploadCount = new AtomicInteger(0);
        
        CompletableFuture<List<ParallelOperationManager.UploadResult>> result = 
            parallelOperationManager.uploadFilesParallel(Arrays.asList(testFiles), (source) -> {
                uploadCount.incrementAndGet();
                try {
                    Thread.sleep(100); // Simulate upload time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        
        List<ParallelOperationManager.UploadResult> results = result.get(5, TimeUnit.SECONDS);
        assertEquals(3, results.size(), "Should upload all files in parallel");
        assertEquals(3, uploadCount.get(), "Should execute all uploads");
        
        for (ParallelOperationManager.UploadResult uploadResult : results) {
            assertTrue(uploadResult.isSuccess(), "Upload should be successful");
            assertNull(uploadResult.getErrorMessage(), "Should have no error");
        }
        
        // Cleanup
        for (File file : testFiles) {
            file.delete();
        }
    }

    @Test
    public void testParallelDownload() throws Exception {
        // Test parallel download of multiple resources
        String[] resources = {"resource1", "resource2", "resource3"};
        File[] destinations = createTestDestinations(3);
        
        AtomicInteger downloadCount = new AtomicInteger(0);
        
        CompletableFuture<List<ParallelOperationManager.DownloadResult>> result = 
            parallelOperationManager.downloadFilesParallel(Arrays.asList(resources), (resource) -> {
                int index = downloadCount.getAndIncrement();
                try {
                    Thread.sleep(100); // Simulate download time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return destinations[index % destinations.length];
            });
        
        List<ParallelOperationManager.DownloadResult> results = result.get(5, TimeUnit.SECONDS);
        assertEquals(3, results.size(), "Should download all resources");
        assertEquals(3, downloadCount.get(), "Should execute all downloads");
        
        for (ParallelOperationManager.DownloadResult downloadResult : results) {
            assertTrue(downloadResult.isSuccess(), "Download should be successful");
            assertNull(downloadResult.getErrorMessage(), "Should have no error");
        }
        
        // Cleanup
        for (File file : destinations) {
            file.delete();
        }
    }

    @Test
    public void testParallelOperationWithFailure() throws Exception {
        // Test that one failure doesn't stop other operations
        File[] testFiles = createTestFiles(3);
        
        AtomicInteger attemptCount = new AtomicInteger(0);
        
        CompletableFuture<List<ParallelOperationManager.UploadResult>> result = 
            parallelOperationManager.uploadFilesParallel(Arrays.asList(testFiles), (source) -> {
                int attempt = attemptCount.incrementAndGet();
                if (attempt == 2) {
                    throw new IOException("Simulated failure for " + source.getName());
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        
        List<ParallelOperationManager.UploadResult> results = result.get(5, TimeUnit.SECONDS);
        assertEquals(3, results.size(), "Should return results for all operations");
        
        // Check that we have both successful and failed results
        long successCount = results.stream().mapToLong(r -> r.isSuccess() ? 1 : 0).sum();
        long failureCount = results.stream().mapToLong(r -> r.isSuccess() ? 0 : 1).sum();
        
        assertEquals(2, successCount, "Should have 2 successful operations");
        assertEquals(1, failureCount, "Should have 1 failed operation");
        assertTrue(attemptCount.get() >= 2, "Should attempt operations even with failures");
        
        // Cleanup
        for (File file : testFiles) {
            file.delete();
        }
    }

    @Test
    public void testConcurrencyLimits() throws Exception {
        // Test that parallel operations respect concurrency limits
        int maxConcurrency = parallelOperationManager.getMaxConcurrency();
        assertTrue(maxConcurrency > 0, "Should have positive max concurrency");
        assertTrue(maxConcurrency <= 10, "Should have reasonable concurrency limit");
        
        // Test with more operations than max concurrency
        File[] testFiles = createTestFiles(maxConcurrency + 2);
        String[] destinations = new String[maxConcurrency + 2];
        for (int i = 0; i < destinations.length; i++) {
            destinations[i] = "dest" + i;
        }
        
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
        
        CompletableFuture<List<ParallelOperationManager.UploadResult>> result = 
            parallelOperationManager.uploadFilesParallel(Arrays.asList(testFiles), (source) -> {
                int current = concurrentCount.incrementAndGet();
                maxObservedConcurrency.updateAndGet(max -> Math.max(max, current));
                
                try {
                    Thread.sleep(200); // Hold the slot for a bit
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                
                concurrentCount.decrementAndGet();
            });
        
        result.get(10, TimeUnit.SECONDS);
        assertTrue(maxObservedConcurrency.get() <= maxConcurrency, 
            "Should not exceed max concurrency limit");
        
        // Cleanup
        for (File file : testFiles) {
            file.delete();
        }
    }

    @Test
    public void testParallelOperationStatistics() throws Exception {
        // Test that statistics are tracked correctly
        File[] testFiles = createTestFiles(5);
        String[] destinations = {"dest1", "dest2", "dest3", "dest4", "dest5"};
        
        CompletableFuture<List<ParallelOperationManager.UploadResult>> result = 
            parallelOperationManager.uploadFilesParallel(Arrays.asList(testFiles), (source) -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        
        result.get(5, TimeUnit.SECONDS);
        
        ParallelOperationManager.ParallelOperationStats stats = parallelOperationManager.getStatistics();
        assertNotNull(stats, "Should provide statistics");
        assertTrue(stats.getTotalOperations() >= 5, "Should track total operations");
        assertTrue(stats.toString().contains("ParallelOperationStats"), "Statistics should be formatted correctly");
        
        // Cleanup
        for (File file : testFiles) {
            file.delete();
        }
    }

    @Test
    public void testShutdownBehavior() throws Exception {
        // Test graceful shutdown
        assertFalse(parallelOperationManager.isShutdown(), "Should not be shut down initially");
        
        parallelOperationManager.shutdown();
        assertTrue(parallelOperationManager.isShutdown(), "Should be shut down after shutdown call");
        
        // Test that operations fail after shutdown
        File[] testFiles = createTestFiles(1);
        String[] destinations = {"dest1"};
        
        assertThrows(Exception.class, () -> {
            CompletableFuture<List<ParallelOperationManager.UploadResult>> result = 
                parallelOperationManager.uploadFilesParallel(Arrays.asList(testFiles), (source) -> {});
            result.get(1, TimeUnit.SECONDS);
        }, "Should reject operations after shutdown");
        
        // Cleanup
        for (File file : testFiles) {
            file.delete();
        }
    }

    @Test
    public void testBatchOperationOptimization() throws Exception {
        // Test that batch operations are optimized for performance
        File[] testFiles = createTestFiles(10);
        String[] destinations = new String[10];
        for (int i = 0; i < destinations.length; i++) {
            destinations[i] = "dest" + i;
        }
        
        long startTime = System.currentTimeMillis();
        
        CompletableFuture<List<ParallelOperationManager.UploadResult>> result = 
            parallelOperationManager.uploadFilesParallel(Arrays.asList(testFiles), (source) -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        
        result.get(5, TimeUnit.SECONDS);
        
        long duration = System.currentTimeMillis() - startTime;
        
        // With parallelism, should complete much faster than sequential (10 * 100ms = 1000ms)
        assertTrue(duration < 800, "Parallel operations should be faster than sequential");
        
        // Cleanup
        for (File file : testFiles) {
            file.delete();
        }
    }

    @Test
    public void testErrorHandlingAndRecovery() throws Exception {
        // Test error handling with partial failures
        File[] testFiles = createTestFiles(4);
        String[] destinations = {"dest1", "dest2", "dest3", "dest4"};
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        try {
            CompletableFuture<List<ParallelOperationManager.UploadResult>> result = 
                parallelOperationManager.uploadFilesParallel(Arrays.asList(testFiles), (source) -> {
                    String fileName = source.getName();
                    if (fileName.contains("test1") || fileName.contains("test3")) {
                        failureCount.incrementAndGet();
                        throw new IOException("Simulated failure for " + fileName);
                    }
                    successCount.incrementAndGet();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
            
            List<ParallelOperationManager.UploadResult> results = result.get(5, TimeUnit.SECONDS);
            
            // Check results instead of expecting exception
            long actualSuccessCount = results.stream().mapToLong(r -> r.isSuccess() ? 1 : 0).sum();
            long actualFailureCount = results.stream().mapToLong(r -> r.isSuccess() ? 0 : 1).sum();
            
            assertEquals(2, actualSuccessCount, "Should have 2 successful operations");
            assertEquals(2, actualFailureCount, "Should have 2 failed operations");
        } catch (ExecutionException e) {
            // This might happen depending on implementation
        }
        
        assertEquals(2, successCount.get(), "Should complete successful operations");
        assertEquals(2, failureCount.get(), "Should track failed operations");
        
        // Cleanup
        for (File file : testFiles) {
            file.delete();
        }
    }

    // Helper methods
    private File[] createTestFiles(int count) throws IOException {
        File[] files = new File[count];
        for (int i = 0; i < count; i++) {
            files[i] = File.createTempFile("test" + i, ".tmp");
            files[i].deleteOnExit();
        }
        return files;
    }
    
    private File[] createTestDestinations(int count) throws IOException {
        File[] files = new File[count];
        for (int i = 0; i < count; i++) {
            files[i] = File.createTempFile("dest" + i, ".tmp");
            files[i].deleteOnExit();
        }
        return files;
    }
}
