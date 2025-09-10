package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MavenMetadataHandler.
 */
class MavenMetadataHandlerTest {

    private MavenMetadataHandler metadataHandler;

    @BeforeEach
    void setUp() {
        metadataHandler = new MavenMetadataHandler();
    }

    @Test
    @DisplayName("Should generate group-level metadata with plugins")
    void testGenerateGroupMetadata() {
        // Given
        String groupId = "com.example.plugins";
        List<MavenMetadataHandler.PluginInfo> plugins = Arrays.asList(
            new MavenMetadataHandler.PluginInfo("Example Plugin", "example", "example-maven-plugin"),
            new MavenMetadataHandler.PluginInfo("Test Plugin", "test", "test-maven-plugin")
        );

        // When
        String metadataXml = metadataHandler.generateGroupMetadata(groupId, plugins);

        // Then
        assertNotNull(metadataXml);
        assertTrue(metadataXml.contains("<groupId>com.example.plugins</groupId>"));
        assertTrue(metadataXml.contains("<name>Example Plugin</name>"));
        assertTrue(metadataXml.contains("<prefix>example</prefix>"));
        assertTrue(metadataXml.contains("<artifactId>example-maven-plugin</artifactId>"));
        assertTrue(metadataXml.contains("<name>Test Plugin</name>"));
        assertTrue(metadataXml.contains("<prefix>test</prefix>"));
        assertTrue(metadataXml.contains("<artifactId>test-maven-plugin</artifactId>"));
    }

    @Test
    @DisplayName("Should generate group-level metadata with empty plugins")
    void testGenerateGroupMetadataEmpty() {
        // Given
        String groupId = "com.example.empty";
        List<MavenMetadataHandler.PluginInfo> plugins = Arrays.asList();

        // When
        String metadataXml = metadataHandler.generateGroupMetadata(groupId, plugins);

        // Then
        assertNotNull(metadataXml);
        assertTrue(metadataXml.contains("<groupId>com.example.empty</groupId>"));
        assertFalse(metadataXml.contains("<plugin>"));
    }

    @Test
    @DisplayName("Should generate artifact-level metadata with versions")
    void testGenerateArtifactMetadata() {
        // Given
        String groupId = "com.example";
        String artifactId = "test-artifact";
        List<String> versions = Arrays.asList("1.0.0", "1.1.0", "2.0.0-SNAPSHOT", "1.2.0");

        // When
        String metadataXml = metadataHandler.generateArtifactMetadata(groupId, artifactId, versions);

        // Then
        assertNotNull(metadataXml);
        assertTrue(metadataXml.contains("<groupId>com.example</groupId>"));
        assertTrue(metadataXml.contains("<artifactId>test-artifact</artifactId>"));
        assertTrue(metadataXml.contains("<version>1.0.0</version>"));
        assertTrue(metadataXml.contains("<version>1.1.0</version>"));
        assertTrue(metadataXml.contains("<version>1.2.0</version>"));
        assertTrue(metadataXml.contains("<version>2.0.0-SNAPSHOT</version>"));
        assertTrue(metadataXml.contains("<latest>2.0.0-SNAPSHOT</latest>"));
        assertTrue(metadataXml.contains("<release>1.2.0</release>"));
        assertTrue(metadataXml.contains("<lastUpdated>"));
    }

    @Test
    @DisplayName("Should generate artifact-level metadata with only SNAPSHOT versions")
    void testGenerateArtifactMetadataOnlySnapshots() {
        // Given
        String groupId = "com.example";
        String artifactId = "snapshot-artifact";
        List<String> versions = Arrays.asList("1.0.0-SNAPSHOT", "2.0.0-SNAPSHOT");

        // When
        String metadataXml = metadataHandler.generateArtifactMetadata(groupId, artifactId, versions);

        // Then
        assertNotNull(metadataXml);
        assertTrue(metadataXml.contains("<latest>2.0.0-SNAPSHOT</latest>"));
        assertFalse(metadataXml.contains("<release>"));
    }

    @Test
    @DisplayName("Should generate version-level metadata for SNAPSHOT")
    void testGenerateVersionMetadata() {
        // Given
        String groupId = "com.example";
        String artifactId = "test-artifact";
        String version = "1.0.0-SNAPSHOT";
        List<MavenMetadataHandler.SnapshotVersionInfo> snapshotVersions = Arrays.asList(
            new MavenMetadataHandler.SnapshotVersionInfo(null, "jar", "1.0.0-20231201.120000-1", "20231201120000", "20231201.120000", 1),
            new MavenMetadataHandler.SnapshotVersionInfo(null, "pom", "1.0.0-20231201.120000-1", "20231201120000", "20231201.120000", 1)
        );

        // When
        String metadataXml = metadataHandler.generateVersionMetadata(groupId, artifactId, version, snapshotVersions);

        // Then
        assertNotNull(metadataXml);
        assertTrue(metadataXml.contains("<groupId>com.example</groupId>"));
        assertTrue(metadataXml.contains("<artifactId>test-artifact</artifactId>"));
        assertTrue(metadataXml.contains("<version>1.0.0-SNAPSHOT</version>"));
        assertTrue(metadataXml.contains("<timestamp>20231201.120000</timestamp>"));
        assertTrue(metadataXml.contains("<buildNumber>1</buildNumber>"));
        assertTrue(metadataXml.contains("<extension>jar</extension>"));
        assertTrue(metadataXml.contains("<extension>pom</extension>"));
        assertTrue(metadataXml.contains("<value>1.0.0-20231201.120000-1</value>"));
    }

