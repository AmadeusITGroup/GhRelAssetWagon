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

/**
 * Unit tests for CompressionHandler - TDD approach.
 * Tests define the expected API and behavior for artifact compression.
 */
public class CompressionHandlerTest {

    private CompressionHandler compressionHandler;
    private File testDir;

    @BeforeEach
    public void setUp() throws IOException {
        CompressionHandler.resetInstance();
        compressionHandler = CompressionHandler.getInstance();
        testDir = Files.createTempDirectory("compression-test").toFile();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (testDir != null && testDir.exists()) {
            deleteDirectory(testDir);
        }
    }

    @Test
    public void testSingletonInstance() {
        CompressionHandler instance1 = CompressionHandler.getInstance();
        CompressionHandler instance2 = CompressionHandler.getInstance();
        assertSame(instance1, instance2, "CompressionHandler should be a singleton");
    }

    @Test
    public void testSingleFileCompression() throws Exception {
        // Test compression of a single file
        File sourceFile = createTestFile("test-artifact.jar", 1024);
        File compressedFile = new File(testDir, "compressed.gz");
        
        long originalSize = sourceFile.length();
        
        compressionHandler.compressFile(sourceFile, compressedFile);
        
        assertTrue(compressedFile.exists(), "Compressed file should exist");
        assertTrue(compressedFile.length() > 0, "Compressed file should have content");
        assertTrue(compressedFile.length() < originalSize, "Compressed file should be smaller");
        
        // Test decompression
        File decompressedFile = new File(testDir, "decompressed.jar");
        compressionHandler.decompressFile(compressedFile, decompressedFile);
        
        assertTrue(decompressedFile.exists(), "Decompressed file should exist");
        assertEquals(originalSize, decompressedFile.length(), "Decompressed file should match original size");
        
        // Verify content integrity
        byte[] originalContent = Files.readAllBytes(sourceFile.toPath());
        byte[] decompressedContent = Files.readAllBytes(decompressedFile.toPath());
        assertArrayEquals(originalContent, decompressedContent, "Content should be identical after compression/decompression");
    }

    @Test
    public void testMultipleFileCompression() throws Exception {
        // Test compression of multiple files into archive
        File file1 = createTestFile("artifact1.jar", 512);
        File file2 = createTestFile("artifact2.jar", 768);
        File file3 = createTestFile("metadata.xml", 256);
        
        List<File> sourceFiles = Arrays.asList(file1, file2, file3);
        File archiveFile = new File(testDir, "archive.tar.gz");
        
        long totalOriginalSize = sourceFiles.stream().mapToLong(File::length).sum();
        
        compressionHandler.compressFiles(sourceFiles, archiveFile);
        
        assertTrue(archiveFile.exists(), "Archive file should exist");
        assertTrue(archiveFile.length() > 0, "Archive should have content");
        assertTrue(archiveFile.length() < totalOriginalSize, "Archive should be smaller than total original size");
        
        // Test extraction
        File extractDir = new File(testDir, "extracted");
        extractDir.mkdirs();
        
        List<File> extractedFiles = compressionHandler.decompressArchive(archiveFile, extractDir);
        
        assertEquals(3, extractedFiles.size(), "Should extract all files");
        
        // Verify extracted files
        for (File originalFile : sourceFiles) {
            File extractedFile = new File(extractDir, originalFile.getName());
            assertTrue(extractedFile.exists(), "Extracted file should exist: " + originalFile.getName());
            assertEquals(originalFile.length(), extractedFile.length(), "File sizes should match");
        }
    }

    @Test
    public void testCompressionRatios() throws Exception {
        // Test compression ratios for different file types
        File textFile = createTestTextFile("large-text.txt", 2048);
        File binaryFile = createTestFile("binary.jar", 2048);
        
        File compressedText = new File(testDir, "text.gz");
        File compressedBinary = new File(testDir, "binary.gz");
        
        compressionHandler.compressFile(textFile, compressedText);
        compressionHandler.compressFile(binaryFile, compressedBinary);
        
        double textRatio = (double) compressedText.length() / textFile.length();
        double binaryRatio = (double) compressedBinary.length() / binaryFile.length();
        
        assertTrue(textRatio < 0.8, "Text files should compress well (< 80% of original)");
        assertTrue(binaryRatio < 1.0, "Binary files should still compress somewhat");
        
        // Get compression statistics
        CompressionHandler.CompressionStats textStats = compressionHandler.getCompressionStats(textFile, compressedText);
        CompressionHandler.CompressionStats binaryStats = compressionHandler.getCompressionStats(binaryFile, compressedBinary);
        
        assertNotNull(textStats, "Should provide compression stats for text");
        assertNotNull(binaryStats, "Should provide compression stats for binary");
        
        assertTrue(textStats.getCompressionRatio() < binaryStats.getCompressionRatio(), 
            "Text should have better compression ratio (lower ratio) than binary");
    }

