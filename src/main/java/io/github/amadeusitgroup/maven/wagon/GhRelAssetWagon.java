package io.github.amadeusitgroup.maven.wagon;

import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.StandardCopyOption;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.xml.bind.DatatypeConverter;

import java.net.HttpURLConnection;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The GhRelAssetWagon class is a custom implementation of the AbstractWagon
 * class.
 * It provides functionality to interact with GitHub repositories and perform
 * operations
 * such as retrieving release IDs, asset IDs, downloading release assets, and
 * checking
 * or creating tags.
 */
public class GhRelAssetWagon extends AbstractWagon {

    /**
     * The list of artifacts to upload.
     */
    List<String> artifactsToUpload = new ArrayList<String>();

    /**
     * Handler for Maven metadata generation and management.
     */
    private final MavenMetadataHandler metadataHandler = new MavenMetadataHandler();

    /**
     * Handler for checksum generation and validation.
     */
    private final ChecksumHandler checksumHandler = new ChecksumHandler();

    /**
     * Cache for tracking uploaded artifacts and their metadata.
     */
    private final Map<String, Set<String>> repositoryStructure = new HashMap<>();

    /**
     * The base URL for the GitHub API.
     */
    String apiEndpoint = "https://api.github.com";

    /**
     * Retrieves the API endpoint.
     *
     * @return the API endpoint as a String.
     */
    public String getApiEndpoint() {
        return apiEndpoint;
    }

    /**
     * Sets the API endpoint.
     *
     * @param apiEndpoint the API endpoint to set
     */
    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    /**
     * The base URL for the GitHub upload endpoint.
     */
    String uploadEndpoint = "https://uploads.github.com";

    /**
     * Retrieves the upload endpoint URL.
     *
     * @return the upload endpoint URL as a String.
     */
    public String getUploadEndpoint() {
        return uploadEndpoint;
    }

    /**
     * Sets the upload endpoint URL.
     *
     * @param uploadEndpoint the URL of the upload endpoint to be set
     */
    public void setUploadEndpoint(String uploadEndpoint) {
        this.uploadEndpoint = uploadEndpoint;
    }

    /**
     * Sets the repository with the given name and URL.
     *
     * @param repository the name of the repository
     * @param repoURL    the URL of the repository
     */
    public void setRepository(String repository, String repoURL) {
        this.repository = new Repository(repository, repoURL);
    }