    @Test
    @DisplayName("Should parse group metadata correctly")
    void testParseGroupMetadata() {
        // Given
        String xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<metadata>\n" +
            "  <groupId>com.example</groupId>\n" +
            "  <plugins>\n" +
            "    <plugin>\n" +
            "      <name>Test Plugin</name>\n" +
            "      <prefix>test</prefix>\n" +
            "      <artifactId>test-maven-plugin</artifactId>\n" +
            "    </plugin>\n" +
            "  </plugins>\n" +
            "</metadata>";

        // When
        Map<String, Object> metadata = metadataHandler.parseMetadata(xmlContent, MavenMetadataHandler.MetadataType.GROUP);

        // Then
        assertNotNull(metadata);
        assertEquals("com.example", metadata.get("groupId"));
    }

    @Test
    @DisplayName("Should parse artifact metadata correctly")
    void testParseArtifactMetadata() {
        // Given
        String xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<metadata>\n" +
            "  <groupId>com.example</groupId>\n" +
            "  <artifactId>test-artifact</artifactId>\n" +
            "  <versioning>\n" +
            "    <latest>1.2.0</latest>\n" +
            "    <release>1.2.0</release>\n" +
            "    <versions>\n" +
            "      <version>1.0.0</version>\n" +
            "      <version>1.1.0</version>\n" +
            "      <version>1.2.0</version>\n" +
            "    </versions>\n" +
            "    <lastUpdated>20231201120000</lastUpdated>\n" +
            "  </versioning>\n" +
            "</metadata>";

        // When
        Map<String, Object> metadata = metadataHandler.parseMetadata(xmlContent, MavenMetadataHandler.MetadataType.ARTIFACT);

        // Then
        assertNotNull(metadata);
        assertEquals("com.example", metadata.get("groupId"));
        assertEquals("test-artifact", metadata.get("artifactId"));
        assertEquals("1.2.0", metadata.get("latest"));
        assertEquals("1.2.0", metadata.get("release"));
    }

    @Test
    @DisplayName("Should handle null metadata type")
    void testParseMetadataInvalidType() {
        // Given
        String xmlContent = "<metadata></metadata>";

        // When & Then - simplified parsing doesn't throw for null type
        Map<String, Object> result = metadataHandler.parseMetadata(xmlContent, null);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should handle malformed XML gracefully")
    void testParseMetadataMalformedXml() {
        // Given
        String xmlContent = "<metadata><invalid></metadata>";

        // When & Then - simplified parsing handles malformed XML gracefully
        Map<String, Object> result = metadataHandler.parseMetadata(xmlContent, MavenMetadataHandler.MetadataType.GROUP);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should sort versions correctly")
    void testVersionSorting() {
        // Given
        String groupId = "com.example";
        String artifactId = "test-artifact";
        List<String> versions = Arrays.asList("1.10.0", "1.2.0", "1.1.0", "2.0.0-SNAPSHOT", "1.9.0");

        // When
        String metadataXml = metadataHandler.generateArtifactMetadata(groupId, artifactId, versions);

        // Then
        assertNotNull(metadataXml);
        // The latest should be 2.0.0-SNAPSHOT (highest version)
        assertTrue(metadataXml.contains("<latest>2.0.0-SNAPSHOT</latest>"));
        // The release should be 1.10.0 (highest non-SNAPSHOT)
        assertTrue(metadataXml.contains("<release>1.10.0</release>"));
    }

    @Test
    @DisplayName("Should handle empty version list")
    void testEmptyVersionList() {
        // Given
        String groupId = "com.example";
        String artifactId = "empty-artifact";
        List<String> versions = Arrays.asList();

        // When
        String metadataXml = metadataHandler.generateArtifactMetadata(groupId, artifactId, versions);

        // Then
        assertNotNull(metadataXml);
        assertTrue(metadataXml.contains("<groupId>com.example</groupId>"));
        assertTrue(metadataXml.contains("<artifactId>empty-artifact</artifactId>"));
        assertFalse(metadataXml.contains("<versioning>"));
    }

    @Test
    @DisplayName("Should handle null version list")
    void testNullVersionList() {
        // Given
        String groupId = "com.example";
        String artifactId = "null-artifact";
        List<String> versions = null;

        // When
        String metadataXml = metadataHandler.generateArtifactMetadata(groupId, artifactId, versions);

        // Then
        assertNotNull(metadataXml);
        assertTrue(metadataXml.contains("<groupId>com.example</groupId>"));
        assertTrue(metadataXml.contains("<artifactId>null-artifact</artifactId>"));
        assertFalse(metadataXml.contains("<versioning>"));
    }
}
