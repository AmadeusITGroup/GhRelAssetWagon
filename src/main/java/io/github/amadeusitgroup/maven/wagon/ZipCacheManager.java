package io.github.amadeusitgroup.maven.wagon;

import jakarta.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZipCacheManager {

    private static final Logger logger = LoggerFactory.getLogger(ZipCacheManager.class);

    private final Path cacheDirectory;

    private File cacheFile;

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
        return cacheFile != null;
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
            logger.debug("Initialized zip cache from remote asset: {}", cacheFile.getAbsolutePath());
        } else {
            logger.debug("No remote zip asset found; cache file will be created on first put: {}", cacheFile.getAbsolutePath());
        }
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
     * No-op: kept for API compatibility. Zip operations now use java.util.zip
     * streams exclusively and hold no persistent file handles.
     */
    public synchronized void close() {
        // Nothing to close.
    }

    /**
     * Calculates the SHA-1 hash value of the given input string.
     *
     * @param input the input string
     * @return the hex-encoded SHA-1 digest
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
}
