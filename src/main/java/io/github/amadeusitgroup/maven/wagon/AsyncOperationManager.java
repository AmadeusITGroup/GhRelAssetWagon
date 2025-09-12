package io.github.amadeusitgroup.maven.wagon;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages asynchronous operations for non-blocking uploads and downloads.
 */
public class AsyncOperationManager {
    
    private static final int DEFAULT_THREAD_POOL_SIZE = 4;
    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 30000; // 30 seconds
    
    private final ExecutorService executorService;
    private final BlockingQueue<Runnable> taskQueue;
    private final AtomicInteger activeOperations;
    private final AtomicLong totalOperations;
    private final AtomicLong completedOperations;
    private final AtomicLong failedOperations;
    
    private static volatile AsyncOperationManager instance;
    
    private AsyncOperationManager() {
        this.taskQueue = new LinkedBlockingQueue<>(DEFAULT_QUEUE_CAPACITY);
        this.executorService = new ThreadPoolExecutor(
            DEFAULT_THREAD_POOL_SIZE,
            DEFAULT_THREAD_POOL_SIZE,
            60L, TimeUnit.SECONDS,
            taskQueue,
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "GhRelAssetWagon-Async-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
        
        this.activeOperations = new AtomicInteger(0);
        this.totalOperations = new AtomicLong(0);
        this.completedOperations = new AtomicLong(0);
        this.failedOperations = new AtomicLong(0);
    }
    
    /**
     * Get singleton instance of async operation manager
     */
    public static AsyncOperationManager getInstance() {
        if (instance == null) {
            synchronized (AsyncOperationManager.class) {
                if (instance == null) {
                    instance = new AsyncOperationManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Reset singleton instance for testing
     */
    public static void resetInstance() {
        synchronized (AsyncOperationManager.class) {
            if (instance != null && !instance.isShutdown()) {
                instance.shutdown();
            }
            instance = null;
        }
    }
    
    /**
     * Submit asynchronous download operation
     */
    public CompletableFuture<File> downloadAsync(String resourceName, File destination, 
                                               DownloadOperation downloadOperation) {
        totalOperations.incrementAndGet();
        activeOperations.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                File result = downloadOperation.execute(resourceName, destination);
                completedOperations.incrementAndGet();
                return result;
            } catch (Exception e) {
                failedOperations.incrementAndGet();
                throw new RuntimeException("Download failed for: " + resourceName, e);
            } finally {
                activeOperations.decrementAndGet();
            }
        }, executorService);
    }
    
    /**
     * Submit asynchronous upload operation
     */
    public CompletableFuture<Void> uploadAsync(File source, String destination,
                                             UploadOperation uploadOperation) {
        totalOperations.incrementAndGet();
        activeOperations.incrementAndGet();
        
        return CompletableFuture.runAsync(() -> {
            try {
                uploadOperation.execute(source, destination);
                completedOperations.incrementAndGet();
            } catch (Exception e) {
                failedOperations.incrementAndGet();
                throw new RuntimeException("Upload failed for: " + source.getName(), e);
            } finally {
                activeOperations.decrementAndGet();
            }
        }, executorService);
    }
    
    /**
     * Submit batch upload operations
     */
    public CompletableFuture<Void> uploadBatchAsync(BatchUploadOperation batchOperation) {
        totalOperations.incrementAndGet();
        activeOperations.incrementAndGet();
        
        return CompletableFuture.runAsync(() -> {
            try {
                batchOperation.execute();
                completedOperations.incrementAndGet();
            } catch (Exception e) {
                failedOperations.incrementAndGet();
                throw new RuntimeException("Batch upload failed", e);
            } finally {
                activeOperations.decrementAndGet();
            }
        }, executorService);
    }
    
    /**
     * Submit parallel directory operations
     */
    public CompletableFuture<Void> processDirectoryAsync(File directory, String baseDestination,
                                                       DirectoryOperation directoryOperation) {
        totalOperations.incrementAndGet();
        activeOperations.incrementAndGet();
        
        return CompletableFuture.runAsync(() -> {
            try {
                directoryOperation.execute(directory, baseDestination);
                completedOperations.incrementAndGet();
            } catch (Exception e) {
                failedOperations.incrementAndGet();
                throw new RuntimeException("Directory operation failed for: " + directory.getName(), e);
            } finally {
                activeOperations.decrementAndGet();
            }
        }, executorService);
    }
    
    /**
     * Wait for all active operations to complete
     */
    public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        
        while (activeOperations.get() > 0) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= timeoutMs) {
                return false;
            }
            
            Thread.sleep(100); // Check every 100ms
        }
        
