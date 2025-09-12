package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for DeltaSyncManager - TDD approach.
 * Tests define the expected API and behavior before implementation.
 */
public class DeltaSyncManagerTest {

    private DeltaSyncManager deltaSyncManager;
    private File testDir;
    private File testFile1;
    private File testFile2;
    private File testFile3;

    @BeforeEach
    public void setUp() throws IOException {
        DeltaSyncManager.resetInstance();
        deltaSyncManager = DeltaSyncManager.getInstance();
        
        testDir = Files.createTempDirectory("delta-sync-test").toFile();
        testFile1 = createTestFile("test1.jar", "content1");
        testFile2 = createTestFile("test2.jar", "content2");
        testFile3 = createTestFile("test3.jar", "content3");
        
        deltaSyncManager.setCacheDirectory(testDir);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (deltaSyncManager != null) {
            deltaSyncManager.clearCache();
        }
        deleteDirectory(testDir);
    }

    @Test
    public void testSingletonInstance() {
        DeltaSyncManager instance1 = DeltaSyncManager.getInstance();
        DeltaSyncManager instance2 = DeltaSyncManager.getInstance();
        assertSame(instance1, instance2, "DeltaSyncManager should be a singleton");
    }

    @Test
    public void testInitialSync() throws Exception {
        // Test first-time sync (no cache exists)
        String repositoryId = "test-repo";
        
        assertFalse(deltaSyncManager.hasBeenSynced(repositoryId), 
            "Should not have cached state initially");
        
        // Create test files
        List<File> files = Arrays.asList(testFile1, testFile2, testFile3);
        
        DeltaSyncManager.SyncResult result = deltaSyncManager.performInitialSync(
            repositoryId, 
            files,
            new DeltaSyncManager.SyncHandler() {
                @Override
                public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {}
                @Override
                public void deleteFile(String fileName) {}
            }
        );
        
        assertTrue(result.isSuccess(), "Initial sync should be successful");
        assertTrue(deltaSyncManager.hasBeenSynced(repositoryId), 
            "Should have cached state after sync");
        assertEquals(3, result.getAddedFiles().size(), "Should add all files");
    }

    @Test
    public void testInitialSyncWithFiles() throws Exception {
        // Test initial synchronization of a repository
        String repositoryId = "test-repo";
        List<File> files = Arrays.asList(testFile1, testFile2, testFile3);
        
        AtomicInteger syncCount = new AtomicInteger(0);
        
        DeltaSyncManager.SyncResult result = deltaSyncManager.performInitialSync(
            repositoryId, 
            files,
            new DeltaSyncManager.SyncHandler() {
                @Override
                public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {
                    syncCount.incrementAndGet();
                    assertEquals(DeltaSyncManager.SyncOperation.ADD, operation, 
                        "Initial sync should use ADD operation");
                }
                
                @Override
                public void deleteFile(String fileName) {
                    // Not used in initial sync
                }
            }
        );
        
        assertTrue(result.isSuccess(), "Initial sync should be successful");
        assertEquals(repositoryId, result.getRepositoryId(), "Should return correct repository ID");
        assertEquals(DeltaSyncManager.SyncType.INITIAL, result.getSyncType(), "Should be initial sync type");
        assertEquals(3, syncCount.get(), "Should sync all files");
        assertEquals(3, result.getAddedFiles().size(), "Should report all files as added");
        assertEquals(0, result.getModifiedFiles().size(), "Should have no modified files in initial sync");
        assertEquals(0, result.getDeletedFiles().size(), "Should have no deleted files in initial sync");
        
        assertTrue(deltaSyncManager.hasBeenSynced(repositoryId), "Repository should be marked as synced");
        assertTrue(deltaSyncManager.getLastSyncTimestamp(repositoryId) > 0, "Should have sync timestamp");
    }