    /**
     * Retrieves the release ID for a given repository and tag.
     *
     * @param repository the name of the repository
     * @param tag        the tag of the release
     * @return the release ID as a string
     * @throws IOException if an I/O error occurs while retrieving the release ID
     */
    String getReleaseId(String repository, String tag) throws IOException {

        URL url = new URL(apiEndpoint + "/repos/" + repository + "/releases/tags/" + tag);

        // Note: we cannot follow redirects automatically - we need to do it manually
        int maxRedirects = 5;
        for (int i = 0; i < maxRedirects; i++) {
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            int responseCode = urlConnection.getResponseCode();

            System.out.println("GhRelAssetWagon:getReleaseId Response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = urlConnection.getInputStream();

                // create ObjectMapper instance
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());
                System.out.println("GhRelAssetWagon: getReleaseId response: " + rootNode.asText());
                JsonNode idNode = rootNode.path("id");
                System.out.println("GhRelAssetWagon: getReleaseId id: " + idNode.asInt());

                return idNode.asText();
            } else if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER || responseCode == 307 || responseCode == 308) {
                // HttpURLConnection.HTTP_TEMP_REDIRECT => 307
                // HttpURLConnection.HTTP_PERM_REDIRECT => 308
                String newUrl = urlConnection.getHeaderField("Location");
                url = new URL(newUrl);
            } else {
                throw new IOException("Failed to get release id");
            }
        }

        return null;
    }

    /**
     * Retrieves the asset ID for a given repository, tag, and asset name.
     *
     * @param repository The name of the repository.
     * @param tag        The tag of the release.
     * @param assetName  The name of the asset.
     * @return The asset ID if found, or null if not found.
     * @throws IOException If there is an error retrieving the asset ID.
     */
    String getAssetId(String repository, String tag, String assetName) throws IOException {
        // get release from github using the repository and tag
        String releaseId = getReleaseId(repository, tag);

        URL url = new URL(apiEndpoint + "/repos/" + repository + "/releases/" + releaseId + "/assets");

        // Note: we cannot follow redirects automatically - we need to do it manually
        int maxRedirects = 5;
        for (int i = 0; i < maxRedirects; i++) {
            System.out.println("GhRelAssetWagon: getAssetId url: " + url);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            int responseCode = urlConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = urlConnection.getInputStream();

                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());

                for (JsonNode assetNode : rootNode) {
                    // System.out.println("GhRelAssetWagon: getAssetId assetNode: " + assetNode);
                    JsonNode nameNode = assetNode.path("name");
                    // System.out.println("GhRelAssetWagon: getAssetId name: " + nameNode.asText());
                    if (nameNode.asText().equals(assetName)) {
                        JsonNode idNode = assetNode.path("id");
                        System.out.println("GhRelAssetWagon: getAssetId id: " + idNode.asInt());
                        return idNode.asText();
                    }
                }
            } else if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER || responseCode == 307 || responseCode == 308) {
                // HttpURLConnection.HTTP_TEMP_REDIRECT => 307
                // HttpURLConnection.HTTP_PERM_REDIRECT => 308
                String newUrl = urlConnection.getHeaderField("Location");
                url = new URL(newUrl);
            } else {
                throw new IOException("Failed to get asset id");
            }
        }

        return null;
    }

    /**
     * Downloads a release asset from a GitHub repository.
     *
     * @param repository The name of the GitHub repository.
     * @param tag        The tag of the release.
     * @param assetName  The name of the asset to download.
     * @return The SHA1 hash of the downloaded asset.
     * @throws Exception If an error occurs during the download process.
     */
    String downloadGHReleaseAsset(String repository, String tag, String assetName) throws Exception {
        // get release from github using the repository and tag
        System.out.println("GhRelAssetWagon: Downloading asset " + assetName + " from " + repository + " tag " + tag);
        String assetId = getAssetId(repository, tag, assetName);
        System.out.println("GhRelAssetWagon: Asset ID: " + assetId);

        URL url = new URL(apiEndpoint + "/repos/" + repository + "/releases/assets/" + assetId);
        System.out.println("GhRelAssetWagon: Downloading asset from " + url);

        // Note: we cannot follow redirects automatically - we need to do it manually
        int maxRedirects = 5;
        for (int i = 0; i < maxRedirects; i++) {
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/octet-stream");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            int responseCode = urlConnection.getResponseCode();
            System.out.println("GhRelAssetWagon: DownloadGHReleaseAsset Response code: " + responseCode);
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = urlConnection.getInputStream();
                String sha1 = getSHA1(this.getRepository().getUrl().toString());
                File destination = new File(System.getProperty("user.home") + "/.ghrelasset/repos/" + sha1);
                System.out.println("GhRelAssetWagon: Downloading asset to " + destination);
                org.apache.commons.io.FileUtils.copyInputStreamToFile(inputStream, destination);
                return sha1;
            } else if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER || responseCode == 307 || responseCode == 308) {
                // HttpURLConnection.HTTP_TEMP_REDIRECT => 307
                // HttpURLConnection.HTTP_PERM_REDIRECT => 308
                String newUrl = urlConnection.getHeaderField("Location");
                url = new URL(newUrl);
            } else {
                throw new IOException("Failed to download asset");
            }
        }

        return null;
    }

    /**
     * Checks if a tag exists in a GitHub repository. If the tag does not exist, it
     * creates the tag.
     *
     * @param repository The name of the GitHub repository.
     * @param tag        The name of the tag to check or create.
     * @param commit     The commit SHA associated with the tag.
     * @return The name of the tag.
     * @throws IOException If an error occurs while checking or creating the tag.
     */
    String getOrCreateTag(String repository, String tag, String commit) throws IOException {

        // check the tag - if missing, create it
        URL url = new URL(apiEndpoint + "/repos/" + repository + "/tags/" + commit);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
        urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
        urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        urlConnection.setRequestMethod("GET");
        urlConnection.connect();

        int responseCode = urlConnection.getResponseCode();
        urlConnection.disconnect();
        urlConnection = null;
        System.out.println("GhRelAssetWagon:checkOrCreateTag Response code: " + responseCode);

        if (responseCode == HttpURLConnection.HTTP_OK) {
            System.out.println("GhRelAssetWagon: Tag exists");
            // return the tag
            return tag;
        } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            System.out.println("GhRelAssetWagon: Tag does not exist");

            // create the tag
            url = new URL(apiEndpoint + "/repos/" + repository + "/git/tags");
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestMethod("POST");

            // set the request body
            String requestBody = "{\"tag\":\"" + tag
                    + "\",\"message\":\"Tag created by GhRhelAssetWagon\",\"object\":\"" + commit
                    + "\",\"type\":\"commit\",\"email\":\"GhRhelAssetWagon@noreply.com\""
                    + "\"}}";
            System.out.println("GhRelAssetWagon:checkOrCreateTag Request body: " + requestBody);

            urlConnection.setDoOutput(true);
            urlConnection.connect();

            urlConnection.getOutputStream().write(requestBody.getBytes());

            responseCode = urlConnection.getResponseCode();
            System.out.println("GhRelAssetWagon:checkOrCreateTag Response code: " + responseCode);
            System.out.println(
                    "GhRelAssetWagon:checkOrCreateTag Response message: " + urlConnection.getResponseMessage());

            if (responseCode == HttpURLConnection.HTTP_CREATED) {
                System.out.println("GhRelAssetWagon: Tag created");
            } else {
                throw new IOException("Failed to create tag");
            }

        } else {
            throw new IOException("Failed to check tag");
        }

        return tag;
    }

    /**
     * Retrieves an existing release from the specified GitHub repository based on
     * the given tag,
     * or creates a new release if it doesn't exist.
     *
     * @param repository the name of the GitHub repository
     * @param tag        the tag associated with the release
     * @return the ID of the existing or newly created release
     * @throws IOException if an I/O error occurs while communicating with the
     *                     GitHub API
     */
    String getOrCreateRelease(String repository, String tag) throws IOException {
        URL url = new URL(apiEndpoint + "/repos/" + repository + "/releases/tags/" + tag);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
        urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
        urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        urlConnection.setRequestMethod("GET");
        urlConnection.connect();

        int responseCode = urlConnection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            System.out.println("GhRelAssetWagon: Release exists");
            // get release id
            InputStream inputStream = urlConnection.getInputStream();
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());
            JsonNode idNode = rootNode.path("id");
            urlConnection.disconnect();
            urlConnection = null;
            return idNode.asText();
        } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            System.out.println("GhRelAssetWagon: Release does not exist");

            // create the release
            url = new URL(apiEndpoint + "/repos/" + repository + "/releases");
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestMethod("POST");
            urlConnection.setDoOutput(true);
            urlConnection.connect();

            // set the request body
            String requestBody = "{\"tag_name\":\"" + tag
                    + "\",\"name\":\"" + tag
                    + "\",\"body\":\"Release created by GhRrelAssetWagon\",\"draft\":false,\"prerelease\":false,\"generate_release_notes\":false}";

            urlConnection.getOutputStream().write(requestBody.getBytes());

            responseCode = urlConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_CREATED) {
                System.out.println("GhRelAssetWagon: Release created");
                // get release id
                InputStream inputStream = urlConnection.getInputStream();
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());
                JsonNode idNode = rootNode.path("id");
                return idNode.asText();
            } else {
                throw new IOException("Failed to create release");
            }
        } else {
            throw new IOException("Failed to check release");
        }
    }

    /**
     * Retrieves the default branch of a GitHub repository.
     *
     * @param repository the name of the repository in the format "owner/repository"
     * @return the name of the default branch
     * @throws IOException if there is an error while retrieving the default branch
     */
    String getDefaultBranch(String repository) throws IOException {
        URL url = new URL(apiEndpoint + "/repos/" + repository);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
        urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
        urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        urlConnection.setRequestMethod("GET");
        urlConnection.connect();

        int responseCode = urlConnection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            InputStream inputStream = urlConnection.getInputStream();
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());
            JsonNode defaultBranchNode = rootNode.path("default_branch");
            return defaultBranchNode.asText();
        } else {
            throw new IOException("Failed to get default branch");
        }
    }

    /**
     * Retrieves the latest commit SHA for a given repository and branch.
     *
     * @param repository the name of the repository
     * @param branch     the name of the branch
     * @return the SHA of the latest commit
     * @throws IOException if there is an error while retrieving the latest commit
     */
    String getLatestCommit(String repository, String branch) throws IOException {
        URL url = new URL(apiEndpoint + "/repos/" + repository + "/branches/" + branch);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
        urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
        urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        urlConnection.setRequestMethod("GET");
        urlConnection.connect();

        int responseCode = urlConnection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            InputStream inputStream = urlConnection.getInputStream();
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());
            JsonNode commitNode = rootNode.path("commit");
            JsonNode shaNode = commitNode.path("sha");
            return shaNode.asText();
        } else {
            throw new IOException("Failed to get latest commit");
        }
    }

    /**
     * Uploads a release asset to a GitHub repository.
     *
     * @param repository The name of the GitHub repository.
     * @param tag        The tag associated with the release.
     * @param assetName  The name of the asset to be uploaded.
     * @return The SHA1 hash of the uploaded asset.
     * @throws Exception If an error occurs during the upload process.
     */
    String uploadGHReleaseAsset(String repository, String tag, String assetName) throws Exception {
        // get release from github using the repository and tag
        System.out.println("GhRelAssetWagon: Uploading asset " + assetName + " to " + repository + " tag " + tag);

        // 1. Let's assume the tag is created on the latest commit of the default branch
        // - let's get the repository metadata to read it
        String defaultBranch = getDefaultBranch(repository);

        // 2. Now we need to get this branch and grab the sha of the latest commit
        String latestCommit = getLatestCommit(repository, defaultBranch);

        // 3. Now we need to check or create the tag
        try {
            getOrCreateTag(repository, tag, latestCommit);
        } catch (IOException e) {
            // print out the stack trace
            e.printStackTrace();
            throw new IOException("Failed to create tag");
        }

        String releaseId = getOrCreateRelease(repository, tag);
        System.out.println("GhRelAssetWagon: Release ID: " + releaseId);

        String sha1 = getSHA1(this.getRepository().getUrl().toString());
        File zipRepo = new File(System.getProperty("user.home") + "/.ghrelasset/repos/" + sha1);
        System.out.println("GhRelAssetWagon: uploadGHReleaseAsset - repo: " + zipRepo);

        URL url = new URL(uploadEndpoint + "/repos/" + repository + "/releases/" + releaseId
                + "/assets?name=" + assetName);

        HttpURLConnection urlConnection = null;

        // Create the asset
        try {
            String assetId = getAssetId(repository, tag, assetName);

            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestProperty("Content-Type", "application/octet-stream");
            urlConnection.setRequestMethod("POST");
            urlConnection.setDoOutput(true);
            urlConnection.connect();

            // set the request body - as a binary file from the zipRepo
            urlConnection.getOutputStream().write(org.apache.commons.io.FileUtils.readFileToByteArray(zipRepo));
            int responseCode = urlConnection.getResponseCode();
            urlConnection.disconnect();
            urlConnection = null;

            // if the asset already exists, then we need to delete it first
            if (responseCode == 422) {
                // delete the asset
                URL deleteUrl = new URL(apiEndpoint + "/repos/" + repository + "/releases/assets/" + assetId);
                urlConnection = (HttpURLConnection) deleteUrl.openConnection();
                urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
                urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
                urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                urlConnection.setRequestMethod("DELETE");
                urlConnection.connect();

                responseCode = urlConnection.getResponseCode();
                urlConnection.disconnect();
                urlConnection = null;
                if (responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                    System.out.println("GhRelAssetWagon: Asset deleted");
                } else {
                    throw new IOException("Failed to delete asset");
                }

                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
                urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
                urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                urlConnection.setRequestProperty("Content-Type", "application/octet-stream");
                urlConnection.setRequestMethod("POST");
                urlConnection.setDoOutput(true);
                urlConnection.connect();

                // set the request body - as a binary file from the zipRepo
                urlConnection.getOutputStream().write(org.apache.commons.io.FileUtils.readFileToByteArray(zipRepo));
                responseCode = urlConnection.getResponseCode();
                urlConnection.disconnect();
                urlConnection = null;
                if (responseCode == HttpURLConnection.HTTP_CREATED) {
                    System.out.println("GhRelAssetWagon: Asset uploaded");
                    return sha1;
                } else {
                    throw new IOException("Failed to upload asset");
                }

            } else if (responseCode == HttpURLConnection.HTTP_CREATED) {
                System.out.println("GhRelAssetWagon: Asset uploaded");
            } else {
                throw new IOException("Failed to upload asset");
            }

        } catch (IOException e) {
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestProperty("Content-Type", "application/octet-stream");
            urlConnection.setRequestMethod("POST");
            urlConnection.setDoOutput(true);
            urlConnection.connect();

            // set the request body - as a binary file from the zipRepo
            urlConnection.getOutputStream().write(org.apache.commons.io.FileUtils.readFileToByteArray(zipRepo));
            Integer responseCode = urlConnection.getResponseCode();
            urlConnection.disconnect();
            urlConnection = null;
            if (responseCode == HttpURLConnection.HTTP_CREATED) {
                System.out.println("GhRelAssetWagon: Asset uploaded");
                return sha1;
            } else {
                throw new IOException("Failed to upload asset");
            }
        }
        return sha1;
    }

    /**
     * Calculates the SHA-1 hash value of the given input string.
     *
     * @param input the input string to calculate the hash value for
     * @return the SHA-1 hash value of the input string
     * @throws Exception if an error occurs during the hash calculation
     */
    String getSHA1(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(input.getBytes());
        byte[] digest = md.digest();
        System.out.println("GhRelAssetWagon: SHA-1: " + DatatypeConverter.printHexBinary(digest));
        return DatatypeConverter.printHexBinary(digest);
    }

    /**
     * Retrieves a resource from a zip file and copies it to the specified output
     * file path.
     *
     * @param zipFilePath    the path of the zip file
     * @param resourceName   the name of the resource to retrieve from the zip file
     * @param outputFilePath the path of the output file where the resource will be
     *                       copied to
     * @throws IOException if an I/O error occurs while reading the zip file or
     *                     copying the resource
     */
    void getResourceFromZip(String zipFilePath, String resourceName, String outputFilePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipFilePath)) {
            ZipEntry entry = zipFile.getEntry(resourceName);

            if (entry == null) {
                System.out.println("The resource does not exist in the zip file: " + resourceName);
                return;
            }

            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                System.out.println("GhRelAssetWagon: getResourceFromZip - copying resource from zip file: "
                        + resourceName + " to " + outputFilePath);
                // if outputFilePath exists, then delete it first
                if (Files.exists(Paths.get(outputFilePath))) {
                    Files.delete(Paths.get(outputFilePath));
                }
                Files.copy(inputStream, Paths.get(outputFilePath));
                // verify the file was copied
                if (Files.exists(Paths.get(outputFilePath))) {
                    System.out.println("GhRelAssetWagon: getResourceFromZip - resource copied successfully");
                    // display the file size
                    System.out.println("GhRelAssetWagon: getResourceFromZip - file size: "
                            + Files.size(Paths.get(outputFilePath)));
                } else {
                    System.out.println("GhRelAssetWagon: getResourceFromZip - failed to copy resource");
                }
            }
        }
    }

    /**
     * Adds a resource to a zip file.
     *
     * @param zipFilePath  the path of the zip file
     * @param resourcePath the path of the resource to be added
     * @param resourceName the name of the resource to be added
     * @throws IOException if an I/O error occurs while reading or writing the zip
     *                     file
     */
    void addResourceToZip(String zipFilePath, String resourcePath, String resourceName) throws IOException {

        File zipRepo = new File(zipFilePath);
        if (!zipRepo.exists()) {
            // create a new zip file
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFilePath))) {
                // add the new resource to the new zip file
                zipOutputStream.putNextEntry(new ZipEntry(resourceName));
                try (InputStream inputStream = new FileInputStream(resourcePath)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        zipOutputStream.write(buffer, 0, length);
                    }
                }
                zipOutputStream.closeEntry();
            }
            return;
        }

        // create a new zip file
        File tempFile = new File(zipFilePath + ".tmp");
        try (ZipFile zip = new ZipFile(zipFilePath);) {
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(tempFile))) {
                zip.stream().forEach(entry -> {
                    try {
                        if (entry.getName().equals(resourceName)) {
                            // skip the resource to be added
                            return;
                        }
                        zipOutputStream.putNextEntry(entry);
                        InputStream inputStream = zip.getInputStream(entry);
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = inputStream.read(buffer)) > 0) {
                            zipOutputStream.write(buffer, 0, length);
                        }
                        inputStream.close();
                        zipOutputStream.closeEntry();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });

                // add the new resource to the new zip file
                zipOutputStream.putNextEntry(new ZipEntry(resourceName));
                try (InputStream inputStream = new FileInputStream(resourcePath)) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = inputStream.read(buffer)) > 0) {
                        zipOutputStream.write(buffer, 0, length);
                    }
                }
                zipOutputStream.closeEntry();
            }
            // rename the new zip file to the original zip file
            Files.move(tempFile.toPath(), Paths.get(zipFilePath), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Retrieves a resource from the repository and saves it to the specified
     * destination file.
     *
     * @param resourceName The name of the resource to retrieve.
     * @param destination  The file where the retrieved resource will be saved.
     * @throws TransferFailedException       If the transfer of the resource fails.
     * @throws ResourceDoesNotExistException If the requested resource does not
     *                                       exist.
     * @throws AuthorizationException        If the user is not authorized to access
     *                                       the resource.
     */
    @Override
    public void get(String resourceName, File destination)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {

        System.out.println("GhRelAssetWagon: Getting resource '" + resourceName + "' to '" + destination + "'");

        String sha1;
        try {
            sha1 = getSHA1(this.getRepository().getUrl().toString());
            File zipRepo = new File(System.getProperty("user.home") + "/.ghrelasset/repos/" + sha1);
            System.out.println("GhRelAssetWagon: get - repo: " + zipRepo);
            if (zipRepo.exists()) {
                // zipRepo is a zip file - get the resource from it
                System.out.println("GhRelAssetWagon: get - repo exists");
                String resourceNameInZip = resourceName;
                if (resourceName.startsWith("/")) {
                    resourceNameInZip = resourceName.substring(1);
                }
                getResourceFromZip(zipRepo.toString(), resourceNameInZip, destination.toString());

                // org.apache.commons.io.FileUtils.copyFile(zipRepo, destination);
            } else {
                downloadGHReleaseAsset("Amadeus-xDLC/github.pinger", "0.0.0", "pinger");
                org.apache.commons.io.FileUtils.copyFile(zipRepo, destination);
            }
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the specified resource if it is newer than the given timestamp.
     *
     * @param resourceName the name of the resource to retrieve
     * @param destination  the file where the resource will be saved
     * @param timestamp    the timestamp to compare against the resource's last
     *                     modified timestamp
     * @return true if the resource was retrieved, false otherwise
     * @throws TransferFailedException       if the transfer fails
     * @throws ResourceDoesNotExistException if the specified resource does not
     *                                       exist
     * @throws AuthorizationException        if the user is not authorized to access
     *                                       the resource
     */
    @Override
    public boolean getIfNewer(String resourceName, File destination, long timestamp)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        System.out.println(
                "GhRelAssetWagon: getIfNewer " + resourceName + " to " + destination + " timestamp " + timestamp);
        
        try {
            // Parse repository and tag from URL
            String repoUrl = this.getRepository().getUrl();
            // Handle both ghrelasset:// and regular URL formats
            if (repoUrl.startsWith("ghrelasset://")) {
                repoUrl = repoUrl.substring("ghrelasset://".length());
            }
            String[] urlParts = repoUrl.split("/");
            if (urlParts.length < 3) {
                throw new TransferFailedException("Invalid repository URL format");
            }
            
            String repository = urlParts[0] + "/" + urlParts[1];
            String tag = urlParts[2];
            String assetName = resourceName.substring(resourceName.lastIndexOf("/") + 1);
            
            // Get release information to check asset timestamp
            String releaseId;
            try {
                releaseId = getReleaseId(repository, tag);
            } catch (IOException e) {
                System.out.println("GhRelAssetWagon: Release not found for tag: " + tag + " - " + e.getMessage());
                return false; // Release doesn't exist
            }
            if (releaseId == null) {
                return false; // Release doesn't exist
            }
            
            // Get asset information including timestamp
            URL url = new URL(apiEndpoint + "/repos/" + repository + "/releases/" + releaseId + "/assets");
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();
            
            int responseCode = urlConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = urlConnection.getInputStream();
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());
                
                for (JsonNode assetNode : rootNode) {
                    JsonNode nameNode = assetNode.path("name");
                    if (nameNode.asText().equals(assetName)) {
                        JsonNode updatedAtNode = assetNode.path("updated_at");
                        String updatedAtStr = updatedAtNode.asText();
                        
                        try {
                            Instant assetTimestamp = Instant.parse(updatedAtStr);
                            long assetTimestampMillis = assetTimestamp.toEpochMilli();
                            
                            if (assetTimestampMillis > timestamp) {
                                // Asset is newer, download it
                                get(resourceName, destination);
                                return true;
                            } else {
                                // Asset is older or same age
                                return false;
                            }
                        } catch (DateTimeParseException e) {
                            System.err.println("GhRelAssetWagon: Failed to parse asset timestamp: " + updatedAtStr);
                            // If we can't parse timestamp, fall back to regular get
                            get(resourceName, destination);
                            return true;
                        }
                    }
                }
            }
            
            return false; // Asset not found
            
        } catch (IOException e) {
            throw new TransferFailedException("Failed to check resource timestamp: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new TransferFailedException("Unexpected error during getIfNewer: " + e.getMessage(), e);
        }
    }


    /**
     * Generates and stages checksum files for an artifact.
     * 
     * @param source The source file to generate checksums for
     * @param destination The destination path in the repository
     * @throws IOException If checksum generation fails
     */
    private void generateAndStageChecksums(File source, String destination) throws IOException {
        Map<String, String> checksums = checksumHandler.generateChecksums(source);
        
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            String algorithm = entry.getKey();
            String checksum = entry.getValue();
            String extension = checksumHandler.getChecksumFileExtension(algorithm);
            
            // Create temporary checksum file
            File checksumFile = File.createTempFile("checksum-" + algorithm, "." + extension);
            try (FileOutputStream fos = new FileOutputStream(checksumFile)) {
                fos.write(checksum.getBytes());
            }
            
            // Stage the checksum file
            String checksumDestination = destination + "." + extension;
            stageArtifact(checksumFile, checksumDestination);
            
            // Clean up temporary file
            checksumFile.delete();
        }
    }

    /**
     * Stages an artifact for upload by adding it to the local ZIP cache.
     * 
     * @param source The source file to stage
     * @param destination The destination path in the repository
     * @throws IOException If staging fails
     */
    private void stageArtifact(File source, String destination) throws IOException {
        String releaseName = this.getRepository().getUrl().substring(0, this.getRepository().getUrl().lastIndexOf("/"));
        String tagName = this.getRepository().getUrl().substring(this.getRepository().getUrl().lastIndexOf("/") + 1);

        System.out.println("GhRelAssetWagon: stageArtifact - releaseName: " + releaseName);
        System.out.println("GhRelAssetWagon: stageArtifact - tagName: " + tagName);

        try {
            String sha1 = getSHA1(this.getRepository().getUrl().toString());
            File zipRepo = new File(System.getProperty("user.home") + "/.ghrelasset/repos/" + sha1);
            System.out.println("GhRelAssetWagon: stageArtifact - repo: " + zipRepo);

            addResourceToZip(zipRepo.getAbsolutePath(), source.toString(), destination);
            this.artifactsToUpload.add(source.toString());

        } catch (Exception e) {
            throw new IOException("Failed to stage artifact", e);
        }
    }

    /**
     * Updates the repository structure tracking for metadata generation.
     * 
     * @param destination The artifact destination path
     */
    private void updateRepositoryStructure(String destination) {
        // Parse the Maven coordinates from the destination path
        // Format: groupId/artifactId/version/artifact-version.extension
        String[] pathParts = destination.split("/");
        if (pathParts.length >= 3) {
            String version = pathParts[pathParts.length - 2];
            String artifactId = pathParts[pathParts.length - 3];
            
            // Build group ID from remaining path parts
            StringBuilder groupIdBuilder = new StringBuilder();
            for (int i = 0; i < pathParts.length - 3; i++) {
                if (i > 0) groupIdBuilder.append(".");
                groupIdBuilder.append(pathParts[i]);
            }
            String groupId = groupIdBuilder.toString();
            
            // Track the structure
            String groupKey = "group:" + groupId;
            String artifactKey = "artifact:" + groupId + ":" + artifactId;
            
            repositoryStructure.computeIfAbsent(groupKey, k -> new HashSet<>()).add(artifactId);
            repositoryStructure.computeIfAbsent(artifactKey, k -> new HashSet<>()).add(version);
        }
    }

    /**
     * Determines if a destination path represents a main artifact (not a classifier).
     * 
     * @param destination The destination path
     * @return true if this is a main artifact, false otherwise
     */
    private boolean isMainArtifact(String destination) {
        String filename = destination.substring(destination.lastIndexOf("/") + 1);
        
        // Skip checksum files
        if (checksumHandler.isChecksumFile(filename)) {
            return false;
        }
        
        // Main artifacts typically follow the pattern: artifactId-version.extension
        // Classified artifacts follow: artifactId-version-classifier.extension
        String[] pathParts = destination.split("/");
        if (pathParts.length >= 3) {
            String version = pathParts[pathParts.length - 2];
            String artifactId = pathParts[pathParts.length - 3];
            
            String expectedPrefix = artifactId + "-" + version;
            String nameWithoutExt = filename.contains(".") ? 
                filename.substring(0, filename.lastIndexOf(".")) : filename;
            
            // Main artifact if the name matches exactly artifactId-version
            return nameWithoutExt.equals(expectedPrefix);
        }
        
        return false;
    }

    /**
     * Generates Maven metadata files for the given artifact destination.
     * 
     * @param destination The artifact destination path
     * @throws IOException If metadata generation fails
     */
    private void generateMavenMetadata(String destination) throws IOException {
        String[] pathParts = destination.split("/");
        if (pathParts.length < 3) {
            return; // Invalid path structure
        }
        
        String version = pathParts[pathParts.length - 2];
        String artifactId = pathParts[pathParts.length - 3];
        
        // Build group ID
        StringBuilder groupIdBuilder = new StringBuilder();
        for (int i = 0; i < pathParts.length - 3; i++) {
            if (i > 0) groupIdBuilder.append(".");
            groupIdBuilder.append(pathParts[i]);
        }
        String groupId = groupIdBuilder.toString();
        
        // Generate artifact-level metadata
        generateArtifactLevelMetadata(groupId, artifactId);
        
        // Generate group-level metadata if this is a plugin
        if (isPluginArtifact(destination)) {
            generateGroupLevelMetadata(groupId, artifactId);
        }
        
        // Generate version-level metadata for SNAPSHOT versions
        if (version.endsWith("-SNAPSHOT")) {
            generateVersionLevelMetadata(groupId, artifactId, version);
        }
    }

    /**
     * Generates artifact-level Maven metadata.
     * 
     * @param groupId The group ID
     * @param artifactId The artifact ID
     * @throws IOException If metadata generation fails
     */
    private void generateArtifactLevelMetadata(String groupId, String artifactId) throws IOException {
        String artifactKey = "artifact:" + groupId + ":" + artifactId;
        Set<String> versions = repositoryStructure.get(artifactKey);
        
        if (versions != null && !versions.isEmpty()) {
            String metadataXml = metadataHandler.generateArtifactMetadata(groupId, artifactId, new ArrayList<>(versions));
            
            // Stage the metadata file
            String metadataPath = groupId.replace(".", "/") + "/" + artifactId + "/maven-metadata.xml";
            File tempMetadataFile = File.createTempFile("maven-metadata", ".xml");
            
            try (FileOutputStream fos = new FileOutputStream(tempMetadataFile)) {
                fos.write(metadataXml.getBytes());
            }
            
            stageArtifact(tempMetadataFile, metadataPath);
            tempMetadataFile.delete();
        }
    }

    /**
     * Generates group-level Maven metadata for plugins.
     * 
     * @param groupId The group ID
     * @param artifactId The artifact ID
     * @throws IOException If metadata generation fails
     */
    private void generateGroupLevelMetadata(String groupId, String artifactId) throws IOException {
        // For simplicity, assume plugin prefix is the artifactId without "-maven-plugin" suffix
        String prefix = artifactId.replace("-maven-plugin", "").replace("maven-", "");
        
        List<MavenMetadataHandler.PluginInfo> plugins = new ArrayList<>();
        plugins.add(new MavenMetadataHandler.PluginInfo(artifactId, prefix, artifactId));
        
        String metadataXml = metadataHandler.generateGroupMetadata(groupId, plugins);
        
        // Stage the group-level metadata file
        String metadataPath = groupId.replace(".", "/") + "/maven-metadata.xml";
        File tempMetadataFile = File.createTempFile("maven-metadata-group", ".xml");
        
        try (FileOutputStream fos = new FileOutputStream(tempMetadataFile)) {
            fos.write(metadataXml.getBytes());
        }
        
        stageArtifact(tempMetadataFile, metadataPath);
        tempMetadataFile.delete();
    }

    /**
     * Generates version-level Maven metadata for SNAPSHOT versions.
     * 
     * @param groupId The group ID
     * @param artifactId The artifact ID
     * @param version The SNAPSHOT version
     * @throws IOException If metadata generation fails
     */
    private void generateVersionLevelMetadata(String groupId, String artifactId, String version) throws IOException {
        // Generate snapshot version info
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd.HHmmss").format(new java.util.Date());
        int buildNumber = 1; // In a real implementation, this would be incremented
        
        List<MavenMetadataHandler.SnapshotVersionInfo> snapshotVersions = new ArrayList<>();
        snapshotVersions.add(new MavenMetadataHandler.SnapshotVersionInfo(
            null, "jar", version.replace("-SNAPSHOT", "-" + timestamp + "-" + buildNumber),
            timestamp.replace(".", ""), timestamp, buildNumber));
        snapshotVersions.add(new MavenMetadataHandler.SnapshotVersionInfo(
            null, "pom", version.replace("-SNAPSHOT", "-" + timestamp + "-" + buildNumber),
            timestamp.replace(".", ""), timestamp, buildNumber));
        
        String metadataXml = metadataHandler.generateVersionMetadata(groupId, artifactId, version, snapshotVersions);
        
        // Stage the version-level metadata file
        String metadataPath = groupId.replace(".", "/") + "/" + artifactId + "/" + version + "/maven-metadata.xml";
        File tempMetadataFile = File.createTempFile("maven-metadata-version", ".xml");
        
        try (FileOutputStream fos = new FileOutputStream(tempMetadataFile)) {
            fos.write(metadataXml.getBytes());
        }
        
        stageArtifact(tempMetadataFile, metadataPath);
        tempMetadataFile.delete();
    }

    /**
     * Determines if an artifact is a Maven plugin based on its path.
     * 
     * @param destination The artifact destination path
     * @return true if this appears to be a plugin artifact
     */
    private boolean isPluginArtifact(String destination) {
        return destination.contains("maven-plugin") || destination.contains("-plugin");
    }

    /**
     * Opens the connection to the remote repository.
     * This method is called internally by the wagon implementation.
     * It prints the username and repository URL, sets the authentication
     * information,
     * and downloads the zip file if the repository URL ends with ".zip".
     *
     * @throws ConnectionException     if there is an error connecting to the remote
     *                                 repository
     * @throws AuthenticationException if the authentication fails or the token is
     *                                 not set
     */
    @Override
    protected void openConnectionInternal() throws ConnectionException, AuthenticationException {

        System.out.println("GhRelAssetWagon: openConnectionInternal");
        System.out.println("GhRelAssetWagon: Username: " + this.authenticationInfo.getUserName());
        System.out.println("GhRelAssetWagon: Repository: " + this.getRepository().getUrl());

        this.authenticationInfo = new AuthenticationInfo();
        this.authenticationInfo.setUserName("ghrelasset");
        String token = System.getenv("GH_RELEASE_ASSET_TOKEN");
        if (token == null) {
            throw new AuthenticationException("GH_RELEASE_ASSET_TOKEN is not set");
        }

        // if the value of the token is a valid path to a file, then read the token from
        // it
        if (new File(token).exists()) {
            try {
                token = FileUtils.fileRead(token).strip();
            } catch (IOException e) {
                throw new AuthenticationException("Failed to read token from file: " + token);
            }
        }

        this.authenticationInfo.setPassword(token);

        if (this.getRepository().getUrl().endsWith(".zip")) {
            System.out.println("GhRelAssetWagon: Downloading the zip file");
            String[] parts = this.getRepository().getUrl().split("/");
            try {
                downloadGHReleaseAsset(parts[2] + "/" + parts[3], parts[4], parts[5]);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Closes the connection and performs any necessary cleanup operations.
     * If there are artifacts to upload, it uploads them to the repository.
     * If the repository URL ends with ".zip", it also downloads the zip file.
     * 
     * @throws ConnectionException if there is an error closing the connection.
     */
    @Override
    protected void closeConnection() throws ConnectionException {
        System.out.println("GhRelAssetWagon: Closing connection");
        if (this.artifactsToUpload != null && !this.artifactsToUpload.isEmpty()) {
            System.out.println("GhRelAssetWagon: Closing connection - uploading artifacts");
            for (String artifact : this.artifactsToUpload) {
                System.out.println("GhRelAssetWagon: Closing connection - uploading artifact: " + artifact);
            }

            if (this.getRepository().getUrl().endsWith(".zip")) {
                System.out.println("GhRelAssetWagon: Downloading the zip file");
                String[] parts = this.getRepository().getUrl().split("/");
                try {
                    String uploadedAssetSha1 = uploadGHReleaseAsset(parts[2] + "/" + parts[3], parts[4], parts[5]);
                    System.out.println("GhRelAssetWagon[closeConnection]: Uploaded asset SHA1: " + uploadedAssetSha1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            this.artifactsToUpload.clear();
        }
    }

    void setAuthenticationInfo(AuthenticationInfo authenticationInfo) {
        this.authenticationInfo = authenticationInfo;
    }

    // ========== Phase 1 Enhancement - Missing Wagon Interface Methods ==========

    /**
     * Lists all files in the specified directory path within the GitHub release assets.
     * This method retrieves the list of assets from a GitHub release and filters them
     * based on the directory path provided.
     *
     * @param destinationDirectory the directory path to list files from
     * @return a list of file names in the specified directory, or empty list if none found
     * @throws TransferFailedException       if the transfer fails
     * @throws ResourceDoesNotExistException if the specified directory does not exist
     * @throws AuthorizationException        if the user is not authorized to access the resource
     */
    @Override
    public List<String> getFileList(String destinationDirectory)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        System.out.println("GhRelAssetWagon: getFileList for directory: " + destinationDirectory);
        
        List<String> fileList = new ArrayList<>();
        
        try {
            // Parse repository and tag from URL
            String repoUrl = this.getRepository().getUrl();
            // Handle both ghrelasset:// and regular URL formats
            if (repoUrl.startsWith("ghrelasset://")) {
                repoUrl = repoUrl.substring("ghrelasset://".length());
            }
            String[] urlParts = repoUrl.split("/");
            if (urlParts.length < 3) {
                throw new TransferFailedException("Invalid repository URL format");
            }
            
            String repository = urlParts[0] + "/" + urlParts[1];
            String tag = urlParts[2];
            
            // Get release information
            String releaseId;
            try {
                releaseId = getReleaseId(repository, tag);
            } catch (IOException e) {
                System.out.println("GhRelAssetWagon: Release not found for tag: " + tag + " - " + e.getMessage());
                return fileList; // Return empty list if release doesn't exist
            }
            if (releaseId == null) {
                System.out.println("GhRelAssetWagon: Release not found for tag: " + tag);
                return fileList; // Return empty list if release doesn't exist
            }
            
            // Get assets from the release
            URL url = new URL(apiEndpoint + "/repos/" + repository + "/releases/" + releaseId + "/assets");
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();
            
            int responseCode = urlConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = urlConnection.getInputStream();
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());
                
                // Extract file names from assets
                for (JsonNode assetNode : rootNode) {
                    JsonNode nameNode = assetNode.path("name");
                    String assetName = nameNode.asText();
                    
                    // For GitHub releases, we treat all assets as being in the same "directory"
                    // In a more sophisticated implementation, we could parse asset names for directory structure
                    if (assetName != null && !assetName.isEmpty()) {
                        fileList.add(assetName);
                    }
                }
                
                System.out.println("GhRelAssetWagon: Found " + fileList.size() + " files in directory: " + destinationDirectory);
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                System.out.println("GhRelAssetWagon: Release assets not found for: " + repository + "/" + tag);
                return fileList; // Return empty list
            } else {
                throw new TransferFailedException("Failed to retrieve file list. HTTP response code: " + responseCode);
            }
            
        } catch (IOException e) {
            throw new TransferFailedException("Failed to retrieve file list: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new TransferFailedException("Unexpected error during getFileList: " + e.getMessage(), e);
        }
        
        return fileList;
    }

    /**
     * Checks if a resource exists in the GitHub release assets.
     * This method queries the GitHub API to determine if the specified resource
     * exists as an asset in the release.
     *
     * @param resourceName the name of the resource to check
     * @return true if the resource exists, false otherwise
     * @throws TransferFailedException    if the transfer fails
     * @throws AuthorizationException     if the user is not authorized to access the resource
     */
    @Override
    public boolean resourceExists(String resourceName)
            throws TransferFailedException, AuthorizationException {
        System.out.println("GhRelAssetWagon: Checking if resource exists: " + resourceName);
        
        try {
            // Parse repository and tag from URL
            String repoUrl = this.getRepository().getUrl();
            // Handle both ghrelasset:// and regular URL formats
            if (repoUrl.startsWith("ghrelasset://")) {
                repoUrl = repoUrl.substring("ghrelasset://".length());
            }
            String[] urlParts = repoUrl.split("/");
            if (urlParts.length < 3) {
                throw new TransferFailedException("Invalid repository URL format");
            }
            
            String repository = urlParts[0] + "/" + urlParts[1];
            String tag = urlParts[2];
            String assetName = resourceName.substring(resourceName.lastIndexOf("/") + 1);
            
            // Get release information
            String releaseId;
            try {
                releaseId = getReleaseId(repository, tag);
            } catch (IOException e) {
                System.out.println("GhRelAssetWagon: Release not found for tag: " + tag + " - " + e.getMessage());
                return false; // Release doesn't exist
            }
            if (releaseId == null) {
                System.out.println("GhRelAssetWagon: Release not found for tag: " + tag);
                return false; // Release doesn't exist
            }
            
            // Check if asset exists in the release
            URL url = new URL(apiEndpoint + "/repos/" + repository + "/releases/" + releaseId + "/assets");
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/vnd.github+json");
            urlConnection.setRequestProperty("Authorization", "Bearer " + this.authenticationInfo.getPassword());
            urlConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();
            
            int responseCode = urlConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream inputStream = urlConnection.getInputStream();
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(inputStream.readAllBytes());
                
                // Search for the asset by name
                for (JsonNode assetNode : rootNode) {
                    JsonNode nameNode = assetNode.path("name");
                    if (nameNode.asText().equals(assetName)) {
                        System.out.println("GhRelAssetWagon: Resource exists: " + resourceName);
                        return true;
                    }
                }
                
                System.out.println("GhRelAssetWagon: Resource not found: " + resourceName);
                return false;
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                System.out.println("GhRelAssetWagon: Release not found, resource does not exist: " + resourceName);
                return false;
            } else {
                throw new TransferFailedException("Failed to check resource existence. HTTP response code: " + responseCode);
            }
            
        } catch (IOException e) {
            throw new TransferFailedException("Failed to check resource existence: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new TransferFailedException("Unexpected error during resourceExists: " + e.getMessage(), e);
        }
    }

    /**
     * Uploads a directory and its contents to the GitHub release.
     * This method recursively processes all files in the source directory
     * and uploads them as individual assets to the GitHub release.
     *
     * @param sourceDirectory      the source directory to upload
     * @param destinationDirectory the destination directory path (used as prefix for asset names)
     * @throws TransferFailedException       if the transfer fails
     * @throws ResourceDoesNotExistException if the source directory does not exist
     * @throws AuthorizationException        if the user is not authorized to perform the operation
     */
    @Override
    public void putDirectory(File sourceDirectory, String destinationDirectory)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
        System.out.println("GhRelAssetWagon: putDirectory from " + sourceDirectory + " to " + destinationDirectory);
        
        if (!sourceDirectory.exists()) {
            throw new ResourceDoesNotExistException("Source directory does not exist: " + sourceDirectory);
        }
        
        if (!sourceDirectory.isDirectory()) {
            throw new TransferFailedException("Source is not a directory: " + sourceDirectory);
        }
        
        try {
            // Recursively upload all files in the directory
            uploadDirectoryRecursively(sourceDirectory, destinationDirectory, "");
            
        } catch (Exception e) {
            throw new TransferFailedException("Failed to upload directory: " + e.getMessage(), e);
        }
    }

    /**
     * Helper method to recursively upload directory contents.
     *
     * @param currentDir           the current directory being processed
     * @param destinationDirectory the base destination directory
     * @param relativePath         the relative path from the source directory root
     * @throws Exception if an error occurs during upload
     */
    private void uploadDirectoryRecursively(File currentDir, String destinationDirectory, String relativePath) throws Exception {
        File[] files = currentDir.listFiles();
        if (files == null) {
            return; // Empty directory or not accessible
        }
        
        for (File file : files) {
            String currentRelativePath = relativePath.isEmpty() ? file.getName() : relativePath + "/" + file.getName();
            String destinationPath = destinationDirectory + currentRelativePath;
            
            if (file.isDirectory()) {
                // Recursively process subdirectory
                uploadDirectoryRecursively(file, destinationDirectory, currentRelativePath);
            } else {
                // Upload individual file
                System.out.println("GhRelAssetWagon: Uploading file: " + file + " to " + destinationPath);
                put(file, destinationPath);
            }
        }
    }

    /**
     * Uploads a file to the specified destination.
     *
     * @param source      The file to be uploaded.
     * @param destination The destination path where the file will be uploaded.
     * @throws TransferFailedException If the transfer fails.
     */
    @Override
    public void put(File source, String destination) throws TransferFailedException {
        System.out.println("GhRelAssetWagon: put " + source.getAbsolutePath() + " to " + destination);
        
        try {
            // Validate repository path structure
            RepositoryValidator.ValidationResult validation = RepositoryValidator.validateRepositoryPath(destination);
            if (!validation.isValid()) {
                System.out.println("GhRelAssetWagon: Repository path validation failed: " + validation.getMessage());
                // Log warning but continue - some legacy paths might not be perfectly compliant
            }
            
            // Check if this is a checksum file - don't generate checksums for checksum files
            boolean isChecksumFile = destination.endsWith(".md5") || destination.endsWith(".sha1") || 
                                   destination.endsWith(".sha256") || destination.endsWith(".asc");
            
            if (!isChecksumFile) {
                // Generate checksums for the source file
                generateAndStageChecksums(source, destination);
            }
            
            // Stage the main artifact
            stageArtifact(source, destination);
            
            // Update repository structure tracking
            updateRepositoryStructure(destination);
            
            // Generate Maven metadata if applicable (but not for checksum files)
            if (!isChecksumFile) {
                generateMavenMetadata(destination);
            }
            
        } catch (IOException e) {
            throw new TransferFailedException("Failed to upload artifact: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new TransferFailedException("Unexpected error during put: " + e.getMessage(), e);
        }
    }

    /**
     * Indicates whether this wagon supports directory copy operations.
     * The GhRelAssetWagon supports directory operations by uploading
     * individual files as separate GitHub release assets.
     *
     * @return true, indicating that directory copy is supported
     */
    @Override
    public boolean supportsDirectoryCopy() {
        return true;
    }

}