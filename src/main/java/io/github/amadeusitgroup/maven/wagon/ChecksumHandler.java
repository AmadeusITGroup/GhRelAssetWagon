package io.github.amadeusitgroup.maven.wagon;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles checksum generation and validation for Maven artifacts.
 * Supports MD5, SHA-1, and SHA-256 algorithms as required by Maven repositories.
 */
public class ChecksumHandler {
    
    private static final String[] SUPPORTED_ALGORITHMS = {"MD5", "SHA-1", "SHA-256"};
    private static final int BUFFER_SIZE = 8192;
    
    /**
     * Generates checksums for a file using the specified algorithms.
     * 
     * @param file The file to generate checksums for
     * @param algorithms The checksum algorithms to use (MD5, SHA-1, SHA-256)
     * @return Map of algorithm name to checksum hex string
     * @throws IOException If file cannot be read
     */
    public Map<String, String> generateChecksums(File file, String... algorithms) throws IOException {
        if (algorithms.length == 0) {
            algorithms = SUPPORTED_ALGORITHMS;
        }
        
        Map<String, MessageDigest> digestMap = new HashMap<>();
        Map<String, String> checksums = new HashMap<>();
        
        // Initialize message digests
        for (String algorithm : algorithms) {
            try {
                digestMap.put(algorithm, MessageDigest.getInstance(algorithm));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("Unsupported algorithm: " + algorithm, e);
            }
        }
        
        // Read file and update all digests
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                for (MessageDigest digest : digestMap.values()) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
        }
        
        // Generate hex strings
        for (Map.Entry<String, MessageDigest> entry : digestMap.entrySet()) {
            String algorithm = entry.getKey();
            MessageDigest digest = entry.getValue();
            String checksum = bytesToHex(digest.digest());
            checksums.put(algorithm, checksum);
        }
        
        return checksums;
    }
    
    /**
     * Generates checksum files for a Maven artifact.
     * Creates .md5, .sha1, and .sha256 files alongside the original file.
     * 
     * @param artifactFile The artifact file
     * @return Map of algorithm to checksum file path
     * @throws IOException If files cannot be written
     */
    public Map<String, String> generateChecksumFiles(File artifactFile) throws IOException {
        Map<String, String> checksums = generateChecksums(artifactFile);
        Map<String, String> checksumFiles = new HashMap<>();
        
        String basePath = artifactFile.getAbsolutePath();
        
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            String algorithm = entry.getKey();
            String checksum = entry.getValue();
            
            String extension = getChecksumFileExtension(algorithm);
            String checksumFilePath = basePath + "." + extension;
            
            try (FileWriter writer = new FileWriter(checksumFilePath)) {
                writer.write(checksum);
            }
            
            checksumFiles.put(algorithm, checksumFilePath);
        }
        
        return checksumFiles;
    }
    
    /**
     * Validates a file against an expected checksum.
     * 
     * @param file The file to validate
     * @param expectedChecksum The expected checksum value
     * @param algorithm The checksum algorithm used
     * @return true if checksum matches, false otherwise
     * @throws IOException If file cannot be read
     */
    public boolean validateChecksum(File file, String expectedChecksum, String algorithm) throws IOException {
        Map<String, String> checksums = generateChecksums(file, algorithm);
        String actualChecksum = checksums.get(algorithm);
        return expectedChecksum.equalsIgnoreCase(actualChecksum);
    }
    
    /**
     * Reads checksum from a checksum file.
     * 
     * @param checksumFile The checksum file to read
     * @return The checksum value
     * @throws IOException If file cannot be read
     */
    public String readChecksumFile(File checksumFile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(checksumFile))) {
            String line = reader.readLine();
            if (line != null) {
                // Handle both formats: "checksum" and "checksum  filename"
                String[] parts = line.trim().split("\\s+");
                return parts[0];
            }
            return null;
        }
    }
    
    /**
     * Validates a file against its corresponding checksum file.
     * 
     * @param artifactFile The artifact file
     * @param checksumFile The checksum file
     * @param algorithm The algorithm used for the checksum
     * @return true if validation passes, false otherwise
     * @throws IOException If files cannot be read
     */
    public boolean validateAgainstChecksumFile(File artifactFile, File checksumFile, String algorithm) throws IOException {
        String expectedChecksum = readChecksumFile(checksumFile);
        if (expectedChecksum == null) {
            return false;
        }
        return validateChecksum(artifactFile, expectedChecksum, algorithm);
    }
    
    /**
     * Gets the standard file extension for a checksum algorithm.
     * 
     * @param algorithm The checksum algorithm
     * @return The file extension (without dot)
     */
    public String getChecksumFileExtension(String algorithm) {
        switch (algorithm.toUpperCase()) {
            case "MD5":
                return "md5";
            case "SHA-1":
                return "sha1";
            case "SHA-256":
                return "sha256";
            default:
                return algorithm.toLowerCase().replace("-", "");
        }
    }
    
    /**
     * Determines the algorithm from a checksum file extension.
     * 
     * @param extension The file extension (with or without dot)
     * @return The algorithm name
     */
    public String getAlgorithmFromExtension(String extension) {
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        switch (ext.toLowerCase()) {
            case "md5":
                return "MD5";
            case "sha1":
                return "SHA-1";
            case "sha256":
                return "SHA-256";
            default:
                throw new IllegalArgumentException("Unknown checksum extension: " + extension);
        }
    }
    
    /**
     * Converts byte array to hexadecimal string.
     * 
     * @param bytes The byte array
     * @return Hexadecimal string representation
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    
    /**
     * Checks if a file appears to be a checksum file based on its extension.
     * 
     * @param filename The filename to check
     * @return true if it's a checksum file, false otherwise
     */
    public boolean isChecksumFile(String filename) {
        String lowerName = filename.toLowerCase();
        return lowerName.endsWith(".md5") || 
               lowerName.endsWith(".sha1") || 
               lowerName.endsWith(".sha256");
    }
    
    /**
     * Gets the original artifact filename from a checksum filename.
     * 
     * @param checksumFilename The checksum filename
     * @return The original artifact filename
     */
    public String getArtifactFilename(String checksumFilename) {
        String lowerName = checksumFilename.toLowerCase();
        if (lowerName.endsWith(".md5")) {
            return checksumFilename.substring(0, checksumFilename.length() - 4);
        } else if (lowerName.endsWith(".sha1")) {
            return checksumFilename.substring(0, checksumFilename.length() - 5);
        } else if (lowerName.endsWith(".sha256")) {
            return checksumFilename.substring(0, checksumFilename.length() - 7);
        }
        return checksumFilename;
    }
}
