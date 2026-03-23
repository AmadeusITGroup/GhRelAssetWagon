package io.github.amadeusitgroup.maven.wagon;

import org.apache.maven.wagon.repository.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.io.TempDir;

class ZipCacheManagerTest {

  @TempDir
  Path tempDir;

  GhRelAssetRepository ghRelAssetRepository;

  @BeforeEach
  void setUp() {
    ghRelAssetRepository = new GhRelAssetRepository(new Repository("id", "ghrelasset://org/repo/tag/zip.zip"));
  }

    @Test
    void testCacheFilename() throws IOException {
        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);
        zipCacheManager.initialize(ghRelAssetRepository, null);

        Path expectedPath = tempDir.resolve("C7FF432FC21DE2BA73216A62D5EA21EB9564B57C");
        assertEquals(expectedPath, zipCacheManager.getCacheFile().toPath());
    }

    @Test
    void testIsInitialized() throws IOException {
        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);
        assertFalse(zipCacheManager.isInitialized());

        InputStream inputStream = new ByteArrayInputStream("test data".getBytes(StandardCharsets.UTF_8));
        zipCacheManager.initialize(ghRelAssetRepository, inputStream);

        assertTrue(zipCacheManager.isInitialized());
        assertTrue(zipCacheManager.getCacheFile().exists());
        assertEquals("test data", FileUtils.readFileToString(zipCacheManager.getCacheFile(), StandardCharsets.UTF_8));
    }

    @Test
    void testGetZipFileSystemBeforeInitialization() {
        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);
        assertThrows(IllegalStateException.class, zipCacheManager::getZipFileSystem);
    }

    @Test
    void testGetAndCloseZipFileSystem() throws IOException {
        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);

        // create InputStream for a zip file
        Path zipPath = tempDir.resolve("testzip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry entry = new ZipEntry("testdir/");
            zos.putNextEntry(entry);
            zos.closeEntry();
        }
        InputStream inputStream = Files.newInputStream(zipPath);

        zipCacheManager.initialize(ghRelAssetRepository, inputStream);

        FileSystem fs = zipCacheManager.getZipFileSystem();
        assertNotNull(fs);
        assertTrue(fs.isOpen());

        zipCacheManager.close();
        assertFalse(fs.isOpen());
    }

    @Test
    void testConcurrentGetZipFileSystemDoesNotThrow() throws Exception {
        // Simulates the scenario where Maven deploys .jar and .pom in parallel,
        // creating two ZipCacheManager instances pointing at the same zip file.
        // Both call getZipFileSystem() concurrently — the second should not fail
        // with FileSystemAlreadyExistsException.

        // Create a valid zip file to use as cache content
        Path zipPath = tempDir.resolve("concurrentzip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry entry = new ZipEntry("testdir/");
            zos.putNextEntry(entry);
            zos.closeEntry();
        }

        // Initialize two separate ZipCacheManager instances with the same repo URL
        // (same SHA-1 → same cache file → same FileSystem URI)
        ZipCacheManager manager1 = new ZipCacheManager(tempDir);
        ZipCacheManager manager2 = new ZipCacheManager(tempDir);

        manager1.initialize(ghRelAssetRepository, Files.newInputStream(zipPath));
        manager2.initialize(ghRelAssetRepository, Files.newInputStream(zipPath));

        // Use a latch so both threads call getZipFileSystem() at the same time
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<FileSystem> future1 = executor.submit(() -> {
            startLatch.await();
            return manager1.getZipFileSystem();
        });

        Future<FileSystem> future2 = executor.submit(() -> {
            startLatch.await();
            return manager2.getZipFileSystem();
        });

        startLatch.countDown(); // release both threads simultaneously

        FileSystem fs1 = future1.get();
        FileSystem fs2 = future2.get();

        assertNotNull(fs1);
        assertNotNull(fs2);
        assertTrue(fs1.isOpen());
        assertTrue(fs2.isOpen());

        executor.shutdown();

        // Clean up — close one, the other should handle it gracefully
        manager1.close();
        manager2.close();
    }

    @Test
    void testCloseAfterAlreadyClosed() throws IOException {
        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);

        Path zipPath = tempDir.resolve("doubleclose");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry entry = new ZipEntry("testdir/");
            zos.putNextEntry(entry);
            zos.closeEntry();
        }
        zipCacheManager.initialize(ghRelAssetRepository, Files.newInputStream(zipPath));
        zipCacheManager.getZipFileSystem();

        // First close
        zipCacheManager.close();
        // Second close should not throw
        assertDoesNotThrow(() -> zipCacheManager.close());
    }

}