    @Test
    public void testIncrementalSync() throws Exception {
        // Test incremental synchronization with changes
        String repositoryId = "test-repo";
        List<File> initialFiles = Arrays.asList(testFile1, testFile2);
        
        // Perform initial sync
        deltaSyncManager.performInitialSync(repositoryId, initialFiles, new DeltaSyncManager.SyncHandler() {
            @Override
            public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {}
            @Override
            public void deleteFile(String fileName) {}
        });
        
        // Modify files and add new one
        Thread.sleep(100); // Ensure timestamp difference
        updateFileContent(testFile1, "modified content");
        List<File> updatedFiles = Arrays.asList(testFile1, testFile2, testFile3);
        
        AtomicInteger addCount = new AtomicInteger(0);
        AtomicInteger modifyCount = new AtomicInteger(0);
        
        DeltaSyncManager.SyncResult result = deltaSyncManager.performIncrementalSync(
            repositoryId,
            updatedFiles,
            new DeltaSyncManager.SyncHandler() {
                @Override
                public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {
                    if (operation == DeltaSyncManager.SyncOperation.ADD) {
                        addCount.incrementAndGet();
                    } else if (operation == DeltaSyncManager.SyncOperation.MODIFY) {
                        modifyCount.incrementAndGet();
                    }
                }
                
                @Override
                public void deleteFile(String fileName) {}
            }
        );
        
        assertTrue(result.isSuccess(), "Incremental sync should be successful");
        assertEquals(DeltaSyncManager.SyncType.INCREMENTAL, result.getSyncType(), "Should be incremental sync");
        assertEquals(1, addCount.get(), "Should add one new file");
        assertEquals(1, modifyCount.get(), "Should modify one existing file");
        assertEquals(1, result.getAddedFiles().size(), "Should report one added file");
        assertEquals(1, result.getModifiedFiles().size(), "Should report one modified file");
        assertEquals(0, result.getDeletedFiles().size(), "Should have no deleted files");
    }

    @Test
    public void testFileDeletion() throws Exception {
        // Test detection and handling of deleted files
        String repositoryId = "test-repo";
        List<File> initialFiles = Arrays.asList(testFile1, testFile2, testFile3);
        
        // Perform initial sync
        deltaSyncManager.performInitialSync(repositoryId, initialFiles, new DeltaSyncManager.SyncHandler() {
            @Override
            public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {}
            @Override
            public void deleteFile(String fileName) {}
        });
        
        // Remove one file from the list (simulating deletion)
        List<File> updatedFiles = Arrays.asList(testFile1, testFile3);
        
        AtomicInteger deleteCount = new AtomicInteger(0);
        
        DeltaSyncManager.SyncResult result = deltaSyncManager.performIncrementalSync(
            repositoryId,
            updatedFiles,
            new DeltaSyncManager.SyncHandler() {
                @Override
                public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {}
                
                @Override
                public void deleteFile(String fileName) {
                    deleteCount.incrementAndGet();
                }
            }
        );
        
        assertTrue(result.isSuccess(), "Sync with deletion should be successful");
        assertEquals(1, deleteCount.get(), "Should delete one file");
        assertEquals(1, result.getDeletedFiles().size(), "Should report one deleted file");
        assertEquals(0, result.getAddedFiles().size(), "Should have no added files");
        assertEquals(0, result.getModifiedFiles().size(), "Should have no modified files");
    }

    @Test
    public void testConflictDetection() throws Exception {
        // Test basic conflict detection functionality
        String repositoryId = "test-repo";
        
        // Set up initial state
        List<File> initialFiles = Arrays.asList(testFile1, testFile2);
        
        deltaSyncManager.performInitialSync(repositoryId, initialFiles, new DeltaSyncManager.SyncHandler() {
            @Override
            public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {}
            @Override
            public void deleteFile(String fileName) {}
        });
        
        // Create different local and remote files
        List<File> localFiles = Arrays.asList(testFile1, testFile2);
        List<File> remoteFiles = Arrays.asList(testFile2, testFile3); // Different set
        
        DeltaSyncManager.ConflictDetectionResult conflicts = deltaSyncManager.detectConflicts(
            repositoryId, localFiles, remoteFiles);
        
        // Basic conflict detection should work
        assertNotNull(conflicts, "Should return conflict detection result");
        assertNotNull(conflicts.getConflicts(), "Should have conflicts list");
    }