        return true;
    }
    
    /**
     * Submit a generic task for async execution
     */
    public <T> CompletableFuture<T> submitTask(Callable<T> task) {
        totalOperations.incrementAndGet();
        activeOperations.incrementAndGet();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                T result = task.call();
                completedOperations.incrementAndGet();
                return result;
            } catch (Exception e) {
                failedOperations.incrementAndGet();
                throw new RuntimeException("Task execution failed", e);
            } finally {
                activeOperations.decrementAndGet();
            }
        }, executorService);
    }
    
    /**
     * Check if the manager is shut down
     */
    public boolean isShutdown() {
        return executorService.isShutdown();
    }
    
    /**
     * Get statistics for monitoring
     */
    public Object getStatistics() {
        OperationStats stats = getStats();
        return stats.toString();
    }
    
    /**
     * Get current operation statistics
     */
    public OperationStats getStats() {
        return new OperationStats(
            totalOperations.get(),
            completedOperations.get(),
            failedOperations.get(),
            activeOperations.get(),
            taskQueue.size()
        );
    }
    
    /**
     * Shutdown the async operation manager
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Interface for download operations
     */
    @FunctionalInterface
    public interface DownloadOperation {
        File execute(String resourceName, File destination) throws IOException;
    }
    
    /**
     * Interface for upload operations
     */
    @FunctionalInterface
    public interface UploadOperation {
        void execute(File source, String destination) throws IOException;
    }
    
    /**
     * Interface for batch upload operations
     */
    @FunctionalInterface
    public interface BatchUploadOperation {
        void execute() throws IOException;
    }
    
    /**
     * Interface for directory operations
     */
    @FunctionalInterface
    public interface DirectoryOperation {
        void execute(File directory, String baseDestination) throws IOException;
    }
    
    /**
     * Operation statistics for monitoring
     */
    public static class OperationStats {
        private final long totalOperations;
        private final long completedOperations;
        private final long failedOperations;
        private final int activeOperations;
        private final int queuedOperations;
        
        public OperationStats(long totalOperations, long completedOperations, long failedOperations,
                            int activeOperations, int queuedOperations) {
            this.totalOperations = totalOperations;
            this.completedOperations = completedOperations;
            this.failedOperations = failedOperations;
            this.activeOperations = activeOperations;
            this.queuedOperations = queuedOperations;
        }
        
        public long getTotalOperations() { return totalOperations; }
        public long getCompletedOperations() { return completedOperations; }
        public long getFailedOperations() { return failedOperations; }
        public int getActiveOperations() { return activeOperations; }
        public int getQueuedOperations() { return queuedOperations; }
        
        public double getSuccessRate() {
            if (totalOperations == 0) return 0.0;
            return (double) completedOperations / totalOperations;
        }
        
        public double getFailureRate() {
            if (totalOperations == 0) return 0.0;
            return (double) failedOperations / totalOperations;
        }
        
        @Override
        public String toString() {
            return String.format("AsyncStats{total=%d, completed=%d, failed=%d, active=%d, queued=%d, success=%.1f%%}", 
                totalOperations, completedOperations, failedOperations, activeOperations, queuedOperations,
                getSuccessRate() * 100);
        }
    }
}
