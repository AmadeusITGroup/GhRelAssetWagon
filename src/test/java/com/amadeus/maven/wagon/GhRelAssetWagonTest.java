package com.amadeus.maven.wagon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import static org.mockito.Mockito.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@WireMockTest()
public class GhRelAssetWagonTest {

        private GhRelAssetWagon ghRelAssetWagon;
        private AuthenticationInfo authenticationInfo;

        @BeforeEach
        public void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
                ghRelAssetWagon = new GhRelAssetWagon();
                authenticationInfo = mock(AuthenticationInfo.class);
                ghRelAssetWagon.setAuthenticationInfo(authenticationInfo);
                // set the api and upload endpoints to wiremock
                ghRelAssetWagon.setApiEndpoint(wmRuntimeInfo.getHttpBaseUrl());
                ghRelAssetWagon.setUploadEndpoint(wmRuntimeInfo.getHttpBaseUrl());
        }

        @Test
        public void testGetSHA1() throws Exception {
                String input = "test_input";
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(input.getBytes());
                String expectedSha1 = HexFormat.of().formatHex(digest).toUpperCase();
                String sha1 = ghRelAssetWagon.getSHA1(input);
                assertEquals(expectedSha1, sha1);
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
}