    @Test
    public void testSyncStatistics() throws Exception {
        // Test comprehensive sync statistics tracking
        String repo1 = "repo1";
        String repo2 = "repo2";
        
        // Perform multiple sync operations
        deltaSyncManager.performInitialSync(repo1, Arrays.asList(testFile1), new DeltaSyncManager.SyncHandler() {
            @Override
            public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {}
            @Override
            public void deleteFile(String fileName) {}
        });
        deltaSyncManager.performInitialSync(repo2, Arrays.asList(testFile2, testFile3), new DeltaSyncManager.SyncHandler() {
            @Override
            public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {}
            @Override
            public void deleteFile(String fileName) {}
        });
        
        DeltaSyncManager.DeltaSyncStats stats = deltaSyncManager.getStatistics();
        
        assertNotNull(stats, "Should provide statistics");
        assertEquals(2, stats.getRepositoriesTracked(), "Should track 2 repositories");
        assertTrue(stats.getTotalSyncOperations() >= 2, "Should have at least 2 sync operations");
        assertTrue(stats.getFilesProcessed() >= 3, "Should have processed at least 3 files");
        assertTrue(stats.getBytesTransferred() > 0, "Should have transferred some bytes");
        assertTrue(stats.getSuccessRate() > 0, "Should have positive success rate");
        
        String statsString = stats.toString();
        assertTrue(statsString.contains("DeltaSyncStats"), "Statistics should be formatted correctly");
        assertTrue(statsString.contains("repos"), "Statistics should mention repositories");
        
        // Test repository tracking
        assertTrue(deltaSyncManager.hasBeenSynced(repo1), "Repo1 should be tracked");
        assertTrue(deltaSyncManager.hasBeenSynced(repo2), "Repo2 should be tracked");
        assertFalse(deltaSyncManager.hasBeenSynced("non-existent"), "Non-existent repo should not be tracked");
    }

    @Test
    public void testCacheManagement() throws Exception {
        // Test basic cache functionality
        String repositoryId = "test-repo";
        List<File> files = Arrays.asList(testFile1, testFile2);
        
        // Perform sync and verify cache
        deltaSyncManager.performInitialSync(repositoryId, files, new DeltaSyncManager.SyncHandler() {
            @Override
            public void syncFile(File file, DeltaSyncManager.SyncOperation operation) {}
            @Override
            public void deleteFile(String fileName) {}
        });
        assertTrue(deltaSyncManager.hasBeenSynced(repositoryId), "Should have synced state");
        
        // Clear cache and verify
        deltaSyncManager.clearCache();
        assertFalse(deltaSyncManager.hasBeenSynced(repositoryId), "Should not have cached state after clear");
        assertFalse(deltaSyncManager.hasBeenSynced("non-existent"), "Should not have state for non-existent repo");
        
        // Test basic cache directory functionality
        File cacheDir = deltaSyncManager.getCacheDirectory();
        assertNotNull(cacheDir, "Should have cache directory");
        assertTrue(cacheDir.exists(), "Cache directory should exist");
    }

    // Helper methods for test setup
    private File createTestFile(String name, String content) throws IOException {
        File file = new File(testDir, name);
        Files.write(file.toPath(), content.getBytes());
        return file;
    }

    private void updateFileContent(File file, String newContent) throws IOException {
        Files.write(file.toPath(), newContent.getBytes());
    }

    private void deleteDirectory(File directory) {
        if (directory != null && directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}
