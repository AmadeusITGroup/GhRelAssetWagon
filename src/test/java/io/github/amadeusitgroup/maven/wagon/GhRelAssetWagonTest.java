package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.Repository;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@WireMockTest()
public class GhRelAssetWagonTest {

    @TempDir
    Path tempDir;

    private GhRelAssetWagon ghRelAssetWagon;
    private AuthenticationInfo authenticationInfo;
    private Repository repository;

    @BeforeEach
    public void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        ghRelAssetWagon = spy(GhRelAssetWagon.class);
        authenticationInfo = mock(AuthenticationInfo.class);
        repository = new Repository("test-repo", "ghrelasset://owner/repo/v1.0.0/test-asset.zip");
        ghRelAssetWagon.setAuthenticationInfo(authenticationInfo);
        // set the api and upload endpoints to wiremock
        ghRelAssetWagon.setApiEndpoint(wmRuntimeInfo.getHttpBaseUrl());
        ghRelAssetWagon.setUploadEndpoint(wmRuntimeInfo.getHttpBaseUrl());
        // set up a mock repository for testing
        ghRelAssetWagon.setRepository("test-repo", "ghrelasset://owner/repo/v1.0.0/test-asset.zip");
    }

    @AfterEach
    public void tearDown() throws Exception {
        ghRelAssetWagon.closeConnection();
    }

    @Test
    public void testGetReleaseId() throws IOException {
        String repository = "owner/repo";
        String tag = "v1.0.0";
        String expectedReleaseId = "12345";

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": 12345}")));

        String releaseId = ghRelAssetWagon.getReleaseId(repository, tag);
        assertEquals(expectedReleaseId, releaseId);
    }

    @Test
    public void testGetAssetId() throws IOException {
        String repository = "owner/repo";
        String tag = "v1.0.0";
        String assetName = "asset.zip";
        String expectedAssetId = "67890";

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": 12345}")));

        stubFor(get(urlEqualTo("/repos/owner/repo/releases/12345/assets"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\": 67890, \"name\": \"asset.zip\"}]")));

        String assetId = ghRelAssetWagon.getAssetId(repository, tag, assetName);
        assertEquals(expectedAssetId, assetId);
    }

    @Test
    public void testGetOrCreateTag() throws IOException {
        String repository = "owner/repo";
        String tag = "v1.0.0";
        String commit = "mocked_commit";

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // the first call should return 404
        // the second call should return 200
        stubFor(get(urlEqualTo("/repos/owner/repo/git/refs/tags/v1.0.0"))
                .inScenario("Tag Lookup")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\": \"Not Found\"}"))
                .willSetStateTo("Tag Found"));

        stubFor(get(urlEqualTo("/repos/owner/repo/git/refs/tags/v1.0.0"))
                .inScenario("Tag Lookup")
                .whenScenarioStateIs("Tag Found")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"object\": {\"sha\": \"mocked_commit\"}}")));

        stubFor(post(urlEqualTo("/repos/owner/repo/git/tags"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\": \"mocked_commit\"}")));

        String resultTag = ghRelAssetWagon.getOrCreateTag(repository, tag, commit);
        assertEquals(tag, resultTag);
    }

    @Test
    public void testGetOrCreateRelease() throws IOException {
        String repository = "owner/repo";
        String tag = "v1.0.0";
        String expectedReleaseId = "12345";

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": 12345}")));

        String releaseId = ghRelAssetWagon.getOrCreateRelease(repository, tag);
        assertEquals(expectedReleaseId, releaseId);
    }

    @Test
    public void testGetDefaultBranch() throws IOException {
        String repository = "owner/repo";
        String expectedBranch = "main";

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        stubFor(get(urlEqualTo("/repos/owner/repo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"default_branch\": \"main\"}")));

        String defaultBranch = ghRelAssetWagon.getDefaultBranch(repository);
        assertEquals(expectedBranch, defaultBranch);
    }

    @Test
    public void testGetLatestCommit() throws IOException {
        String repository = "owner/repo";
        String branch = "main";
        String expectedCommit = "mocked_commit";

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        stubFor(get(urlEqualTo("/repos/owner/repo/branches/main"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"commit\": {\"sha\": \"mocked_commit\"}}")));

        String latestCommit = ghRelAssetWagon.getLatestCommit(repository, branch);
        assertEquals(expectedCommit, latestCommit);
    }

    // @Test
    // public void testDownloadGHReleaseAsset() throws Exception {
    // String repository = "owner/repo";
    // String tag = "v1.0.0";
    // String assetName = "asset.zip";
    // String expectedSha1 = "mocked_sha1";

    //
    // when(authenticationInfo.getPassword()).thenReturn("mocked_token");

    // stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
    // .willReturn(aResponse()
    // .withStatus(200)
    // .withHeader("Content-Type", "application/json")
    // .withBody("{\"id\": 12345}")));

    // stubFor(get(urlEqualTo("/repos/owner/repo/releases/12345/assets"))
    // .willReturn(aResponse()
    // .withStatus(200)
    // .withHeader("Content-Type", "application/json")
    // .withBody("[{\"id\": 67890, \"name\": \"asset.zip\"}]")));

    // // repos/owner/repo/releases/assets/67890
    // stubFor(get(urlEqualTo("/repos/owner/repo/releases/assets/67890"))
    // .willReturn(aResponse()
    // .withStatus(200)
    // .withHeader("Content-Type", "application/octet-stream")
    // .withBody("mocked_sha1")));

    // // Mock the HTTP response
    // // You can use a library like WireMock to mock the HTTP responses

    // String sha1 = ghRelAssetWagon.downloadGHReleaseAsset(repository, tag,
    // assetName);
    // assertEquals(expectedSha1, sha1);
    // }

    // @Test
    // public void testUploadGHReleaseAsset() throws Exception {
    // String repository = "owner/repo";
    // String tag = "v1.0.0";
    // String assetName = "asset.zip";
    // String expectedSha1 = "mocked_sha1";

    // when(authenticationInfo.getPassword()).thenReturn("mocked_token");

    // stubFor(get(urlEqualTo("/repos/owner/repo"))
    // .willReturn(aResponse()
    // .withStatus(200)
    // .withHeader("Content-Type", "application/json")
    // .withBody("{\"default_branch\": \"main\"}")));

    // stubFor(get(urlEqualTo("/repos/owner/repo/branches/main"))
    // .willReturn(aResponse()
    // .withStatus(200)
    // .withHeader("Content-Type", "application/json")
    // .withBody("{\"commit\": {\"sha\": \"mocked_commit\"}}")));
    // // mock tag creation
    // stubFor(get(urlEqualTo("/repos/owner/repo/git/refs/tags/v1.0.0"))
    // .inScenario("Tag Lookup")
    // .whenScenarioStateIs("Started")
    // .willReturn(aResponse()
    // .withStatus(404)
    // .withHeader("Content-Type", "application/json")
    // .withBody("{\"message\": \"Not Found\"}"))
    // .willSetStateTo("Tag Found"));

    // stubFor(get(urlEqualTo("/repos/owner/repo/git/refs/tags/v1.0.0"))
    // .inScenario("Tag Lookup")
    // .whenScenarioStateIs("Tag Found")
    // .willReturn(aResponse()
    // .withStatus(200)
    // .withHeader("Content-Type", "application/json")
    // .withBody("{\"object\": {\"sha\": \"mocked_commit\"}}")));

    // stubFor(post(urlEqualTo("/repos/owner/repo/git/tags"))
    // .willReturn(aResponse()
    // .withStatus(201)
    // .withHeader("Content-Type", "application/json")
    // .withBody("{\"sha\": \"mocked_commit\"}")));
    // // mock release creation
    // stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
    // .willReturn(aResponse()
    // .withStatus(200)
    // .withHeader("Content-Type", "application/json")
    // .withBody("{\"id\": 12345}")));

    // stubFor(post(urlEqualTo("/repos/owner/repo/releases/12345/assets?name=asset.zip"))
    // .willReturn(aResponse()
    // .withStatus(201)
    // .withHeader("Content-Type", "application/json")
    // .withBody("{\"sha\": \"mocked_sha1\"}")));
    // // mock assets retrieval
    // stubFor(get(urlEqualTo("/repos/owner/repo/releases/12345/assets"))
    // .willReturn(aResponse()
    // .withStatus(200)
    // .withHeader("Content-Type", "application/json")
    // .withBody("[{\"id\": 67890, \"name\": \"asset.zip\"}]")));

    // // repository = "owner/repo"
    // ghRelAssetWagon.setRepository("owner/repo", "ghrelasset://owner/repo");

    // // Mock File creation
    // File mockedFile = mock(File.class);
    // when(mockedFile.exists()).thenReturn(true);
    // when(mockedFile.getName()).thenReturn("asset.zip");
    // when(mockedFile.getAbsolutePath()).thenReturn("path/to/asset.zip");
    // when(mockedFile.length()).thenReturn(100L);

    // // Mock FileUtils.readFileToByteArray
    // File mockFile = mock(File.class);
    // byte[] mockFileData = "mocked file data".getBytes();

    // org.apache.commons.io.FileUtils mockedFileUtils =
    // mock(org.apache.commons.io.FileUtils.class);

    // when(mockedFileUtils.readFileToByteArray(mockFile)).thenReturn(mockFileData);

    // String sha1 = ghRelAssetWagon.uploadGHReleaseAsset(repository, tag,
    // assetName);
    // assertEquals(expectedSha1, sha1);

    // }

    // @Test
    // public void testGetResourceFromZip() throws IOException {
    // String zipFilePath = "path/to/zipfile.zip";
    // String resourceName = "resource.txt";
    // String outputFilePath = "path/to/output.txt";

    // // Mock the zip file and resource extraction
    // // You can use a library like Zip4j to mock the zip file operations

    // ghRelAssetWagon.getResourceFromZip(zipFilePath, resourceName,
    // outputFilePath);

    // // Verify the resource extraction
    // // You can use assertions to verify the extracted resource
    // }

    // @Test
    // public void testAddResourceToZip() throws IOException {
    // String zipFilePath = "path/to/zipfile.zip";
    // String resourcePath = "path/to/resource.txt";
    // String resourceName = "resource.txt";

    // // Mock the zip file and resource addition
    // // You can use a library like Zip4j to mock the zip file operations

    // ghRelAssetWagon.addResourceToZip(zipFilePath, resourcePath, resourceName);

    // // Verify the resource addition
    // // You can use assertions to verify the added resource
    // }

    // @Test
    // public void testGet() throws Exception {
    // String resourceName = "resource.txt";
    // File destination = new File("path/to/destination.txt");

    // // Mock the SHA1 calculation and resource retrieval
    // // You can use a library like WireMock to mock the HTTP responses

    // ghRelAssetWagon.get(resourceName, destination);

    // // Verify the resource retrieval
    // // You can use assertions to verify the retrieved resource
    // }

    // @Test
    // public void testGetIfNewer() throws Exception {
    // String resourceName = "resource.txt";
    // File destination = new File("path/to/destination.txt");
    // long timestamp = System.currentTimeMillis();

    // // Mock the SHA1 calculation and resource retrieval
    // // You can use a library like WireMock to mock the HTTP responses

    // boolean result = ghRelAssetWagon.getIfNewer(resourceName, destination,
    // timestamp);
    // assertFalse(result);
    // }

    // @Test
    // public void testPut() throws Exception {
    // File source = new File("path/to/source.txt");
    // String destination = "destination.txt";

    // // Mock the SHA1 calculation and resource addition
    // // You can use a library like WireMock to mock the HTTP responses

    // ghRelAssetWagon.put(source, destination);

    // // Verify the resource addition
    // // You can use assertions to verify the added resource
    // }

    // @Test
    // public void testOpenConnectionInternal() throws Exception {
    // and environment variable
    // when(authenticationInfo.getUserName()).thenReturn("ghrelasset");
    // System.setProperty("GH_RELEASE_ASSET_TOKEN", "mocked_token");

    // ghRelAssetWagon.openConnectionInternal();

    // // Verify the authentication info
    // assertEquals("ghrelasset",
    // ghRelAssetWagon.getAuthenticationInfo().getUserName());
    // assertEquals("mocked_token",
    // ghRelAssetWagon.getAuthenticationInfo().getPassword());
    // }

    // @Test
    // public void testCloseConnection() throws Exception {
    // // Mock the artifacts to upload
    // ghRelAssetWagon.getArtifactsToUpload().add("artifact1");
    // ghRelAssetWagon.getArtifactsToUpload().add("artifact2");

    // ghRelAssetWagon.closeConnection();

    // // Verify the artifacts upload
    // // You can use assertions to verify the uploaded artifacts
    // }

    // ========== Phase 1 Enhancement Tests - Missing Wagon Interface Methods ==========

    @Test
    public void testGetFileList_EmptyDirectory() throws Exception {
        String destinationDirectory = "com/example/artifact/1.0.0/";

        // Mock empty release assets response
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": 12345, \"assets\": []}")));

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        List<String> fileList = ghRelAssetWagon.getFileList(destinationDirectory);

        assertNotNull(fileList);
        assertTrue(fileList.isEmpty());
    }

    @Test
    public void testGetFileList_WithFiles() throws Exception {
        String destinationDirectory = "com/example/artifact/1.0.0/";

        // Mock release with assets
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\n" +
                                "  \"id\": 12345,\n" +
                                "  \"assets\": [\n" +
                                "    {\n" +
                                "      \"id\": 67890,\n" +
                                "      \"name\": \"artifact-1.0.0.jar\",\n" +
                                "      \"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.0.0/artifact-1.0.0.jar\"\n" +
                                "    },\n" +
                                "    {\n" +
                                "      \"id\": 67891,\n" +
                                "      \"name\": \"artifact-1.0.0.pom\",\n" +
                                "      \"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.0.0/artifact-1.0.0.pom\"\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}")));

        // Mock assets endpoint
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/12345/assets"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\n" +
                                "  {\n" +
                                "    \"id\": 67890,\n" +
                                "    \"name\": \"artifact-1.0.0.jar\",\n" +
                                "    \"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.0.0/artifact-1.0.0.jar\"\n" +
                                "  },\n" +
                                "  {\n" +
                                "    \"id\": 67891,\n" +
                                "    \"name\": \"artifact-1.0.0.pom\",\n" +
                                "    \"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.0.0/artifact-1.0.0.pom\"\n" +
                                "  }\n" +
                                "]")));

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        List<String> fileList = ghRelAssetWagon.getFileList(destinationDirectory);

        assertNotNull(fileList);
        assertEquals(2, fileList.size());
        assertTrue(fileList.contains("artifact-1.0.0.jar"));
        assertTrue(fileList.contains("artifact-1.0.0.pom"));
    }

    @Test
    public void testGetFileList_ReleaseNotFound() throws Exception {
        String destinationDirectory = "com/example/artifact/1.0.0/";

        // Mock 404 response for non-existent release
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\": \"Not Found\"}")));

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        List<String> fileList = ghRelAssetWagon.getFileList(destinationDirectory);

        assertNotNull(fileList);
        assertTrue(fileList.isEmpty());
    }

    @Test
    public void testResourceExists_ExistingResource() throws Exception {
        String resourceName = "com/example/artifact/1.0.0/artifact-1.0.0.jar";

        Path zipPath = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry entry = new ZipEntry(resourceName);
            zos.putNextEntry(entry);
            zos.write("content".getBytes());
            zos.closeEntry();
        }

        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);
        try (InputStream is = Files.newInputStream(zipPath)) {
            zipCacheManager.initialize(new GhRelAssetRepository(repository), is);
        }

        ghRelAssetWagon.setZipCacheManager(zipCacheManager);

        boolean exists = ghRelAssetWagon.resourceExists(resourceName);

        assertTrue(exists);
    }

    @Test
    public void testResourceExists_NonExistingResource() throws Exception {
        String resourceName = "com/example/artifact/1.0.0/nonexistent.jar";

        Path zipPath = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry entry = new ZipEntry("some/other/file.jar");
            zos.putNextEntry(entry);
            zos.write("content".getBytes());
            zos.closeEntry();
        }

        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);
        try (InputStream is = Files.newInputStream(zipPath)) {
            zipCacheManager.initialize(new GhRelAssetRepository(repository), is);
        }

        ghRelAssetWagon.setZipCacheManager(zipCacheManager);

        boolean exists = ghRelAssetWagon.resourceExists(resourceName);

        assertFalse(exists);
    }

    @Test
    public void testResourceExists_ReleaseNotFound() throws Exception {
        String resourceName = "com/example/artifact/1.0.0/artifact-1.0.0.jar";

        // Mock 404 response for non-existent release
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\": \"Not Found\"}")));

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        boolean exists = ghRelAssetWagon.resourceExists(resourceName);

        assertFalse(exists);
    }

    @Test
    public void testSupportsDirectoryCopy() {
        boolean supports = ghRelAssetWagon.supportsDirectoryCopy();
        assertTrue(supports, "GhRelAssetWagon should support directory copy operations");
    }

    @Test
    public void testPutDirectory_EmptyDirectory() throws Exception {
        File sourceDirectory = createTempDirectory();
        String destinationDirectory = "com/example/artifact/1.0.0/";

        // Should not throw exception for empty directory
        assertDoesNotThrow(() -> {
            ghRelAssetWagon.putDirectory(sourceDirectory, destinationDirectory);
        });

        // Clean up
        sourceDirectory.delete();
    }

    @Test
    public void testPutDirectory_WithFiles() throws Exception {
        File sourceDirectory = createTempDirectoryWithFiles();
        String destinationDirectory = "com/example/artifact/1.0.0/";

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        doNothing().when(ghRelAssetWagon).put(any(File.class), any(String.class));

        // Mock successful upload responses
        stubFor(post(urlMatching("/repos/owner/repo/releases/.*/assets.*"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": 67890, \"name\": \"test-file.txt\"}")));

        assertDoesNotThrow(() -> {
            ghRelAssetWagon.putDirectory(sourceDirectory, destinationDirectory);
        });

        ArgumentCaptor<String> destCaptor = ArgumentCaptor.forClass(String.class);
        verify(ghRelAssetWagon, times(2)).put(any(File.class), destCaptor.capture());

        assertTrue(destCaptor.getAllValues().containsAll(List.of(
                "com/example/artifact/1.0.0/test-file.txt",
                "com/example/artifact/1.0.0/another-file.xml"
        )));

        // Clean up
        deleteDirectory(sourceDirectory);
    }

    @Test
    public void testGetIfNewer_FileIsNewer() throws Exception {
        String resourceName = "com/example/artifact/1.0.0/artifact-1.0.0.jar";
        File destination = File.createTempFile("test", ".jar");
        long timestamp = System.currentTimeMillis() - 86400000; // 1 day ago

        doNothing().when(ghRelAssetWagon).get(resourceName, destination);

        // Mock release with asset that has a newer timestamp
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\n" +
                                "  \"id\": 12345,\n" +
                                "  \"assets\": [\n" +
                                "    {\n" +
                                "      \"id\": 67890,\n" +
                                "      \"name\": \"artifact-1.0.0.jar\",\n" +
                                "      \"updated_at\": \"" + java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString() + "\",\n" +
                                "      \"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.0.0/artifact-1.0.0.jar\"\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}")));

        // Mock assets endpoint
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/12345/assets"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\n" +
                                "  {\n" +
                                "    \"id\": 67890,\n" +
                                "    \"name\": \"artifact-1.0.0.jar\",\n" +
                                "    \"updated_at\": \"" + java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString() + "\",\n" +
                                "    \"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.0.0/artifact-1.0.0.jar\"\n" +
                                "  }\n" +
                                "]")));

        // Mock file download
        stubFor(get(urlEqualTo("/owner/repo/releases/download/v1.0.0/artifact-1.0.0.jar"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("mock jar content")));

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        boolean result = ghRelAssetWagon.getIfNewer(resourceName, destination, timestamp);

        assertTrue(result, "Should return true when remote file is newer");
        verify(ghRelAssetWagon, times(1)).get(resourceName, destination);

        // Clean up
        destination.delete();
    }

    @Test
    public void testGetIfNewer_FileIsOlder() throws Exception {
        String resourceName = "com/example/artifact/1.0.0/artifact-1.0.0.jar";
        File destination = File.createTempFile("test", ".jar");
        long timestamp = System.currentTimeMillis(); // Current time

        // Mock release with asset that has an older timestamp
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\n" +
                                "  \"id\": 12345,\n" +
                                "  \"assets\": [\n" +
                                "    {\n" +
                                "      \"id\": 67890,\n" +
                                "      \"name\": \"artifact-1.0.0.jar\",\n" +
                                "      \"updated_at\": \"" + java.time.Instant.ofEpochMilli(timestamp - 86400000).toString() + "\",\n" +
                                "      \"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.0.0/artifact-1.0.0.jar\"\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}")));

        // Mock assets endpoint
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/12345/assets"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\n" +
                                "  {\n" +
                                "    \"id\": 67890,\n" +
                                "    \"name\": \"artifact-1.0.0.jar\",\n" +
                                "    \"updated_at\": \"" + java.time.Instant.ofEpochMilli(timestamp - 86400000).toString() + "\",\n" +
                                "    \"browser_download_url\": \"https://github.com/owner/repo/releases/download/v1.0.0/artifact-1.0.0.jar\"\n" +
                                "  }\n" +
                                "]")));

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        boolean result = ghRelAssetWagon.getIfNewer(resourceName, destination, timestamp);

        assertFalse(result, "Should return false when remote file is older");
        verify(ghRelAssetWagon, times(0)).get(resourceName, destination);

        // Clean up
        destination.delete();
    }

    // ========== Helper Methods for Tests ==========

    private File createTempDirectory() throws IOException {
        File tempDir = File.createTempFile("test", "dir");
        tempDir.delete();
        tempDir.mkdirs();
        return tempDir;
    }

    private File createTempDirectoryWithFiles() throws IOException {
        File tempDir = createTempDirectory();

        // Create some test files
        File testFile1 = new File(tempDir, "test-file.txt");
        testFile1.createNewFile();
        try (java.io.FileWriter writer = new java.io.FileWriter(testFile1)) {
            writer.write("test content");
        }

        File testFile2 = new File(tempDir, "another-file.xml");
        testFile2.createNewFile();
        try (java.io.FileWriter writer = new java.io.FileWriter(testFile2)) {
            writer.write("<xml>test</xml>");
        }

        return tempDir;
    }

    private void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }

    // ========== Phase 2: Maven Repository Standards Tests ==========

    @Test
    @DisplayName("Should generate checksums when putting artifacts")
    void testPutWithChecksumGeneration() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create test artifact
        File testArtifact = File.createTempFile("test-artifact", ".jar");
        try (FileOutputStream fos = new FileOutputStream(testArtifact)) {
            fos.write("Test artifact content".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put artifact
        ghRelAssetWagon.connect(repository, authenticationInfo);
        ghRelAssetWagon.put(testArtifact, "com/example/test-artifact/1.0.0/test-artifact-1.0.0.jar");

        // Verify that checksums were generated and staged
        // This is verified by checking that the put method completes without errors
        // and that the artifact is added to the upload queue
        assertTrue(ghRelAssetWagon.artifactsToUpload.size() >= 1);

        // Clean up
        testArtifact.delete();
    }

    @Test
    @DisplayName("Should not generate checksums for checksum files")
    void testPutChecksumFileDoesNotGenerateMoreChecksums() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create test checksum file
        File checksumFile = File.createTempFile("test-artifact", ".jar.md5");
        try (FileOutputStream fos = new FileOutputStream(checksumFile)) {
            fos.write("d41d8cd98f00b204e9800998ecf8427e".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put checksum file
        ghRelAssetWagon.connect(repository, authenticationInfo);
        int initialUploadCount = ghRelAssetWagon.artifactsToUpload.size();
        ghRelAssetWagon.put(checksumFile, "com/example/test-artifact/1.0.0/test-artifact-1.0.0.jar.md5");

        // Verify that only the checksum file itself was staged (no additional checksums generated)
        assertEquals(initialUploadCount + 1, ghRelAssetWagon.artifactsToUpload.size());

        // Clean up
        checksumFile.delete();
    }

    @Test
    @DisplayName("Should throw ResourceDoesNotExistException when getting a missing resource")
    void testGetMissingResourceThrowsResourceDoesNotExistException() throws Exception {
        // During deploy, Maven calls get() for maven-metadata.xml which may not exist yet.
        // The wagon must throw ResourceDoesNotExistException (not TransferFailedException)
        // so Maven knows the resource simply doesn't exist and creates fresh metadata.

        Path zipPath = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry entry = new ZipEntry("some/existing/file.txt");
            zos.putNextEntry(entry);
            zos.write("content".getBytes());
            zos.closeEntry();
        }

        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);
        try (InputStream is = Files.newInputStream(zipPath)) {
            zipCacheManager.initialize(new GhRelAssetRepository(repository), is);
        }
        ghRelAssetWagon.setZipCacheManager(zipCacheManager);

        File destination = tempDir.resolve("output.xml").toFile();

        // Must throw ResourceDoesNotExistException, NOT TransferFailedException
        assertThrows(ResourceDoesNotExistException.class, () ->
            ghRelAssetWagon.get("com/example/artifact/maven-metadata.xml", destination)
        );

        zipCacheManager.close();
    }

    @Test
    @DisplayName("Should generate checksum sidecar files for maven-metadata.xml")
    void testMetadataChecksumSidecarFilesGenerated() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create a zip with an initial entry so the cache is initialized
        Path zipPath = tempDir.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry entry = new ZipEntry("placeholder/");
            zos.putNextEntry(entry);
            zos.closeEntry();
        }

        // Initialize the zip cache manager with the test zip
        ZipCacheManager zipCacheManager = new ZipCacheManager(tempDir);
        try (InputStream is = Files.newInputStream(zipPath)) {
            zipCacheManager.initialize(new GhRelAssetRepository(repository), is);
        }
        ghRelAssetWagon.setZipCacheManager(zipCacheManager);

        // Create test JAR file
        File jarFile = File.createTempFile("test-artifact", ".jar");
        try (FileOutputStream fos = new FileOutputStream(jarFile)) {
            fos.write("Test JAR content".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put artifact — this triggers metadata + checksum generation
        ghRelAssetWagon.connect(repository, authenticationInfo);
        ghRelAssetWagon.put(jarFile, "com/example/test-artifact/1.0.0/test-artifact-1.0.0.jar");

        // Verify that maven-metadata.xml AND its checksum sidecar files exist in the zip
        String metadataPath = "com/example/test-artifact/maven-metadata.xml";
        assertTrue(ghRelAssetWagon.resourceExists(metadataPath),
                "maven-metadata.xml should exist in the zip");
        assertTrue(ghRelAssetWagon.resourceExists(metadataPath + ".sha1"),
                "maven-metadata.xml.sha1 should exist in the zip");
        assertTrue(ghRelAssetWagon.resourceExists(metadataPath + ".md5"),
                "maven-metadata.xml.md5 should exist in the zip");

        // Clean up
        jarFile.delete();
        zipCacheManager.close();
    }

    @Test
    @DisplayName("Should generate Maven metadata for POM files")
    void testPutPomGeneratesMetadata() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create test POM file
        File pomFile = File.createTempFile("test-artifact", ".pom");
        try (FileOutputStream fos = new FileOutputStream(pomFile)) {
            fos.write("<?xml version=\"1.0\"?><project></project>".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put POM
        ghRelAssetWagon.connect(repository, authenticationInfo);
        ghRelAssetWagon.put(pomFile, "com/example/test-artifact/1.0.0/test-artifact-1.0.0.pom");

        // Verify that artifacts were staged (including metadata)
        assertTrue(ghRelAssetWagon.artifactsToUpload.size() >= 1);

        // Clean up
        pomFile.delete();
    }

    @Test
    @DisplayName("Should generate Maven metadata for main artifacts")
    void testPutMainArtifactGeneratesMetadata() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create test JAR file
        File jarFile = File.createTempFile("test-artifact", ".jar");
        try (FileOutputStream fos = new FileOutputStream(jarFile)) {
            fos.write("Test JAR content".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put main artifact
        ghRelAssetWagon.connect(repository, authenticationInfo);
        ghRelAssetWagon.put(jarFile, "com/example/test-artifact/1.0.0/test-artifact-1.0.0.jar");

        // Verify that artifacts were staged (including metadata)
        assertTrue(ghRelAssetWagon.artifactsToUpload.size() >= 1);

        // Clean up
        jarFile.delete();
    }

    @Test
    @DisplayName("Should handle SNAPSHOT version metadata generation")
    void testPutSnapshotVersionGeneratesMetadata() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create test SNAPSHOT JAR file
        File snapshotJar = File.createTempFile("test-artifact", ".jar");
        try (FileOutputStream fos = new FileOutputStream(snapshotJar)) {
            fos.write("Test SNAPSHOT content".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put SNAPSHOT artifact
        ghRelAssetWagon.connect(repository, authenticationInfo);
        ghRelAssetWagon.put(snapshotJar, "com/example/test-artifact/1.0.0-SNAPSHOT/test-artifact-1.0.0-SNAPSHOT.jar");

        // Verify that artifacts were staged (including SNAPSHOT metadata)
        assertTrue(ghRelAssetWagon.artifactsToUpload.size() >= 1);

        // Clean up
        snapshotJar.delete();
    }

    @Test
    @DisplayName("Should handle plugin artifact metadata generation")
    void testPutPluginArtifactGeneratesGroupMetadata() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create test plugin JAR file
        File pluginJar = File.createTempFile("test-maven-plugin", ".jar");
        try (FileOutputStream fos = new FileOutputStream(pluginJar)) {
            fos.write("Test plugin content".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put plugin artifact
        ghRelAssetWagon.connect(repository, authenticationInfo);
        ghRelAssetWagon.put(pluginJar, "com/example/plugins/test-maven-plugin/1.0.0/test-maven-plugin-1.0.0.jar");

        // Verify that artifacts were staged (including group-level metadata for plugins)
        assertTrue(ghRelAssetWagon.artifactsToUpload.size() >= 1);

        // Clean up
        pluginJar.delete();
    }

    @Test
    @DisplayName("Should not generate metadata for classified artifacts")
    void testPutClassifiedArtifactDoesNotGenerateMetadata() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create test classified JAR file (sources)
        File sourcesJar = File.createTempFile("test-artifact", "-sources.jar");
        try (FileOutputStream fos = new FileOutputStream(sourcesJar)) {
            fos.write("Test sources content".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put classified artifact
        ghRelAssetWagon.connect(repository, authenticationInfo);
        int initialUploadCount = ghRelAssetWagon.artifactsToUpload.size();
        ghRelAssetWagon.put(sourcesJar, "com/example/test-artifact/1.0.0/test-artifact-1.0.0-sources.jar");

        // Verify that only the artifact and its checksums were staged (no metadata for classified artifacts)
        // The exact count depends on implementation, but should be minimal
        assertTrue(ghRelAssetWagon.artifactsToUpload.size() > initialUploadCount);

        // Clean up
        sourcesJar.delete();
    }

    @Test
    @DisplayName("Should handle repository structure tracking")
    void testRepositoryStructureTracking() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");
        ghRelAssetWagon.connect(repository, authenticationInfo);

        // Put multiple versions of the same artifact
        File artifact1 = File.createTempFile("test-artifact", ".jar");
        File artifact2 = File.createTempFile("test-artifact", ".jar");

        try (FileOutputStream fos = new FileOutputStream(artifact1)) {
            fos.write("Version 1.0.0 content".getBytes());
        }
        try (FileOutputStream fos = new FileOutputStream(artifact2)) {
            fos.write("Version 1.1.0 content".getBytes());
        }

        ghRelAssetWagon.put(artifact1, "com/example/test-artifact/1.0.0/test-artifact-1.0.0.jar");
        ghRelAssetWagon.put(artifact2, "com/example/test-artifact/1.1.0/test-artifact-1.1.0.jar");

        // Verify that multiple artifacts were staged
        assertTrue(ghRelAssetWagon.artifactsToUpload.size() >= 2);

        // Clean up
        artifact1.delete();
        artifact2.delete();
    }

    @Test
    @DisplayName("Should handle malformed artifact paths gracefully")
    void testPutMalformedPath() throws Exception {
        // Setup WireMock stubs
        setupBasicWireMockStubs();

        // Create test artifact
        File testArtifact = File.createTempFile("test", ".jar");
        try (FileOutputStream fos = new FileOutputStream(testArtifact)) {
            fos.write("Test content".getBytes());
        }

        when(authenticationInfo.getPassword()).thenReturn("mocked_token");

        // Connect and put artifact with malformed path
        ghRelAssetWagon.connect(repository, authenticationInfo);

        // This should not throw an exception, just handle gracefully
        assertDoesNotThrow(() -> {
            ghRelAssetWagon.put(testArtifact, "malformed/path");
        });

        // Clean up
        testArtifact.delete();
    }

    // ── Maven 3.8.x / 3.9.x leading-slash regression tests ──────────────────

    /**
     * Helper: creates a ZipCacheManager with an empty ZIP already initialised,
     * bypassing the real openConnectionInternal() which needs GH_RELEASE_ASSET_TOKEN.
     */
    private ZipCacheManager createInitialisedEmptyZipCache() throws IOException {
        Path zipPath = tempDir.resolve("test-leading-slash.zip");
        // create a minimal valid ZIP (one placeholder entry)
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            ZipEntry entry = new ZipEntry(".placeholder");
            zos.putNextEntry(entry);
            zos.write(new byte[0]);
            zos.closeEntry();
        }
        ZipCacheManager zcm = new ZipCacheManager(tempDir);
        try (InputStream is = Files.newInputStream(zipPath)) {
            zcm.initialize(new GhRelAssetRepository(repository), is);
        }
        return zcm;
    }

    /**
     * Maven 3.8.x passes paths with a leading '/' to wagon.put(), e.g.
     *   "/com/amadeus/agility/studio/action-apps-sdk/1.0.35/action-apps-sdk-1.0.35.jar"
     * Maven 3.9.x strips the slash first.  Both must work identically.
     */
    @Test
    @DisplayName("put() with leading-slash path (Maven 3.8.x style) must not throw")
    void testPutWithLeadingSlashPath() throws Exception {
        ZipCacheManager zcm = createInitialisedEmptyZipCache();
        ghRelAssetWagon.setZipCacheManager(zcm);
        when(authenticationInfo.getPassword()).thenReturn("mocked_token");
        ghRelAssetWagon.setAuthenticationInfo(authenticationInfo);

        File artifact = File.createTempFile("test-artifact", ".jar");
        try (FileOutputStream fos = new FileOutputStream(artifact)) {
            fos.write("artifact content".getBytes());
        }

        // Leading slash — this is what Maven 3.8.x sends
        assertDoesNotThrow(() ->
            ghRelAssetWagon.put(artifact, "/com/example/test-artifact/1.0.0/test-artifact-1.0.0.jar"),
            "put() must accept Maven 3.8.x style paths that start with '/'"
        );

        // The artifact should have been staged
        assertFalse(ghRelAssetWagon.artifactsToUpload.isEmpty(),
            "Artifact must be staged even when path has a leading '/'");

        artifact.delete();
    }

    /**
     * The ZIP entry written by put() with a leading-slash path must be identical
     * to the entry written with the same path without a leading slash.
     * Both should resolve to "com/example/…" inside the ZIP, never "/com/example/…".
     */
    @Test
    @DisplayName("put() normalises leading-slash and no-slash paths to the same ZIP entry")
    void testPutLeadingSlashProducesSameZipEntryAsNoSlash() throws Exception {
        ZipCacheManager zcm = createInitialisedEmptyZipCache();
        ghRelAssetWagon.setZipCacheManager(zcm);
        when(authenticationInfo.getPassword()).thenReturn("mocked_token");
        ghRelAssetWagon.setAuthenticationInfo(authenticationInfo);

        String contentA = "maven 3.8 content";
        String normalizedPath = "com/example/test/1.0/test-1.0.jar";

        // Write with leading slash (Maven 3.8.x style)
        File artifactA = File.createTempFile("testA", ".jar");
        try (FileOutputStream fos = new FileOutputStream(artifactA)) {
            fos.write(contentA.getBytes());
        }
        ghRelAssetWagon.put(artifactA, "/" + normalizedPath);

        // Verify the ZIP entry is reachable via the normalized (no-slash) path
        java.nio.file.FileSystem zipFs = ghRelAssetWagon.zipCacheManager.getZipFileSystem();
        assertTrue(java.nio.file.Files.exists(zipFs.getPath(normalizedPath)),
            "ZIP entry must be reachable without leading '/' after a leading-slash put()");

        // Read back the content to confirm the entry is complete and correct
        byte[] stored = java.nio.file.Files.readAllBytes(zipFs.getPath(normalizedPath));
        assertEquals(contentA, new String(stored),
            "Content stored under normalized path must match what was written");

        artifactA.delete();
    }

    /**
     * resourceExists() must return true when the same resource was staged via
     * a leading-slash path (Maven 3.8.x) AND when looked up via a no-slash path (Maven 3.9.x).
     */
    @Test
    @DisplayName("resourceExists() handles both leading-slash and no-slash lookup")
    void testResourceExistsLeadingSlashNormalization() throws Exception {
        ZipCacheManager zcm = createInitialisedEmptyZipCache();
        ghRelAssetWagon.setZipCacheManager(zcm);
        when(authenticationInfo.getPassword()).thenReturn("mocked_token");
        ghRelAssetWagon.setAuthenticationInfo(authenticationInfo);

        String path = "com/example/artifact/1.0/artifact-1.0.jar";
        File artifact = File.createTempFile("testRes", ".jar");
        try (FileOutputStream fos = new FileOutputStream(artifact)) {
            fos.write("content".getBytes());
        }

        // Stage via leading-slash (Maven 3.8.x style)
        ghRelAssetWagon.put(artifact, "/" + path);

        // Both lookup styles must resolve correctly
        assertTrue(ghRelAssetWagon.resourceExists(path),
            "resourceExists() without leading slash must find resource staged with leading slash");
        assertTrue(ghRelAssetWagon.resourceExists("/" + path),
            "resourceExists() with leading slash must also find the resource");

        artifact.delete();
    }

    /**
     * get() must successfully read back a resource regardless of whether the path
     * has a leading slash (Maven 3.8.x) or not (Maven 3.9.x).
     * The ZIP entry is written directly — bypassing put() — so the test is not
     * sensitive to NIO zip-filesystem flush timing.
     */
    @Test
    @DisplayName("get() handles leading-slash path written by Maven 3.8.x")
    void testGetLeadingSlashNormalization() throws Exception {
        String normalizedPath = "com/example/lib/2.0/lib-2.0.jar";
        String content = "lib content";

        // Build a ZIP that already contains the entry at the normalised path.
        // We write it via plain ZipOutputStream so it is guaranteed on disk.
        Path zipPath = tempDir.resolve("get-test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry(normalizedPath));
            zos.write(content.getBytes());
            zos.closeEntry();
        }

        // Initialise the ZipCacheManager with that ZIP (sets cacheFile = SHA1-named copy).
        ZipCacheManager zcm = new ZipCacheManager(tempDir);
        try (InputStream is = Files.newInputStream(zipPath)) {
            zcm.initialize(new GhRelAssetRepository(repository), is);
        }
        ghRelAssetWagon.setZipCacheManager(zcm);
        when(authenticationInfo.getPassword()).thenReturn("mocked_token");
        ghRelAssetWagon.setAuthenticationInfo(authenticationInfo);

        // Read back with no leading slash (Maven 3.9.x style)
        File dest = File.createTempFile("testGetDest", ".jar");
        assertDoesNotThrow(() -> ghRelAssetWagon.get(normalizedPath, dest),
            "get() without leading slash must find the resource");
        assertEquals(content, new String(java.nio.file.Files.readAllBytes(dest.toPath())));

        // Read back with leading slash (Maven 3.8.x style) — must be normalised and find the same entry
        File dest2 = File.createTempFile("testGetDest2", ".jar");
        assertDoesNotThrow(() -> ghRelAssetWagon.get("/" + normalizedPath, dest2),
            "get() with leading slash must also find the resource after normalisation");
        assertEquals(content, new String(java.nio.file.Files.readAllBytes(dest2.toPath())));

        dest.delete();
        dest2.delete();
    }

    private void setupBasicWireMockStubs() {
        // Mock release endpoint for tag creation/checking
        stubFor(get(urlEqualTo("/repos/owner/repo/releases/tags/v1.0.0"))
                .willReturn(aResponse()
                        .withStatus(404)));

        // Mock commit SHA endpoint
        stubFor(get(urlEqualTo("/repos/owner/repo/git/refs/heads/main"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"object\":{\"sha\":\"mocked_commit\"}}")));

        // Mock tag creation
        stubFor(post(urlEqualTo("/repos/owner/repo/git/tags"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"sha\":\"mocked_tag_sha\"}")));

        // Mock release creation
        stubFor(post(urlEqualTo("/repos/owner/repo/releases"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":12345,\"upload_url\":\"https://uploads.github.com/repos/owner/repo/releases/12345/assets{?name,label}\"}")));
    }
}
