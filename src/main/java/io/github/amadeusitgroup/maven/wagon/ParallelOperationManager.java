package io.github.amadeusitgroup.maven.wagon;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages parallel operations for uploads and downloads with concurrency control.
 * Implements singleton pattern with thread-safe operations.
 */
public class ParallelOperationManager {
    
    private static volatile ParallelOperationManager instance;
    private static final Object lock = new Object();
    
    private final ExecutorService executorService;
    private final Semaphore concurrencyLimiter;
    private final Map<String, Future<?>> activeTasks;
    private final AtomicInteger maxConcurrency;
    private final AtomicInteger activeOperations;
    private final AtomicLong totalOperations;
    private final AtomicLong successfulOperations;
    private final AtomicLong failedOperations;
    private final AtomicLong totalBytesProcessed;
    private volatile boolean shutdown;
    
    private ParallelOperationManager() {
        this.maxConcurrency = new AtomicInteger(5); // Default max concurrency
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ParallelOperation-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        this.concurrencyLimiter = new Semaphore(maxConcurrency.get());
        this.activeTasks = new ConcurrentHashMap<>();
        this.activeOperations = new AtomicInteger(0);
        this.totalOperations = new AtomicLong(0);
        this.successfulOperations = new AtomicLong(0);
        this.failedOperations = new AtomicLong(0);
        this.totalBytesProcessed = new AtomicLong(0);
        this.shutdown = false;
    }
    
