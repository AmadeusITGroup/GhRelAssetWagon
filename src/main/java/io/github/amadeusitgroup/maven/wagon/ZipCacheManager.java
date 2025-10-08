package io.github.amadeusitgroup.maven.wagon;

import jakarta.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;

public class ZipCacheManager {

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
    public FileSystem getZipFileSystem() throws IOException {
        if (!isInitialized()) {
            throw new IllegalStateException("ZipCacheManager has not been initialized");
        }

        if (zipFileSystem == null) {
            initialiseZipFileSystem(this.getCacheFile());
        }

        return zipFileSystem;
    }

    /**
     * Closes the zip {@link FileSystem} if it has been created.
     *
     * @throws IOException if an I/O error occurs closing the file system.
     */
    public void close() throws IOException {
        if (zipFileSystem != null) {
            zipFileSystem.close();
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
            URI uri = URI.create("jar:" + zipRepo.toURI());
            this.zipFileSystem = FileSystems.newFileSystem(uri, env);
        }
    }
}
