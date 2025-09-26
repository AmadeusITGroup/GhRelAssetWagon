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

}
