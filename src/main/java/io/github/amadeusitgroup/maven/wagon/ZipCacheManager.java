package io.github.amadeusitgroup.maven.wagon;

import jakarta.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZipCacheManager {

    private static final Logger logger = LoggerFactory.getLogger(ZipCacheManager.class);

    private final Path cacheDirectory;

    private File cacheFile;
    private FileSystem zipFileSystem;

    public ZipCacheManager() {
        this(Paths.get(System.getProperty("user.home"), ".ghrelasset/repos"));
    }

    public ZipCacheManager(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    /**
     * Checks if the cache file has been created.
     *
     * @return {@code true} if the cache file exists, {@code false} otherwise.
     */
    public boolean isInitialized() {
        return cacheFile != null && cacheFile.exists();
    }

    /**
     * Initializes the cache manager. It computes the cache file name based on the repository URL and copies the
     * content of the input stream to it. If the cache is already initialized, this method does nothing.
     *
     * @param repository    the {@link GhRelAssetRepository} being used.
     * @param inputStream   the {@link InputStream} of the zip asset. Can be null if the asset doesn't exist.
     * @throws IOException if an I/O error occurs when creating the cache file or directories.
     */
    public void initialize(GhRelAssetRepository repository, InputStream inputStream) throws IOException {
        if (isInitialized()) {
            return;
        }

        String cacheFileName = getSHA1(repository.getUrl());
        this.cacheFile = cacheDirectory.resolve(cacheFileName).toFile();

        if (inputStream != null) {
            if (!cacheFile.getParentFile().exists()) {
                cacheFile.getParentFile().mkdirs();
            }

            FileUtils.copyInputStreamToFile(inputStream, this.cacheFile);
        }
        // null input indicates zip asset not found, could be first time execution
    }

    /**
     * Returns the cache file.
     *
     * @return The cache {@link File}.
     */
    public File getCacheFile() {
        return cacheFile;
    }

    /**
     * Gets the {@link FileSystem} for the zip cache. It will be created if it doesn't exist.
     *
     * @return the zip {@link FileSystem}.
     * @throws IOException           if an I/O error occurs.
     * @throws IllegalStateException if the cache has not been initialized.
     */
    public synchronized FileSystem getZipFileSystem() throws IOException {
        if (!isInitialized()) {
            throw new IllegalStateException("ZipCacheManager has not been initialized");
        }

        if (zipFileSystem == null || !zipFileSystem.isOpen()) {
            initialiseZipFileSystem(this.getCacheFile());
        }

        return zipFileSystem;
    }

    /**
     * Closes the zip {@link FileSystem} if it has been created and is still open.
     * Handles the case where the FileSystem may have already been closed by
     * another wagon instance sharing the same underlying zip file.
     *
     * @throws IOException if an I/O error occurs closing the file system.
     */
    public synchronized void close() throws IOException {
        if (zipFileSystem != null) {
            try {
                if (zipFileSystem.isOpen()) {
                    zipFileSystem.close();
                }
            } catch (Exception e) {
                logger.warn("Failed to close zip FileSystem (may have been closed by another wagon instance): {}", e.getMessage());
            } finally {
                zipFileSystem = null;
            }
        }
    }

    /**
     * Calculates the SHA-1 hash value of the given input string.
     *
     * @param input the input string to calculate the hash value for
     * @return the SHA-1 hash value of the input string
     * @throws IllegalArgumentException if SHA-1 algorithm is not available
     */
    private String getSHA1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(input.getBytes());
            byte[] digest = md.digest();
            return DatatypeConverter.printHexBinary(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("SHA1 algorithm unavailable", e);
        }
    }

    /**
     * In order to write new files to an existing Zip a new {@link java.io.FileSystem} is created.
     * A reference is maintained to avoid recreating for each new file, then the Filesystem is closed in {@link #close()}
     *
     * @param zipRepo the zipRepo to initialize the FileSystem for
     * @throws IOException if an I/O error occurs whilst initializing the FileSystem
     */
    void initialiseZipFileSystem(File zipRepo) throws IOException {
        if (this.zipFileSystem == null || !this.zipFileSystem.isOpen()) {
            Map<String, String> env = new HashMap<>();
            env.put("create", "true");
            try {
                // Use Path-based overload to avoid "Provider 'jar' not found" errors
                // caused by Maven's classloader isolation hiding the jdk.zipfs module.
                this.zipFileSystem = FileSystems.newFileSystem(zipRepo.toPath(), env);
            } catch (FileSystemAlreadyExistsException e) {
                // Another wagon instance in the same JVM already opened this zip;
                // reuse the existing FileSystem instead of failing.
                URI uri = URI.create("jar:" + zipRepo.toURI());
                this.zipFileSystem = FileSystems.getFileSystem(uri);
            }
        }
    }
}
