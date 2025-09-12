package io.github.amadeusitgroup.maven.wagon;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages delta synchronization for incremental repository updates.
 * Implements singleton pattern with thread-safe operations.
 */
public class DeltaSyncManager {
    
    private static volatile DeltaSyncManager instance;
    private static final Object lock = new Object();
    
    private final Map<String, RepositorySnapshot> repositorySnapshots;
    private final Map<String, Long> lastSyncTimestamps;
    private final ObjectMapper objectMapper;
    private final AtomicLong totalSyncOperations;
    private final AtomicLong successfulSyncs;
    private final AtomicLong failedSyncs;
    private final AtomicLong filesProcessed;
    private final AtomicLong bytesTransferred;
    private File cacheDirectory;
    
    private DeltaSyncManager() {
        this.repositorySnapshots = new ConcurrentHashMap<>();
        this.lastSyncTimestamps = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        this.totalSyncOperations = new AtomicLong(0);
        this.successfulSyncs = new AtomicLong(0);
        this.failedSyncs = new AtomicLong(0);
        this.filesProcessed = new AtomicLong(0);
        this.bytesTransferred = new AtomicLong(0);
        
        // Default cache directory
        String userHome = System.getProperty("user.home");
        this.cacheDirectory = new File(userHome, ".ghrelasset/delta-cache");
        this.cacheDirectory.mkdirs();
    }
    
