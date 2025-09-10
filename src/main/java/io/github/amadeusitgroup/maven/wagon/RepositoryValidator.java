package io.github.amadeusitgroup.maven.wagon;

import java.util.regex.Pattern;

/**
 * Utility class for validating Maven repository directory structures and paths.
 * Ensures compliance with Maven repository layout standards.
 */
public class RepositoryValidator {

    // Maven coordinate patterns
    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern CLASSIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    
    // Maven repository path pattern: groupId/artifactId/version/filename
    private static final Pattern REPOSITORY_PATH_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._-]+)*)/([a-zA-Z0-9._-]+)/([a-zA-Z0-9._-]+)/(.+)$"
    );
    
    // Maven metadata path pattern: groupId/.../maven-metadata.xml
    private static final Pattern METADATA_PATH_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._-]+)*)/maven-metadata\\.xml(?:\\.(md5|sha1|sha256))?$"
    );
    
    // Maven artifact filename patterns (handles classifiers properly)
    private static final Pattern ARTIFACT_FILENAME_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9._-]+)-([a-zA-Z0-9._-]+)(?:-([a-zA-Z0-9._-]+))?\\.([a-zA-Z0-9.]+)(?:\\.(md5|sha1|sha256|asc))?$"
    );
    
    // Maven metadata filename pattern
    private static final Pattern METADATA_FILENAME_PATTERN = Pattern.compile(
        "^maven-metadata\\.xml(?:\\.(md5|sha1|sha256))?$"
    );

    /**
     * Validates a Maven repository path for compliance with Maven repository layout standards.
     * 
     * @param repositoryPath the repository path to validate (e.g., "com/example/my-artifact/1.0.0/my-artifact-1.0.0.jar")
     * @return ValidationResult containing validation status and details
     */
    public static ValidationResult validateRepositoryPath(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.trim().isEmpty()) {
            return ValidationResult.invalid("Repository path cannot be null or empty");
        }

        // Normalize path separators
        String normalizedPath = repositoryPath.replace('\\', '/');
        
        // Check for invalid characters
        if (normalizedPath.contains("..") || normalizedPath.contains("//")) {
            return ValidationResult.invalid("Repository path contains invalid sequences (.., //)");
        }

        // Check if it's a metadata path first
        java.util.regex.Matcher metadataMatcher = METADATA_PATH_PATTERN.matcher(normalizedPath);
        if (metadataMatcher.matches()) {
            // It's a metadata file at group or artifact level
            return ValidationResult.valid("Maven metadata path is valid");
        }

        // Match against repository path pattern
        java.util.regex.Matcher matcher = REPOSITORY_PATH_PATTERN.matcher(normalizedPath);
        if (!matcher.matches()) {
            return ValidationResult.invalid("Repository path does not match Maven repository layout: " + normalizedPath);
        }

        String groupIdPath = matcher.group(1);
        String artifactId = matcher.group(2);
        String version = matcher.group(3);
        String filename = matcher.group(4);

        // Validate components
        ValidationResult groupResult = validateGroupIdPath(groupIdPath);
        if (!groupResult.isValid()) {
            return groupResult;
        }

        ValidationResult artifactResult = validateArtifactId(artifactId);
        if (!artifactResult.isValid()) {
            return artifactResult;
        }

        ValidationResult versionResult = validateVersion(version);
        if (!versionResult.isValid()) {
            return versionResult;
        }

        // Validate filename format and consistency
        ValidationResult filenameResult = validateFilename(filename, artifactId, version);
        if (!filenameResult.isValid()) {
            return filenameResult;
        }

        return ValidationResult.valid("Repository path is valid");
    }

    /**
     * Validates a group ID path (e.g., "com/example/mygroup").
     */
    private static ValidationResult validateGroupIdPath(String groupIdPath) {
        if (groupIdPath == null || groupIdPath.trim().isEmpty()) {
            return ValidationResult.invalid("Group ID path cannot be null or empty");
        }

        String[] parts = groupIdPath.split("/");
        for (String part : parts) {
            if (!GROUP_ID_PATTERN.matcher(part).matches()) {
                return ValidationResult.invalid("Invalid group ID component: " + part);
            }
        }

        return ValidationResult.valid("Group ID path is valid");
    }

    /**
     * Validates an artifact ID.
     */
    private static ValidationResult validateArtifactId(String artifactId) {
        if (artifactId == null || artifactId.trim().isEmpty()) {
            return ValidationResult.invalid("Artifact ID cannot be null or empty");
        }

        if (!ARTIFACT_ID_PATTERN.matcher(artifactId).matches()) {
            return ValidationResult.invalid("Invalid artifact ID: " + artifactId);
        }

        return ValidationResult.valid("Artifact ID is valid");
    }

    /**
     * Validates a version string.
     */
    private static ValidationResult validateVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            return ValidationResult.invalid("Version cannot be null or empty");
        }

        if (!VERSION_PATTERN.matcher(version).matches()) {
            return ValidationResult.invalid("Invalid version: " + version);
        }

        return ValidationResult.valid("Version is valid");
    }

    /**
     * Validates a filename within the context of artifact ID and version.
     */
    private static ValidationResult validateFilename(String filename, String artifactId, String version) {
        if (filename == null || filename.trim().isEmpty()) {
            return ValidationResult.invalid("Filename cannot be null or empty");
        }

        // Check if it's a metadata file
        if (METADATA_FILENAME_PATTERN.matcher(filename).matches()) {
            return ValidationResult.valid("Maven metadata filename is valid");
        }

        // Parse filename manually for better handling of complex extensions
        String baseFilename = filename;
        String checksumExt = null;
        
        // Check for checksum extensions
        if (filename.endsWith(".md5")) {
            checksumExt = "md5";
            baseFilename = filename.substring(0, filename.length() - 4);
        } else if (filename.endsWith(".sha1")) {
            checksumExt = "sha1";
            baseFilename = filename.substring(0, filename.length() - 5);
        } else if (filename.endsWith(".sha256")) {
            checksumExt = "sha256";
            baseFilename = filename.substring(0, filename.length() - 7);
        } else if (filename.endsWith(".asc")) {
            checksumExt = "asc";
            baseFilename = filename.substring(0, filename.length() - 4);
        }
        
        // Expected filename pattern: artifactId-version[-classifier].extension
        String expectedPrefix = artifactId + "-" + version;
        if (!baseFilename.startsWith(expectedPrefix)) {
            // Try to extract actual artifact ID and version for better error message
            int firstDash = baseFilename.indexOf('-');
            if (firstDash > 0) {
                String actualArtifactId = baseFilename.substring(0, firstDash);
                if (!actualArtifactId.equals(artifactId)) {
                    return ValidationResult.invalid("Artifact ID in filename (" + actualArtifactId + ") does not match path artifact ID (" + artifactId + ")");
                }
            }
            return ValidationResult.invalid("Filename does not start with expected artifact-version pattern: " + expectedPrefix);
        }
        
        // Extract the part after artifactId-version
        String suffix = baseFilename.substring(expectedPrefix.length());
        
        // Check if there's a classifier (starts with -)
        String classifier = null;
        if (suffix.startsWith("-")) {
            int dotIndex = suffix.indexOf('.');
            if (dotIndex > 1) {
                classifier = suffix.substring(1, dotIndex);
                if (!CLASSIFIER_PATTERN.matcher(classifier).matches()) {
                    return ValidationResult.invalid("Invalid classifier: " + classifier);
                }
            }
        } else if (!suffix.startsWith(".")) {
            return ValidationResult.invalid("Invalid filename format after version: " + suffix);
        }

        return ValidationResult.valid("Artifact filename is valid");
    }

    /**
     * Extracts Maven coordinates from a repository path.
     * 
     * @param repositoryPath the repository path
     * @return MavenCoordinates object or null if path is invalid
     */
    public static MavenCoordinates extractCoordinates(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.trim().isEmpty()) {
            return null;
        }
        
        String normalizedPath = repositoryPath.replace('\\', '/');
        
        // Check if it's a metadata path first
        java.util.regex.Matcher metadataMatcher = METADATA_PATH_PATTERN.matcher(normalizedPath);
        if (metadataMatcher.matches()) {
            String groupIdPath = metadataMatcher.group(1);
            String groupId = groupIdPath.replace('/', '.');
            return new MavenCoordinates(groupId, "maven-metadata", "xml", null, "xml");
        }

        // Try regular artifact path
        java.util.regex.Matcher matcher = REPOSITORY_PATH_PATTERN.matcher(normalizedPath);
        if (!matcher.matches()) {
            return null;
        }

        String groupIdPath = matcher.group(1);
        String artifactId = matcher.group(2);
        String version = matcher.group(3);
        String filename = matcher.group(4);

        // Validate the path before extracting coordinates
        ValidationResult validation = validateRepositoryPath(repositoryPath);
        if (!validation.isValid()) {
            return null;
        }

        // Convert group ID path to group ID
        String groupId = groupIdPath.replace('/', '.');

        // Extract classifier and extension from filename
        String classifier = null;
        String extension = null;

        if (METADATA_FILENAME_PATTERN.matcher(filename).matches()) {
            extension = "xml";
        } else {
            // Parse filename manually for better classifier extraction
            String baseFilename = filename;
            
            // Remove checksum extensions
            if (filename.endsWith(".md5")) {
                baseFilename = filename.substring(0, filename.length() - 4);
            } else if (filename.endsWith(".sha1")) {
                baseFilename = filename.substring(0, filename.length() - 5);
            } else if (filename.endsWith(".sha256")) {
                baseFilename = filename.substring(0, filename.length() - 7);
            } else if (filename.endsWith(".asc")) {
                baseFilename = filename.substring(0, filename.length() - 4);
            }
            
            // Expected pattern: artifactId-version[-classifier].extension
            String expectedPrefix = artifactId + "-" + version;
            if (baseFilename.startsWith(expectedPrefix)) {
                String suffix = baseFilename.substring(expectedPrefix.length());
                
                if (suffix.startsWith("-")) {
                    // Has classifier
                    int dotIndex = suffix.indexOf('.');
                    if (dotIndex > 1) {
                        classifier = suffix.substring(1, dotIndex);
                        extension = suffix.substring(dotIndex + 1);
                    }
                } else if (suffix.startsWith(".")) {
                    // No classifier
                    extension = suffix.substring(1);
                }
            }
        }

        return new MavenCoordinates(groupId, artifactId, version, classifier, extension);
    }

    /**
     * Result of a validation operation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult valid(String message) {
            return new ValidationResult(true, message);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "ValidationResult{valid=" + valid + ", message='" + message + "'}";
        }
    }

    /**
     * Represents Maven coordinates extracted from a repository path.
     */
    public static class MavenCoordinates {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String classifier;
        private final String extension;

        public MavenCoordinates(String groupId, String artifactId, String version, String classifier, String extension) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.classifier = classifier;
            this.extension = extension;
        }

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String getVersion() { return version; }
        public String getClassifier() { return classifier; }
        public String getExtension() { return extension; }

        @Override
        public String toString() {
            return "MavenCoordinates{" +
                "groupId='" + groupId + '\'' +
                ", artifactId='" + artifactId + '\'' +
                ", version='" + version + '\'' +
                ", classifier='" + classifier + '\'' +
                ", extension='" + extension + '\'' +
                '}';
        }
    }
}