    @Test
    public void testCompressionAlgorithms() throws Exception {
        // Test different compression algorithms
        File sourceFile = createTestFile("test-file.jar", 1024);
        
        // Test GZIP
        File gzipFile = new File(testDir, "test.gz");
        compressionHandler.compressFile(sourceFile, gzipFile, CompressionHandler.Algorithm.GZIP);
        assertTrue(gzipFile.exists(), "GZIP compressed file should exist");
        
        // Test DEFLATE
        File deflateFile = new File(testDir, "test.deflate");
        compressionHandler.compressFile(sourceFile, deflateFile, CompressionHandler.Algorithm.DEFLATE);
        assertTrue(deflateFile.exists(), "DEFLATE compressed file should exist");
        
        // Test LZ4 (if supported)
        if (compressionHandler.isAlgorithmSupported(CompressionHandler.Algorithm.LZ4)) {
            File lz4File = new File(testDir, "test.lz4");
            compressionHandler.compressFile(sourceFile, lz4File, CompressionHandler.Algorithm.LZ4);
            assertTrue(lz4File.exists(), "LZ4 compressed file should exist");
        }
        
        // Compare compression ratios
        CompressionHandler.CompressionStats gzipStats = compressionHandler.getCompressionStats(sourceFile, gzipFile);
        CompressionHandler.CompressionStats deflateStats = compressionHandler.getCompressionStats(sourceFile, deflateFile);
        
        assertTrue(gzipStats.getCompressionRatio() > 0, "GZIP should provide compression");
        assertTrue(deflateStats.getCompressionRatio() > 0, "DEFLATE should provide compression");
    }

    @Test
    public void testStreamingCompression() throws Exception {
        // Test streaming compression for large files
        File largeFile = createTestFile("large-artifact.jar", 10240); // 10KB
        File streamCompressed = new File(testDir, "stream-compressed.gz");
        
        // Use streaming compression
        long startTime = System.currentTimeMillis();
        compressionHandler.compressFileStreaming(largeFile, streamCompressed);
        long streamTime = System.currentTimeMillis() - startTime;
        
        assertTrue(streamCompressed.exists(), "Stream compressed file should exist");
        
        // Compare with regular compression
        File regularCompressed = new File(testDir, "regular-compressed.gz");
        startTime = System.currentTimeMillis();
        compressionHandler.compressFile(largeFile, regularCompressed);
        long regularTime = System.currentTimeMillis() - startTime;
        
        // Streaming should be more memory efficient (we can't easily test memory usage in unit tests)
        // but we can verify the results are equivalent
        assertEquals(regularCompressed.length(), streamCompressed.length(), 
            "Streaming and regular compression should produce same size output");
    }

    @Test
    public void testCompressionValidation() throws Exception {
        // Test validation of compressed files
        File sourceFile = createTestFile("test-artifact.jar", 512);
        File compressedFile = new File(testDir, "test.gz");
        
        compressionHandler.compressFile(sourceFile, compressedFile);
        
        assertTrue(compressionHandler.isValidCompressedFile(compressedFile), 
            "Should validate correctly compressed file");
        
        // Test with corrupted file
        File corruptedFile = new File(testDir, "corrupted.gz");
        Files.write(corruptedFile.toPath(), "invalid compressed data".getBytes());
        
        assertFalse(compressionHandler.isValidCompressedFile(corruptedFile), 
            "Should detect corrupted compressed file");
        
        // Test integrity check
        assertTrue(compressionHandler.verifyIntegrity(sourceFile, compressedFile), 
            "Should verify integrity of compressed file");
    }

    @Test
    public void testCompressionMetadata() throws Exception {
        // Test compression metadata handling
        File sourceFile = createTestFile("artifact-with-metadata.jar", 1024);
        File compressedFile = new File(testDir, "with-metadata.gz");
        
        // Add metadata during compression
        CompressionHandler.CompressionOptions options = new CompressionHandler.CompressionOptions()
            .setOriginalFilename(sourceFile.getName())
            .setTimestamp(sourceFile.lastModified())
            .setComment("Test compression with metadata");
        
        compressionHandler.compressFile(sourceFile, compressedFile, options);
        
        // Extract metadata
        CompressionHandler.CompressionMetadata metadata = compressionHandler.getMetadata(compressedFile);
        
        assertNotNull(metadata, "Should extract metadata from compressed file");
        assertEquals(sourceFile.getName(), metadata.getOriginalFilename(), "Should preserve original filename");
        assertEquals(sourceFile.lastModified(), metadata.getTimestamp(), "Should preserve timestamp");
        assertEquals("Test compression with metadata", metadata.getComment(), "Should preserve comment");
    }

