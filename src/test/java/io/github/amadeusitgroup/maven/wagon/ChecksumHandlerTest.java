package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChecksumHandler.
 */
class ChecksumHandlerTest {

    private ChecksumHandler checksumHandler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        checksumHandler = new ChecksumHandler();
    }

    @Test
    @DisplayName("Should generate checksums for a file")
    void testGenerateChecksums() throws IOException {
        // Given
        File testFile = tempDir.resolve("test-artifact.jar").toFile();
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("This is test content for checksum generation");
        }

        // When
        Map<String, String> checksums = checksumHandler.generateChecksums(testFile);

        // Then
        assertNotNull(checksums);
        assertEquals(3, checksums.size()); // MD5, SHA-1, SHA-256
        assertTrue(checksums.containsKey("MD5"));
        assertTrue(checksums.containsKey("SHA-1"));
        assertTrue(checksums.containsKey("SHA-256"));
        
        // Verify checksum format (hex strings)
        assertTrue(checksums.get("MD5").matches("[a-f0-9]{32}"));
        assertTrue(checksums.get("SHA-1").matches("[a-f0-9]{40}"));
        assertTrue(checksums.get("SHA-256").matches("[a-f0-9]{64}"));
    }

    @Test
    @DisplayName("Should generate specific algorithm checksums")
    void testGenerateSpecificChecksums() throws IOException {
        // Given
        File testFile = tempDir.resolve("test-artifact.jar").toFile();
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content");
        }

        // When
        Map<String, String> checksums = checksumHandler.generateChecksums(testFile, "MD5", "SHA-1");

        // Then
        assertNotNull(checksums);
        assertEquals(2, checksums.size());
        assertTrue(checksums.containsKey("MD5"));
        assertTrue(checksums.containsKey("SHA-1"));
        assertFalse(checksums.containsKey("SHA-256"));
    }

    @Test
    @DisplayName("Should generate checksum files")
    void testGenerateChecksumFiles() throws IOException {
        // Given
        File testFile = tempDir.resolve("test-artifact.jar").toFile();
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content for checksum files");
        }

        // When
        Map<String, String> checksumFiles = checksumHandler.generateChecksumFiles(testFile);

        // Then
        assertNotNull(checksumFiles);
        assertEquals(3, checksumFiles.size());
        
        // Verify checksum files were created
        File md5File = new File(checksumFiles.get("MD5"));
        File sha1File = new File(checksumFiles.get("SHA-1"));
        File sha256File = new File(checksumFiles.get("SHA-256"));
        
        assertTrue(md5File.exists());
        assertTrue(sha1File.exists());
        assertTrue(sha256File.exists());
        
        assertTrue(md5File.getName().endsWith(".md5"));
        assertTrue(sha1File.getName().endsWith(".sha1"));
        assertTrue(sha256File.getName().endsWith(".sha256"));
    }

    @Test
    @DisplayName("Should validate checksum correctly")
    void testValidateChecksum() throws IOException {
        // Given
        File testFile = tempDir.resolve("test-artifact.jar").toFile();
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content for validation");
        }
        
        Map<String, String> checksums = checksumHandler.generateChecksums(testFile, "MD5");
        String expectedChecksum = checksums.get("MD5");

        // When
        boolean isValid = checksumHandler.validateChecksum(testFile, expectedChecksum, "MD5");

        // Then
        assertTrue(isValid);
    }

    @Test
    @DisplayName("Should fail validation with wrong checksum")
    void testValidateChecksumWrong() throws IOException {
        // Given
        File testFile = tempDir.resolve("test-artifact.jar").toFile();
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content");
        }
        
        String wrongChecksum = "wrongchecksumvalue123456789012345";

        // When
        boolean isValid = checksumHandler.validateChecksum(testFile, wrongChecksum, "MD5");

        // Then
        assertFalse(isValid);
    }

    @Test
    @DisplayName("Should read checksum from file")
    void testReadChecksumFile() throws IOException {
        // Given
        File checksumFile = tempDir.resolve("test.md5").toFile();
        String expectedChecksum = "d41d8cd98f00b204e9800998ecf8427e";
        try (FileWriter writer = new FileWriter(checksumFile)) {
            writer.write(expectedChecksum);
        }

        // When
        String actualChecksum = checksumHandler.readChecksumFile(checksumFile);

        // Then
        assertEquals(expectedChecksum, actualChecksum);
    }

    @Test
    @DisplayName("Should read checksum from file with filename")
    void testReadChecksumFileWithFilename() throws IOException {
        // Given
        File checksumFile = tempDir.resolve("test.md5").toFile();
        String expectedChecksum = "d41d8cd98f00b204e9800998ecf8427e";
        try (FileWriter writer = new FileWriter(checksumFile)) {
            writer.write(expectedChecksum + "  test-artifact.jar");
        }

        // When
        String actualChecksum = checksumHandler.readChecksumFile(checksumFile);

        // Then
        assertEquals(expectedChecksum, actualChecksum);
    }

    @Test
    @DisplayName("Should validate against checksum file")
    void testValidateAgainstChecksumFile() throws IOException {
        // Given
        File testFile = tempDir.resolve("test-artifact.jar").toFile();
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Test content");
        }
        
        // Generate checksum and create checksum file
        Map<String, String> checksums = checksumHandler.generateChecksums(testFile, "MD5");
        String checksum = checksums.get("MD5");
        
        File checksumFile = tempDir.resolve("test-artifact.jar.md5").toFile();
        try (FileWriter writer = new FileWriter(checksumFile)) {
            writer.write(checksum);
        }

        // When
        boolean isValid = checksumHandler.validateAgainstChecksumFile(testFile, checksumFile, "MD5");

        // Then
        assertTrue(isValid);
    }

    @Test
    @DisplayName("Should get correct file extensions")
    void testGetChecksumFileExtension() {
        assertEquals("md5", checksumHandler.getChecksumFileExtension("MD5"));
        assertEquals("sha1", checksumHandler.getChecksumFileExtension("SHA-1"));
        assertEquals("sha256", checksumHandler.getChecksumFileExtension("SHA-256"));
        assertEquals("sha512", checksumHandler.getChecksumFileExtension("SHA-512"));
    }

    @Test
    @DisplayName("Should get algorithm from extension")
    void testGetAlgorithmFromExtension() {
        assertEquals("MD5", checksumHandler.getAlgorithmFromExtension("md5"));
        assertEquals("SHA-1", checksumHandler.getAlgorithmFromExtension("sha1"));
        assertEquals("SHA-256", checksumHandler.getAlgorithmFromExtension("sha256"));
        assertEquals("MD5", checksumHandler.getAlgorithmFromExtension(".md5"));
        assertEquals("SHA-1", checksumHandler.getAlgorithmFromExtension(".sha1"));
        assertEquals("SHA-256", checksumHandler.getAlgorithmFromExtension(".sha256"));
    }

    @Test
    @DisplayName("Should throw exception for unknown extension")
    void testGetAlgorithmFromUnknownExtension() {
        assertThrows(IllegalArgumentException.class, () -> {
            checksumHandler.getAlgorithmFromExtension("unknown");
        });
    }

    @Test
    @DisplayName("Should identify checksum files correctly")
    void testIsChecksumFile() {
        assertTrue(checksumHandler.isChecksumFile("artifact-1.0.0.jar.md5"));
        assertTrue(checksumHandler.isChecksumFile("artifact-1.0.0.jar.sha1"));
        assertTrue(checksumHandler.isChecksumFile("artifact-1.0.0.jar.sha256"));
        assertTrue(checksumHandler.isChecksumFile("ARTIFACT-1.0.0.JAR.MD5"));
        
        assertFalse(checksumHandler.isChecksumFile("artifact-1.0.0.jar"));
        assertFalse(checksumHandler.isChecksumFile("artifact-1.0.0.pom"));
        assertFalse(checksumHandler.isChecksumFile("maven-metadata.xml"));
    }

    @Test
    @DisplayName("Should get artifact filename from checksum filename")
    void testGetArtifactFilename() {
        assertEquals("artifact-1.0.0.jar", checksumHandler.getArtifactFilename("artifact-1.0.0.jar.md5"));
        assertEquals("artifact-1.0.0.jar", checksumHandler.getArtifactFilename("artifact-1.0.0.jar.sha1"));
        assertEquals("artifact-1.0.0.jar", checksumHandler.getArtifactFilename("artifact-1.0.0.jar.sha256"));
        assertEquals("artifact-1.0.0.pom", checksumHandler.getArtifactFilename("artifact-1.0.0.pom.md5"));
        
        // Should return original filename if not a checksum file
        assertEquals("artifact-1.0.0.jar", checksumHandler.getArtifactFilename("artifact-1.0.0.jar"));
    }

    @Test
    @DisplayName("Should handle unsupported algorithm")
    void testUnsupportedAlgorithm() {
        // Given
        File testFile = tempDir.resolve("test.jar").toFile();

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            checksumHandler.generateChecksums(testFile, "UNSUPPORTED");
        });
    }

    @Test
    @DisplayName("Should handle non-existent file")
    void testNonExistentFile() {
        // Given
        File nonExistentFile = tempDir.resolve("non-existent.jar").toFile();

        // When & Then
        assertThrows(IOException.class, () -> {
            checksumHandler.generateChecksums(nonExistentFile);
        });
    }

    @Test
    @DisplayName("Should generate consistent checksums")
    void testConsistentChecksums() throws IOException {
        // Given
        File testFile = tempDir.resolve("test-artifact.jar").toFile();
        try (FileWriter writer = new FileWriter(testFile)) {
            writer.write("Consistent content");
        }

        // When
        Map<String, String> checksums1 = checksumHandler.generateChecksums(testFile, "MD5");
        Map<String, String> checksums2 = checksumHandler.generateChecksums(testFile, "MD5");

        // Then
        assertEquals(checksums1.get("MD5"), checksums2.get("MD5"));
    }

    @Test
    @DisplayName("Should generate different checksums for different content")
    void testDifferentChecksums() throws IOException {
        // Given
        File testFile1 = tempDir.resolve("test1.jar").toFile();
        File testFile2 = tempDir.resolve("test2.jar").toFile();
        
        try (FileWriter writer = new FileWriter(testFile1)) {
            writer.write("Content 1");
        }
        try (FileWriter writer = new FileWriter(testFile2)) {
            writer.write("Content 2");
        }

        // When
        Map<String, String> checksums1 = checksumHandler.generateChecksums(testFile1, "MD5");
        Map<String, String> checksums2 = checksumHandler.generateChecksums(testFile2, "MD5");

        // Then
        assertNotEquals(checksums1.get("MD5"), checksums2.get("MD5"));
    }
}