    public static ParallelOperationManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new ParallelOperationManager();
                }
            }
        }
        return instance;
    }
    
    public static void resetInstance() {
        synchronized (lock) {
            if (instance != null) {
                instance.shutdown();
                instance = null;
            }
        }
    }
    
    /**
     * Uploads multiple files in parallel.
     */
    public CompletableFuture<List<UploadResult>> uploadFilesParallel(List<File> files, UploadHandler handler) {
        if (shutdown) {
            throw new IllegalStateException("ParallelOperationManager is shutdown");
        }
        
        List<CompletableFuture<UploadResult>> futures = new ArrayList<>();
        
        for (File file : files) {
            CompletableFuture<UploadResult> future = CompletableFuture.supplyAsync(() -> {
                String taskId = "upload-" + file.getName() + "-" + System.currentTimeMillis();
                
                try {
                    concurrencyLimiter.acquire();
                    activeOperations.incrementAndGet();
                    totalOperations.incrementAndGet();
                    
                    activeTasks.put(taskId, CompletableFuture.completedFuture(null));
                    
                    long startTime = System.currentTimeMillis();
                    handler.upload(file);
                    long duration = System.currentTimeMillis() - startTime;
                    
                    successfulOperations.incrementAndGet();
                    totalBytesProcessed.addAndGet(file.length());
                    
                    return new UploadResult(file.getName(), true, null, duration, file.length());
                    
                } catch (Exception e) {
                    failedOperations.incrementAndGet();
                    return new UploadResult(file.getName(), false, e.getMessage(), 0, file.length());
                } finally {
                    activeOperations.decrementAndGet();
                    concurrencyLimiter.release();
                    activeTasks.remove(taskId);
                }
            }, executorService);
            
            futures.add(future);
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll));
    }
    
    /**
     * Downloads multiple files in parallel.
     */
    public CompletableFuture<List<DownloadResult>> downloadFilesParallel(List<String> fileNames, DownloadHandler handler) {
        if (shutdown) {
            throw new IllegalStateException("ParallelOperationManager is shutdown");
        }
        
        List<CompletableFuture<DownloadResult>> futures = new ArrayList<>();
        
        for (String fileName : fileNames) {
            CompletableFuture<DownloadResult> future = CompletableFuture.supplyAsync(() -> {
                String taskId = "download-" + fileName + "-" + System.currentTimeMillis();
                
                try {
                    concurrencyLimiter.acquire();
                    activeOperations.incrementAndGet();
                    totalOperations.incrementAndGet();
                    
                    activeTasks.put(taskId, CompletableFuture.completedFuture(null));
                    
                    long startTime = System.currentTimeMillis();
                    File downloadedFile = handler.download(fileName);
                    long duration = System.currentTimeMillis() - startTime;
                    
                    successfulOperations.incrementAndGet();
                    long fileSize = downloadedFile != null ? downloadedFile.length() : 0;
                    totalBytesProcessed.addAndGet(fileSize);
                    
                    return new DownloadResult(fileName, true, null, duration, fileSize, downloadedFile);
                    
                } catch (Exception e) {
                    failedOperations.incrementAndGet();
                    return new DownloadResult(fileName, false, e.getMessage(), 0, 0, null);
                } finally {
                    activeOperations.decrementAndGet();
                    concurrencyLimiter.release();
                    activeTasks.remove(taskId);
                }
            }, executorService);
            
            futures.add(future);
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll));
    }
    
    /**
     * Uploads files in batches with optimized concurrency.
     */
    public CompletableFuture<List<UploadResult>> uploadBatch(List<File> files, UploadHandler handler, int batchSize) {
        if (shutdown) {
            throw new IllegalStateException("ParallelOperationManager is shutdown");
        }
        
        List<CompletableFuture<List<UploadResult>>> batchFutures = new ArrayList<>();
        
        for (int i = 0; i < files.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, files.size());
            List<File> batch = files.subList(i, endIndex);
            
            CompletableFuture<List<UploadResult>> batchFuture = uploadFilesParallel(batch, handler);
            batchFutures.add(batchFuture);
        }
        
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> batchFutures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll));
    }
    
    /**
     * Downloads files in batches with optimized concurrency.
     */
    public CompletableFuture<List<DownloadResult>> downloadBatch(List<String> fileNames, DownloadHandler handler, int batchSize) {
        if (shutdown) {
            throw new IllegalStateException("ParallelOperationManager is shutdown");
        }
        
        List<CompletableFuture<List<DownloadResult>>> batchFutures = new ArrayList<>();
        
        for (int i = 0; i < fileNames.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, fileNames.size());
            List<String> batch = fileNames.subList(i, endIndex);
            
            CompletableFuture<List<DownloadResult>> batchFuture = downloadFilesParallel(batch, handler);
            batchFutures.add(batchFuture);
        }
        
        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> batchFutures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll));
    }
    
    /**
     * Cancels a specific operation by task ID.
     */
    public boolean cancelOperation(String taskId) {
        Future<?> task = activeTasks.get(taskId);
        if (task != null) {
            boolean cancelled = task.cancel(true);
            if (cancelled) {
                activeTasks.remove(taskId);
            }
            return cancelled;
        }
        return false;
    }
    
    /**
     * Cancels all active operations.
     */
    public int cancelAllOperations() {
        int cancelledCount = 0;
        for (Map.Entry<String, Future<?>> entry : activeTasks.entrySet()) {
            if (entry.getValue().cancel(true)) {
                cancelledCount++;
            }
        }
        activeTasks.clear();
        return cancelledCount;
    }
    
    /**
     * Sets the maximum concurrency level.
     */
    public void setMaxConcurrency(int maxConcurrency) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("Max concurrency must be positive");
        }
        
        int oldMax = this.maxConcurrency.getAndSet(maxConcurrency);
        
        // Adjust semaphore permits
        int difference = maxConcurrency - oldMax;
        if (difference > 0) {
            concurrencyLimiter.release(difference);
        } else if (difference < 0) {
            try {
                concurrencyLimiter.acquire(-difference);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while adjusting concurrency", e);
            }
        }
    }
    
    /**
     * Gets the current maximum concurrency level.
     */
    public int getMaxConcurrency() {
        return maxConcurrency.get();
    }
    
    /**
     * Gets the number of currently active operations.
     */
    public int getActiveOperations() {
        return activeOperations.get();
    }
    
    /**
     * Gets comprehensive statistics.
     */
    public ParallelOperationStats getStatistics() {
        return new ParallelOperationStats(
            totalOperations.get(),
            successfulOperations.get(),
            failedOperations.get(),
            activeOperations.get(),
            maxConcurrency.get(),
            totalBytesProcessed.get(),
            activeTasks.size()
        );
    }
    
    /**
     * Checks if the manager is shutdown.
     */
    public boolean isShutdown() {
        return shutdown || executorService.isShutdown();
    }
    
    /**
     * Waits for all active operations to complete.
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutMillis = unit.toMillis(timeout);
        long startTime = System.currentTimeMillis();
        
        while (activeOperations.get() > 0 && (System.currentTimeMillis() - startTime) < timeoutMillis) {
            Thread.sleep(100);
        }
        
        return activeOperations.get() == 0;
    }
    
    /**
     * Shuts down the parallel operation manager.
     */
    public void shutdown() {
        shutdown = true;
        cancelAllOperations();
        executorService.shutdown();
        
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Result classes
    public static class UploadResult {
        private final String fileName;
        private final boolean success;
        private final String errorMessage;
        private final long duration;
        private final long fileSize;
        
        public UploadResult(String fileName, boolean success, String errorMessage, long duration, long fileSize) {
            this.fileName = fileName;
            this.success = success;
            this.errorMessage = errorMessage;
            this.duration = duration;
            this.fileSize = fileSize;
        }
        
        public String getFileName() { return fileName; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public long getDuration() { return duration; }
        public long getFileSize() { return fileSize; }
    }
    
    public static class DownloadResult {
        private final String fileName;
        private final boolean success;
        private final String errorMessage;
        private final long duration;
        private final long fileSize;
        private final File downloadedFile;
        
        public DownloadResult(String fileName, boolean success, String errorMessage, long duration, long fileSize, File downloadedFile) {
            this.fileName = fileName;
            this.success = success;
            this.errorMessage = errorMessage;
            this.duration = duration;
            this.fileSize = fileSize;
            this.downloadedFile = downloadedFile;
        }
        
        public String getFileName() { return fileName; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public long getDuration() { return duration; }
        public long getFileSize() { return fileSize; }
        public File getDownloadedFile() { return downloadedFile; }
    }
    
    public static class ParallelOperationStats {
        private final long totalOperations;
        private final long successfulOperations;
        private final long failedOperations;
        private final int activeOperations;
        private final int maxConcurrency;
        private final long totalBytesProcessed;
        private final int activeTasks;
        
        public ParallelOperationStats(long totalOperations, long successfulOperations, long failedOperations,
                                    int activeOperations, int maxConcurrency, long totalBytesProcessed, int activeTasks) {
            this.totalOperations = totalOperations;
            this.successfulOperations = successfulOperations;
            this.failedOperations = failedOperations;
            this.activeOperations = activeOperations;
            this.maxConcurrency = maxConcurrency;
            this.totalBytesProcessed = totalBytesProcessed;
            this.activeTasks = activeTasks;
        }
        
        public long getTotalOperations() { return totalOperations; }
        public long getSuccessfulOperations() { return successfulOperations; }
        public long getFailedOperations() { return failedOperations; }
        public int getActiveOperations() { return activeOperations; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public long getTotalBytesProcessed() { return totalBytesProcessed; }
        public int getActiveTasks() { return activeTasks; }
        
        public double getSuccessRate() {
            return totalOperations > 0 ? (double) successfulOperations / totalOperations * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("ParallelOperationStats{total=%d, successful=%d, failed=%d, active=%d, " +
                    "maxConcurrency=%d, bytesProcessed=%d, successRate=%.2f%%}",
                    totalOperations, successfulOperations, failedOperations, activeOperations,
                    maxConcurrency, totalBytesProcessed, getSuccessRate());
        }
    }
    
    // Handler interfaces
    public interface UploadHandler {
        void upload(File file) throws IOException;
    }
    
    public interface DownloadHandler {
        File download(String fileName) throws IOException;
    }
}