    @Test
    public void testCompressionPerformance() throws Exception {
        // Test compression performance with different settings
        File sourceFile = createTestFile("performance-test.jar", 5120); // 5KB
        
        // Test different compression levels
        File fastCompressed = new File(testDir, "fast.gz");
        File normalCompressed = new File(testDir, "normal.gz");
        File bestCompressed = new File(testDir, "best.gz");
        
        long startTime = System.currentTimeMillis();
        compressionHandler.compressFile(sourceFile, fastCompressed, CompressionHandler.Level.FAST);
        long fastTime = System.currentTimeMillis() - startTime;
        
        startTime = System.currentTimeMillis();
        compressionHandler.compressFile(sourceFile, normalCompressed, CompressionHandler.Level.NORMAL);
        long normalTime = System.currentTimeMillis() - startTime;
        
        startTime = System.currentTimeMillis();
        compressionHandler.compressFile(sourceFile, bestCompressed, CompressionHandler.Level.BEST);
        long bestTime = System.currentTimeMillis() - startTime;
        
        // Verify compression level effects
        assertTrue(bestCompressed.length() <= normalCompressed.length(), 
            "Best compression should produce smaller or equal file");
        assertTrue(normalCompressed.length() <= fastCompressed.length(), 
            "Normal compression should produce smaller or equal file than fast");
        
        // Performance characteristics (fast should be fastest, but this is hard to test reliably in unit tests)
        assertTrue(fastTime >= 0 && normalTime >= 0 && bestTime >= 0, "All compressions should complete");
    }

    @Test
    public void testBatchCompression() throws Exception {
        // Test batch compression operations
        File[] sourceFiles = new File[5];
        for (int i = 0; i < 5; i++) {
            sourceFiles[i] = createTestFile("batch-file-" + i + ".jar", 512 + i * 100);
        }
        
        File batchOutputDir = new File(testDir, "batch-output");
        batchOutputDir.mkdirs();
        
        List<File> compressedFiles = compressionHandler.compressBatch(
            Arrays.asList(sourceFiles), 
            batchOutputDir,
            CompressionHandler.Algorithm.GZIP
        );
        
        assertEquals(5, compressedFiles.size(), "Should compress all files in batch");
        
        for (int i = 0; i < 5; i++) {
            File compressedFile = compressedFiles.get(i);
            assertTrue(compressedFile.exists(), "Batch compressed file should exist: " + compressedFile.getName());
            assertTrue(compressedFile.length() < sourceFiles[i].length(), "Should be compressed");
        }
        
        // Test batch decompression
        File batchExtractDir = new File(testDir, "batch-extract");
        batchExtractDir.mkdirs();
        
        List<File> decompressedFiles = compressionHandler.decompressBatch(compressedFiles, batchExtractDir);
        
        assertEquals(5, decompressedFiles.size(), "Should decompress all files in batch");
        
        for (int i = 0; i < 5; i++) {
            assertEquals(sourceFiles[i].length(), decompressedFiles.get(i).length(), 
                "Decompressed file should match original size");
        }
    }

    @Test
    public void testCompressionStatistics() throws Exception {
        // Test comprehensive compression statistics
        File sourceFile = createTestFile("stats-test.jar", 2048);
        File compressedFile = new File(testDir, "stats.gz");
        
        compressionHandler.compressFile(sourceFile, compressedFile);
        
        Object stats = compressionHandler.getStatistics();
        assertNotNull(stats, "Should provide compression statistics");
        
        String statsString = stats.toString();
        assertTrue(statsString.contains("compression"), "Statistics should mention compression");
        assertTrue(statsString.contains("ratio"), "Statistics should include compression ratios");
        
        // Test operation counters
        int compressionCount = compressionHandler.getCompressionCount();
        assertTrue(compressionCount > 0, "Should track compression operations");
        
        long totalBytesCompressed = compressionHandler.getTotalBytesCompressed();
        assertTrue(totalBytesCompressed >= sourceFile.length(), "Should track total bytes compressed");
        
        double averageRatio = compressionHandler.getAverageCompressionRatio();
        assertTrue(averageRatio > 0 && averageRatio <= 1.0, "Average compression ratio should be valid");
    }

    // Helper methods
    private File createTestFile(String name, int sizeBytes) throws IOException {
        File file = new File(testDir, name);
        byte[] data = new byte[sizeBytes];
        // Fill with some pattern to make it compressible
        for (int i = 0; i < sizeBytes; i++) {
            data[i] = (byte) (i % 256);
        }
        Files.write(file.toPath(), data);
        return file;
    }
    
    private File createTestTextFile(String name, int sizeBytes) throws IOException {
        File file = new File(testDir, name);
        StringBuilder content = new StringBuilder();
        String pattern = "This is a test line that repeats to create compressible text content. ";
        
        while (content.length() < sizeBytes) {
            content.append(pattern);
        }
        
        Files.write(file.toPath(), content.toString().substring(0, sizeBytes).getBytes());
        return file;
    }
    
    private void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
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
