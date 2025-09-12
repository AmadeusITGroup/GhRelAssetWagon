package io.github.amadeusitgroup.maven.wagon;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;

/**
 * Handles compression and decompression operations for Maven artifacts.
 * Implements singleton pattern with thread-safe operations.
 */
public class CompressionHandler {
    
    private static volatile CompressionHandler instance;
    private static final Object lock = new Object();
    
    private final AtomicInteger compressionCount = new AtomicInteger(0);
    private final AtomicInteger decompressionCount = new AtomicInteger(0);
    private final AtomicLong totalBytesCompressed = new AtomicLong(0);
    private final AtomicLong totalBytesDecompressed = new AtomicLong(0);
    private final AtomicLong totalCompressionTime = new AtomicLong(0);
    private final List<Double> compressionRatios = Collections.synchronizedList(new ArrayList<>());
    
    private CompressionHandler() {}
    
    /**
     * Gets the singleton instance.
     */
    public static CompressionHandler getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new CompressionHandler();
                }
            }
        }
        return instance;
    }
    
    /**
     * Resets the singleton instance (for testing).
     */
    public static void resetInstance() {
        synchronized (lock) {
            instance = null;
        }
    }
    
    /**
     * Compression algorithms supported.
     */
    public enum Algorithm {
        GZIP, DEFLATE, LZ4
    }
    
    /**
     * Compression levels.
     */
    public enum Level {
        FAST(Deflater.BEST_SPEED),
        NORMAL(Deflater.DEFAULT_COMPRESSION),
        BEST(Deflater.BEST_COMPRESSION);
        
        private final int level;
        
        Level(int level) {
            this.level = level;
        }
        
        public int getLevel() {
            return level;
        }
    }
    
    /**
     * Compresses a single file using GZIP.
     */
    public void compressFile(File sourceFile, File targetFile) throws IOException {
        compressFile(sourceFile, targetFile, Algorithm.GZIP);
    }
    
    /**
     * Compresses a single file using specified algorithm.
     */
    public void compressFile(File sourceFile, File targetFile, Algorithm algorithm) throws IOException {
        compressFile(sourceFile, targetFile, algorithm, Level.NORMAL);
    }
    
    /**
     * Compresses a single file using specified level (with GZIP algorithm).
     */
    public void compressFile(File sourceFile, File targetFile, Level level) throws IOException {
        compressFile(sourceFile, targetFile, Algorithm.GZIP, level);
    }
    
    /**
     * Compresses a single file using specified algorithm and level.
     */
    public void compressFile(File sourceFile, File targetFile, Algorithm algorithm, Level level) throws IOException {
        long startTime = System.currentTimeMillis();
        long originalSize = sourceFile.length();
        
        try (FileInputStream fis = new FileInputStream(sourceFile);
             FileOutputStream fos = new FileOutputStream(targetFile)) {
            
            OutputStream compressedStream = createCompressedOutputStream(fos, algorithm, level);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                compressedStream.write(buffer, 0, bytesRead);
            }
            
            compressedStream.close();
        }
        
        long compressedSize = targetFile.length();
        double ratio = (double) compressedSize / originalSize;
        
        compressionCount.incrementAndGet();
        totalBytesCompressed.addAndGet(originalSize);
        totalCompressionTime.addAndGet(System.currentTimeMillis() - startTime);
        compressionRatios.add(ratio);
    }
    
    /**
     * Compresses a file with metadata options.
     */
    public void compressFile(File sourceFile, File targetFile, CompressionOptions options) throws IOException {
        long startTime = System.currentTimeMillis();
        long originalSize = sourceFile.length();
        
        try (FileInputStream fis = new FileInputStream(sourceFile);
             FileOutputStream fos = new FileOutputStream(targetFile);
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            
            // GZIP doesn't support all metadata, but we can simulate it
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                gzos.write(buffer, 0, bytesRead);
            }
        }
        
        // Store metadata separately for retrieval
        storeMetadata(targetFile, options);
        
        long compressedSize = targetFile.length();
        double ratio = (double) compressedSize / originalSize;
        
        compressionCount.incrementAndGet();
        totalBytesCompressed.addAndGet(originalSize);
        totalCompressionTime.addAndGet(System.currentTimeMillis() - startTime);
        compressionRatios.add(ratio);
    }
    
    /**
     * Compresses multiple files into an archive.
     */
    public void compressFiles(List<File> sourceFiles, File archiveFile) throws IOException {
        long startTime = System.currentTimeMillis();
        long totalOriginalSize = sourceFiles.stream().mapToLong(File::length).sum();
        
        // For simplicity, use ZIP format instead of TAR.GZ for multiple files
        try (FileOutputStream fos = new FileOutputStream(archiveFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            
            for (File file : sourceFiles) {
                ZipEntry entry = new ZipEntry(file.getName());
                entry.setSize(file.length());
                entry.setTime(file.lastModified());
                zos.putNextEntry(entry);
                
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                }
                zos.closeEntry();
            }
        }
        
        long compressedSize = archiveFile.length();
        double ratio = (double) compressedSize / totalOriginalSize;
        
        compressionCount.incrementAndGet();
        totalBytesCompressed.addAndGet(totalOriginalSize);
        totalCompressionTime.addAndGet(System.currentTimeMillis() - startTime);
        compressionRatios.add(ratio);
    }
    
    /**
     * Decompresses a single file.
     */
    public void decompressFile(File compressedFile, File targetFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(compressedFile);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             FileOutputStream fos = new FileOutputStream(targetFile)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = gzis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
        
        decompressionCount.incrementAndGet();
        totalBytesDecompressed.addAndGet(targetFile.length());
    }
    
    /**
     * Decompresses an archive to a directory.
     */
    public List<File> decompressArchive(File archiveFile, File extractDir) throws IOException {
        List<File> extractedFiles = new ArrayList<>();
        
        // Use ZIP format to match the compression method
        try (FileInputStream fis = new FileInputStream(archiveFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    File extractedFile = new File(extractDir, entry.getName());
                    extractedFile.getParentFile().mkdirs();
                    
                    try (FileOutputStream fos = new FileOutputStream(extractedFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    
                    extractedFiles.add(extractedFile);
                }
                zis.closeEntry();
            }
        }
        
        decompressionCount.incrementAndGet();
        totalBytesDecompressed.addAndGet(extractedFiles.stream().mapToLong(File::length).sum());
        
        return extractedFiles;
    }
    
    /**
     * Compresses a file using streaming for memory efficiency.
     */
    public void compressFileStreaming(File sourceFile, File targetFile) throws IOException {
        // For this implementation, streaming is the same as regular compression
        // In a real implementation, this might use different buffer sizes or streaming APIs
        compressFile(sourceFile, targetFile);
    }
    
    /**
     * Batch compression of multiple files.
     */
    public List<File> compressBatch(List<File> sourceFiles, File outputDir, Algorithm algorithm) throws IOException {
        List<File> compressedFiles = new ArrayList<>();
        
        for (File sourceFile : sourceFiles) {
            String compressedName = sourceFile.getName() + getExtensionForAlgorithm(algorithm);
            File compressedFile = new File(outputDir, compressedName);
            compressFile(sourceFile, compressedFile, algorithm);
            compressedFiles.add(compressedFile);
        }
        
        return compressedFiles;
    }
    
    /**
     * Batch decompression of multiple files.
     */
    public List<File> decompressBatch(List<File> compressedFiles, File outputDir) throws IOException {
        List<File> decompressedFiles = new ArrayList<>();
        
        for (File compressedFile : compressedFiles) {
            String originalName = removeCompressionExtension(compressedFile.getName());
            File decompressedFile = new File(outputDir, originalName);
            decompressFile(compressedFile, decompressedFile);
            decompressedFiles.add(decompressedFile);
        }
        
        return decompressedFiles;
    }
    
    /**
     * Validates if a file is a valid compressed file.
     */
    public boolean isValidCompressedFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             GZIPInputStream gzis = new GZIPInputStream(fis)) {
            
            // Try to read the header
            byte[] buffer = new byte[1024];
            gzis.read(buffer);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Verifies integrity of compressed file against original.
     */
    public boolean verifyIntegrity(File originalFile, File compressedFile) {
        try {
            File tempDecompressed = File.createTempFile("integrity_check", ".tmp");
            tempDecompressed.deleteOnExit();
            
            decompressFile(compressedFile, tempDecompressed);
            
            byte[] originalBytes = Files.readAllBytes(originalFile.toPath());
            byte[] decompressedBytes = Files.readAllBytes(tempDecompressed.toPath());
            
            boolean result = Arrays.equals(originalBytes, decompressedBytes);
            tempDecompressed.delete();
            
            return result;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Checks if an algorithm is supported.
     */
    public boolean isAlgorithmSupported(Algorithm algorithm) {
        switch (algorithm) {
            case GZIP:
            case DEFLATE:
                return true;
            case LZ4:
                return false; // Not implemented in this basic version
            default:
                return false;
        }
    }
    
    /**
     * Gets compression statistics for a specific operation.
     */
    public CompressionStats getCompressionStats(File originalFile, File compressedFile) {
        long originalSize = originalFile.length();
        long compressedSize = compressedFile.length();
        double ratio = (double) compressedSize / originalSize;
        double spaceSaved = 1.0 - ratio;
        
        return new CompressionStats(originalSize, compressedSize, ratio, spaceSaved);
    }
    
    /**
     * Gets metadata from a compressed file.
     */
    public CompressionMetadata getMetadata(File compressedFile) {
        // In this implementation, we'd load metadata from a side file
        File metadataFile = new File(compressedFile.getParent(), compressedFile.getName() + ".meta");
        if (metadataFile.exists()) {
            try {
                Properties props = new Properties();
                props.load(new FileInputStream(metadataFile));
                return new CompressionMetadata(
                    props.getProperty("originalFilename"),
                    Long.parseLong(props.getProperty("timestamp", "0")),
                    props.getProperty("comment")
                );
            } catch (IOException | NumberFormatException e) {
                // Return null if metadata can't be read
            }
        }
        return null;
    }
    
    /**
     * Gets overall compression statistics.
     */
    public CompressionStatistics getStatistics() {
        return new CompressionStatistics(
            compressionCount.get(),
            decompressionCount.get(),
            totalBytesCompressed.get(),
            totalBytesDecompressed.get(),
            getAverageCompressionRatio(),
            totalCompressionTime.get()
        );
    }
    
    /**
     * Gets the number of compression operations performed.
     */
    public int getCompressionCount() {
        return compressionCount.get();
    }
    
    /**
     * Gets total bytes compressed.
     */
    public long getTotalBytesCompressed() {
        return totalBytesCompressed.get();
    }
    
    /**
     * Gets average compression ratio.
     */
    public double getAverageCompressionRatio() {
        synchronized (compressionRatios) {
            if (compressionRatios.isEmpty()) {
                return 0.0;
            }
            return compressionRatios.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }
    
    // Helper methods
    private OutputStream createCompressedOutputStream(OutputStream base, Algorithm algorithm, Level level) throws IOException {
        switch (algorithm) {
            case GZIP:
                GZIPOutputStream gzos = new GZIPOutputStream(base);
                // Note: GZIPOutputStream doesn't expose deflater level directly in standard API
                return gzos;
            case DEFLATE:
                return new DeflaterOutputStream(base, new Deflater(level.getLevel()));
            default:
                throw new UnsupportedOperationException("Algorithm not supported: " + algorithm);
        }
    }
    
    private String getExtensionForAlgorithm(Algorithm algorithm) {
        switch (algorithm) {
            case GZIP: return ".gz";
            case DEFLATE: return ".deflate";
            case LZ4: return ".lz4";
            default: return ".compressed";
        }
    }
    
    private String removeCompressionExtension(String filename) {
        if (filename.endsWith(".gz")) {
            return filename.substring(0, filename.length() - 3);
        } else if (filename.endsWith(".deflate")) {
            return filename.substring(0, filename.length() - 8);
        } else if (filename.endsWith(".lz4")) {
            return filename.substring(0, filename.length() - 4);
        }
        return filename;
    }
    
    private void addFileToTar(TarOutputStream tos, File file, String entryName) throws IOException {
        TarEntry entry = new TarEntry(entryName);
        entry.setSize(file.length());
        entry.setModTime(file.lastModified());
        tos.putNextEntry(entry);
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                tos.write(buffer, 0, bytesRead);
            }
        }
        
        tos.closeEntry();
    }
    
    private void storeMetadata(File compressedFile, CompressionOptions options) throws IOException {
        File metadataFile = new File(compressedFile.getParent(), compressedFile.getName() + ".meta");
        Properties props = new Properties();
        
        if (options.getOriginalFilename() != null) {
            props.setProperty("originalFilename", options.getOriginalFilename());
        }
        if (options.getTimestamp() > 0) {
            props.setProperty("timestamp", String.valueOf(options.getTimestamp()));
        }
        if (options.getComment() != null) {
            props.setProperty("comment", options.getComment());
        }
        
        props.store(new FileOutputStream(metadataFile), "Compression metadata");
    }
    
    // Inner classes for data structures
    public static class CompressionOptions {
        private String originalFilename;
        private long timestamp;
        private String comment;
        
        public CompressionOptions setOriginalFilename(String filename) {
            this.originalFilename = filename;
            return this;
        }
        
        public CompressionOptions setTimestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public CompressionOptions setComment(String comment) {
            this.comment = comment;
            return this;
        }
        
        public String getOriginalFilename() { return originalFilename; }
        public long getTimestamp() { return timestamp; }
        public String getComment() { return comment; }
    }
    
    public static class CompressionStats {
        private final long originalSize;
        private final long compressedSize;
        private final double compressionRatio;
        private final double spaceSaved;
        
        public CompressionStats(long originalSize, long compressedSize, double compressionRatio, double spaceSaved) {
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.compressionRatio = compressionRatio;
            this.spaceSaved = spaceSaved;
        }
        
        public long getOriginalSize() { return originalSize; }
        public long getCompressedSize() { return compressedSize; }
        public double getCompressionRatio() { return compressionRatio; }
        public double getSpaceSaved() { return spaceSaved; }
    }
    
    public static class CompressionMetadata {
        private final String originalFilename;
        private final long timestamp;
        private final String comment;
        
        public CompressionMetadata(String originalFilename, long timestamp, String comment) {
            this.originalFilename = originalFilename;
            this.timestamp = timestamp;
            this.comment = comment;
        }
        
        public String getOriginalFilename() { return originalFilename; }
        public long getTimestamp() { return timestamp; }
        public String getComment() { return comment; }
    }
    
    public static class CompressionStatistics {
        private final int compressionOperations;
        private final int decompressionOperations;
        private final long totalBytesCompressed;
        private final long totalBytesDecompressed;
        private final double averageCompressionRatio;
        private final long totalCompressionTime;
        
        public CompressionStatistics(int compressionOperations, int decompressionOperations,
                                   long totalBytesCompressed, long totalBytesDecompressed,
                                   double averageCompressionRatio, long totalCompressionTime) {
            this.compressionOperations = compressionOperations;
            this.decompressionOperations = decompressionOperations;
            this.totalBytesCompressed = totalBytesCompressed;
            this.totalBytesDecompressed = totalBytesDecompressed;
            this.averageCompressionRatio = averageCompressionRatio;
            this.totalCompressionTime = totalCompressionTime;
        }
        
        public int getCompressionOperations() { return compressionOperations; }
        public int getDecompressionOperations() { return decompressionOperations; }
        public long getTotalBytesCompressed() { return totalBytesCompressed; }
        public long getTotalBytesDecompressed() { return totalBytesDecompressed; }
        public double getAverageCompressionRatio() { return averageCompressionRatio; }
        public long getTotalCompressionTime() { return totalCompressionTime; }
        
        @Override
        public String toString() {
            return String.format("CompressionStatistics{compression ops=%d, decompression ops=%d, " +
                    "bytes compressed=%d, bytes decompressed=%d, avg ratio=%.3f, total time=%dms}",
                    compressionOperations, decompressionOperations, totalBytesCompressed,
                    totalBytesDecompressed, averageCompressionRatio, totalCompressionTime);
        }
    }
    
    // Simple TAR implementation classes
    private static class TarOutputStream extends FilterOutputStream {
        private long entryBytesWritten = 0;
        
        public TarOutputStream(OutputStream out) {
            super(out);
        }
        
        public void putNextEntry(TarEntry entry) throws IOException {
            // Simplified TAR header writing
            byte[] header = new byte[512];
            writeString(header, 0, entry.getName(), 100);
            writeOctal(header, 124, entry.getSize(), 12);
            writeOctal(header, 136, entry.getModTime() / 1000, 12);
            out.write(header);
            entryBytesWritten = 0;
        }
        
        public void closeEntry() throws IOException {
            // Pad to 512-byte boundary
            long remaining = 512 - (entryBytesWritten % 512);
            if (remaining < 512) {
                byte[] padding = new byte[(int) remaining];
                out.write(padding);
            }
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            entryBytesWritten += len;
        }
        
        private void writeString(byte[] header, int offset, String value, int maxLen) {
            byte[] bytes = value.getBytes();
            int len = Math.min(bytes.length, maxLen - 1);
            System.arraycopy(bytes, 0, header, offset, len);
        }
        
        private void writeOctal(byte[] header, int offset, long value, int maxLen) {
            String octal = Long.toOctalString(value);
            writeString(header, offset, octal, maxLen);
        }
    }
    
    private static class TarInputStream extends FilterInputStream {
        public TarInputStream(InputStream in) {
            super(in);
        }
        
        public TarEntry getNextEntry() throws IOException {
            byte[] header = new byte[512];
            int bytesRead = in.read(header);
            if (bytesRead < 512) {
                return null;
            }
            
            String name = readString(header, 0, 100);
            if (name.trim().isEmpty()) {
                return null;
            }
            
            long size = readOctal(header, 124, 12);
            long modTime = readOctal(header, 136, 12) * 1000;
            
            return new TarEntry(name.trim(), size, modTime);
        }
        
        private String readString(byte[] header, int offset, int maxLen) {
            int len = 0;
            while (len < maxLen && header[offset + len] != 0) {
                len++;
            }
            return new String(header, offset, len);
        }
        
        private long readOctal(byte[] header, int offset, int maxLen) {
            String octal = readString(header, offset, maxLen).trim();
            if (octal.isEmpty()) {
                return 0;
            }
            try {
                return Long.parseLong(octal, 8);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
    
    private static class TarEntry {
        private final String name;
        private final long size;
        private final long modTime;
        
        public TarEntry(String name) {
            this(name, 0, System.currentTimeMillis());
        }
        
        public TarEntry(String name, long size, long modTime) {
            this.name = name;
            this.size = size;
            this.modTime = modTime;
        }
        
        public String getName() { return name; }
        public long getSize() { return size; }
        public long getModTime() { return modTime; }
        public boolean isDirectory() { return name.endsWith("/"); }
        
        public void setSize(long size) {
            // In a real implementation, this would be mutable
        }
        
        public void setModTime(long modTime) {
            // In a real implementation, this would be mutable
        }
    }
}