    public static DeltaSyncManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new DeltaSyncManager();
                }
            }
        }
        return instance;
    }
    
    public static void resetInstance() {
        synchronized (lock) {
            instance = null;
        }
    }
    
    /**
     * Performs initial synchronization of a repository.
     */
    public SyncResult performInitialSync(String repositoryId, List<File> files, SyncHandler handler) {
        totalSyncOperations.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        try {
            RepositorySnapshot snapshot = new RepositorySnapshot();
            List<String> processedFiles = new ArrayList<>();
            long totalBytes = 0;
            
            for (File file : files) {
                if (file.exists() && file.isFile()) {
                    FileMetadata metadata = createFileMetadata(file);
                    snapshot.addFile(file.getName(), metadata);
                    
                    handler.syncFile(file, SyncOperation.ADD);
                    processedFiles.add(file.getName());
                    totalBytes += file.length();
                    filesProcessed.incrementAndGet();
                }
            }
            
            repositorySnapshots.put(repositoryId, snapshot);
            lastSyncTimestamps.put(repositoryId, System.currentTimeMillis());
            bytesTransferred.addAndGet(totalBytes);
            
            persistSnapshot(repositoryId, snapshot);
            
            long duration = System.currentTimeMillis() - startTime;
            successfulSyncs.incrementAndGet();
            
            return new SyncResult(repositoryId, SyncType.INITIAL, true, null, 
                processedFiles, new ArrayList<>(), new ArrayList<>(), duration, totalBytes);
            
        } catch (Exception e) {
            failedSyncs.incrementAndGet();
            long duration = System.currentTimeMillis() - startTime;
            return new SyncResult(repositoryId, SyncType.INITIAL, false, e.getMessage(), 
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), duration, 0);
        }
    }
    
    /**
     * Performs incremental synchronization of a repository.
     */
    public SyncResult performIncrementalSync(String repositoryId, List<File> currentFiles, SyncHandler handler) {
        totalSyncOperations.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        try {
            RepositorySnapshot previousSnapshot = repositorySnapshots.get(repositoryId);
            if (previousSnapshot == null) {
                // No previous snapshot, perform initial sync
                return performInitialSync(repositoryId, currentFiles, handler);
            }
            
            DeltaAnalysis delta = analyzeDelta(previousSnapshot, currentFiles);
            
            List<String> addedFiles = new ArrayList<>();
            List<String> modifiedFiles = new ArrayList<>();
            List<String> deletedFiles = new ArrayList<>();
            long totalBytes = 0;
            
            // Process added files
            for (File file : delta.getAddedFiles()) {
                handler.syncFile(file, SyncOperation.ADD);
                addedFiles.add(file.getName());
                totalBytes += file.length();
                filesProcessed.incrementAndGet();
            }
            
            // Process modified files
            for (File file : delta.getModifiedFiles()) {
                handler.syncFile(file, SyncOperation.MODIFY);
                modifiedFiles.add(file.getName());
                totalBytes += file.length();
                filesProcessed.incrementAndGet();
            }
            
            // Process deleted files
            for (String fileName : delta.getDeletedFiles()) {
                handler.deleteFile(fileName);
                deletedFiles.add(fileName);
                filesProcessed.incrementAndGet();
            }
            
            // Update snapshot
            RepositorySnapshot newSnapshot = createNewSnapshot(currentFiles);
            repositorySnapshots.put(repositoryId, newSnapshot);
            lastSyncTimestamps.put(repositoryId, System.currentTimeMillis());
            bytesTransferred.addAndGet(totalBytes);
            
            persistSnapshot(repositoryId, newSnapshot);
            
            long duration = System.currentTimeMillis() - startTime;
            successfulSyncs.incrementAndGet();
            
            return new SyncResult(repositoryId, SyncType.INCREMENTAL, true, null, 
                addedFiles, modifiedFiles, deletedFiles, duration, totalBytes);
            
        } catch (Exception e) {
            failedSyncs.incrementAndGet();
            long duration = System.currentTimeMillis() - startTime;
            return new SyncResult(repositoryId, SyncType.INCREMENTAL, false, e.getMessage(), 
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), duration, 0);
        }
    }
    
    /**
     * Detects conflicts between local and remote changes.
     */
    public ConflictDetectionResult detectConflicts(String repositoryId, List<File> localFiles, List<File> remoteFiles) {
        List<FileConflict> conflicts = new ArrayList<>();
        
        RepositorySnapshot lastSnapshot = repositorySnapshots.get(repositoryId);
        if (lastSnapshot == null) {
            return new ConflictDetectionResult(conflicts, ConflictResolutionStrategy.NONE);
        }
        
        Map<String, File> localFileMap = localFiles.stream()
            .collect(Collectors.toMap(File::getName, f -> f));
        Map<String, File> remoteFileMap = remoteFiles.stream()
            .collect(Collectors.toMap(File::getName, f -> f));
        
        // Check for conflicts
        for (String fileName : localFileMap.keySet()) {
            if (remoteFileMap.containsKey(fileName)) {
                File localFile = localFileMap.get(fileName);
                File remoteFile = remoteFileMap.get(fileName);
                FileMetadata lastKnown = lastSnapshot.getFileMetadata(fileName);
                
                if (lastKnown != null) {
                    FileMetadata localMetadata = createFileMetadata(localFile);
                    FileMetadata remoteMetadata = createFileMetadata(remoteFile);
                    
                    // Check if both local and remote have changed since last sync
                    if (!lastKnown.equals(localMetadata) && !lastKnown.equals(remoteMetadata) 
                        && !localMetadata.equals(remoteMetadata)) {
                        
                        conflicts.add(new FileConflict(fileName, localFile, remoteFile, 
                            ConflictType.MODIFY_MODIFY, "Both local and remote versions modified"));
                    }
                }
            }
        }
        
        ConflictResolutionStrategy strategy = conflicts.isEmpty() ? 
            ConflictResolutionStrategy.NONE : ConflictResolutionStrategy.MANUAL;
        
        return new ConflictDetectionResult(conflicts, strategy);
    }
    
    /**
     * Gets the timestamp of the last sync for a repository.
     */
    public long getLastSyncTimestamp(String repositoryId) {
        return lastSyncTimestamps.getOrDefault(repositoryId, 0L);
    }
    
    /**
     * Checks if a repository has been synced before.
     */
    public boolean hasBeenSynced(String repositoryId) {
        return repositorySnapshots.containsKey(repositoryId);
    }
    
    /**
     * Gets comprehensive statistics.
     */
    public DeltaSyncStats getStatistics() {
        return new DeltaSyncStats(
            totalSyncOperations.get(),
            successfulSyncs.get(),
            failedSyncs.get(),
            filesProcessed.get(),
            bytesTransferred.get(),
            repositorySnapshots.size()
        );
    }
    
    /**
     * Sets the cache directory for storing snapshots.
     */
    public void setCacheDirectory(File cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
        this.cacheDirectory.mkdirs();
    }
    
    /**
     * Gets the current cache directory.
     */
    public File getCacheDirectory() {
        return cacheDirectory;
    }
    
    /**
     * Loads a repository snapshot from cache.
     */
    public boolean loadSnapshot(String repositoryId) {
        try {
            File snapshotFile = new File(cacheDirectory, repositoryId + ".snapshot");
            if (snapshotFile.exists()) {
                RepositorySnapshot snapshot = objectMapper.readValue(snapshotFile, RepositorySnapshot.class);
                repositorySnapshots.put(repositoryId, snapshot);
                return true;
            }
        } catch (IOException e) {
            // Ignore load errors
        }
        return false;
    }
    
    /**
     * Clears all cached snapshots.
     */
    public void clearCache() {
        repositorySnapshots.clear();
        lastSyncTimestamps.clear();
        
        // Clear cache files
        if (cacheDirectory.exists()) {
            File[] files = cacheDirectory.listFiles((dir, name) -> name.endsWith(".snapshot"));
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }
    
    // Private helper methods
    
    private FileMetadata createFileMetadata(File file) {
        return new FileMetadata(
            file.getName(),
            file.length(),
            file.lastModified(),
            calculateChecksum(file)
        );
    }
    
    private String calculateChecksum(File file) {
        try {
            byte[] content = Files.readAllBytes(file.toPath());
            return Integer.toHexString(java.util.Arrays.hashCode(content));
        } catch (IOException e) {
            return String.valueOf(file.lastModified());
        }
    }
    
    private DeltaAnalysis analyzeDelta(RepositorySnapshot previousSnapshot, List<File> currentFiles) {
        Set<String> previousFileNames = previousSnapshot.getFileNames();
        Map<String, File> currentFileMap = currentFiles.stream()
            .collect(Collectors.toMap(File::getName, f -> f));
        
        List<File> addedFiles = new ArrayList<>();
        List<File> modifiedFiles = new ArrayList<>();
        List<String> deletedFiles = new ArrayList<>();
        
        // Find added and modified files
        for (File currentFile : currentFiles) {
            String fileName = currentFile.getName();
            if (!previousFileNames.contains(fileName)) {
                addedFiles.add(currentFile);
            } else {
                FileMetadata previousMetadata = previousSnapshot.getFileMetadata(fileName);
                FileMetadata currentMetadata = createFileMetadata(currentFile);
                if (!previousMetadata.equals(currentMetadata)) {
                    modifiedFiles.add(currentFile);
                }
            }
        }
        
        // Find deleted files
        for (String previousFileName : previousFileNames) {
            if (!currentFileMap.containsKey(previousFileName)) {
                deletedFiles.add(previousFileName);
            }
        }
        
        return new DeltaAnalysis(addedFiles, modifiedFiles, deletedFiles);
    }
    
    private RepositorySnapshot createNewSnapshot(List<File> files) {
        RepositorySnapshot snapshot = new RepositorySnapshot();
        for (File file : files) {
            if (file.exists() && file.isFile()) {
                FileMetadata metadata = createFileMetadata(file);
                snapshot.addFile(file.getName(), metadata);
            }
        }
        return snapshot;
    }
    
    private void persistSnapshot(String repositoryId, RepositorySnapshot snapshot) {
        try {
            File snapshotFile = new File(cacheDirectory, repositoryId + ".snapshot");
            objectMapper.writeValue(snapshotFile, snapshot);
        } catch (IOException e) {
            // Ignore persistence errors
        }
    }
    
    // Data classes
    
    public static class RepositorySnapshot {
        private Map<String, FileMetadata> files = new HashMap<>();
        private long timestamp = System.currentTimeMillis();
        
        public RepositorySnapshot() {}
        
        public void addFile(String fileName, FileMetadata metadata) {
            files.put(fileName, metadata);
        }
        
        public FileMetadata getFileMetadata(String fileName) {
            return files.get(fileName);
        }
        
        public Set<String> getFileNames() {
            return files.keySet();
        }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public Map<String, FileMetadata> getFiles() { return files; }
        public void setFiles(Map<String, FileMetadata> files) { this.files = files; }
    }
    
    public static class FileMetadata {
        private String fileName;
        private long size;
        private long lastModified;
        private String checksum;
        
        public FileMetadata() {}
        
        public FileMetadata(String fileName, long size, long lastModified, String checksum) {
            this.fileName = fileName;
            this.size = size;
            this.lastModified = lastModified;
            this.checksum = checksum;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            FileMetadata that = (FileMetadata) obj;
            return size == that.size && 
                   lastModified == that.lastModified && 
                   checksum.equals(that.checksum);
        }
        
        @Override
        public int hashCode() {
            return java.util.Objects.hash(size, lastModified, checksum);
        }
        
        // Getters and setters
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public long getLastModified() { return lastModified; }
        public void setLastModified(long lastModified) { this.lastModified = lastModified; }
        public String getChecksum() { return checksum; }
        public void setChecksum(String checksum) { this.checksum = checksum; }
    }
    
    public static class DeltaAnalysis {
        private final List<File> addedFiles;
        private final List<File> modifiedFiles;
        private final List<String> deletedFiles;
        
        public DeltaAnalysis(List<File> addedFiles, List<File> modifiedFiles, List<String> deletedFiles) {
            this.addedFiles = addedFiles;
            this.modifiedFiles = modifiedFiles;
            this.deletedFiles = deletedFiles;
        }
        
        public List<File> getAddedFiles() { return addedFiles; }
        public List<File> getModifiedFiles() { return modifiedFiles; }
        public List<String> getDeletedFiles() { return deletedFiles; }
    }
    
    public static class SyncResult {
        private final String repositoryId;
        private final SyncType syncType;
        private final boolean success;
        private final String errorMessage;
        private final List<String> addedFiles;
        private final List<String> modifiedFiles;
        private final List<String> deletedFiles;
        private final long duration;
        private final long bytesTransferred;
        
        public SyncResult(String repositoryId, SyncType syncType, boolean success, String errorMessage,
                         List<String> addedFiles, List<String> modifiedFiles, List<String> deletedFiles,
                         long duration, long bytesTransferred) {
            this.repositoryId = repositoryId;
            this.syncType = syncType;
            this.success = success;
            this.errorMessage = errorMessage;
            this.addedFiles = addedFiles;
            this.modifiedFiles = modifiedFiles;
            this.deletedFiles = deletedFiles;
            this.duration = duration;
            this.bytesTransferred = bytesTransferred;
        }
        
        public String getRepositoryId() { return repositoryId; }
        public SyncType getSyncType() { return syncType; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public List<String> getAddedFiles() { return addedFiles; }
        public List<String> getModifiedFiles() { return modifiedFiles; }
        public List<String> getDeletedFiles() { return deletedFiles; }
        public long getDuration() { return duration; }
        public long getBytesTransferred() { return bytesTransferred; }
    }
    
    public static class ConflictDetectionResult {
        private final List<FileConflict> conflicts;
        private final ConflictResolutionStrategy recommendedStrategy;
        
        public ConflictDetectionResult(List<FileConflict> conflicts, ConflictResolutionStrategy recommendedStrategy) {
            this.conflicts = conflicts;
            this.recommendedStrategy = recommendedStrategy;
        }
        
        public List<FileConflict> getConflicts() { return conflicts; }
        public ConflictResolutionStrategy getRecommendedStrategy() { return recommendedStrategy; }
        public boolean hasConflicts() { return !conflicts.isEmpty(); }
    }
    
    public static class FileConflict {
        private final String fileName;
        private final File localFile;
        private final File remoteFile;
        private final ConflictType conflictType;
        private final String description;
        
        public FileConflict(String fileName, File localFile, File remoteFile, 
                           ConflictType conflictType, String description) {
            this.fileName = fileName;
            this.localFile = localFile;
            this.remoteFile = remoteFile;
            this.conflictType = conflictType;
            this.description = description;
        }
        
        public String getFileName() { return fileName; }
        public File getLocalFile() { return localFile; }
        public File getRemoteFile() { return remoteFile; }
        public ConflictType getConflictType() { return conflictType; }
        public String getDescription() { return description; }
    }
    
    public static class DeltaSyncStats {
        private final long totalSyncOperations;
        private final long successfulSyncs;
        private final long failedSyncs;
        private final long filesProcessed;
        private final long bytesTransferred;
        private final int repositoriesTracked;
        
        public DeltaSyncStats(long totalSyncOperations, long successfulSyncs, long failedSyncs,
                             long filesProcessed, long bytesTransferred, int repositoriesTracked) {
            this.totalSyncOperations = totalSyncOperations;
            this.successfulSyncs = successfulSyncs;
            this.failedSyncs = failedSyncs;
            this.filesProcessed = filesProcessed;
            this.bytesTransferred = bytesTransferred;
            this.repositoriesTracked = repositoriesTracked;
        }
        
        public long getTotalSyncOperations() { return totalSyncOperations; }
        public long getSuccessfulSyncs() { return successfulSyncs; }
        public long getFailedSyncs() { return failedSyncs; }
        public long getFilesProcessed() { return filesProcessed; }
        public long getBytesTransferred() { return bytesTransferred; }
        public int getRepositoriesTracked() { return repositoriesTracked; }
        
        public double getSuccessRate() {
            return totalSyncOperations > 0 ? (double) successfulSyncs / totalSyncOperations * 100.0 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("DeltaSyncStats{total=%d, successful=%d, failed=%d, files=%d, " +
                    "bytes=%d, repos=%d, successRate=%.2f%%}",
                    totalSyncOperations, successfulSyncs, failedSyncs, filesProcessed,
                    bytesTransferred, repositoriesTracked, getSuccessRate());
        }
    }
    
    // Enums
    
    public enum SyncType {
        INITIAL, INCREMENTAL
    }
    
    public enum SyncOperation {
        ADD, MODIFY, DELETE
    }
    
    public enum ConflictType {
        MODIFY_MODIFY, ADD_ADD, DELETE_MODIFY
    }
    
    public enum ConflictResolutionStrategy {
        NONE, LOCAL_WINS, REMOTE_WINS, MANUAL, MERGE
    }
    
    // Handler interface
    
    public interface SyncHandler {
        void syncFile(File file, SyncOperation operation) throws IOException;
        void deleteFile(String fileName) throws IOException;
    }
}
