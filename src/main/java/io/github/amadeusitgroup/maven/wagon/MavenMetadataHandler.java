package io.github.amadeusitgroup.maven.wagon;

import java.io.IOException;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Handles Maven metadata generation for group, artifact, and version levels.
 * Generates maven-metadata.xml files according to Maven repository standards.
 */
public class MavenMetadataHandler {
    
    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");
    
    static {
        TIMESTAMP_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    /**
     * Generates group-level metadata for plugin mappings.
     * 
     * @param groupId The group ID
     * @param plugins List of plugin artifacts in this group
     * @return XML content for group-level maven-metadata.xml
     */
    public String generateGroupMetadata(String groupId, List<PluginInfo> plugins) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<metadata>\n");
        xml.append("  <groupId>").append(escapeXml(groupId)).append("</groupId>\n");
        
        if (plugins != null && !plugins.isEmpty()) {
            xml.append("  <plugins>\n");
            for (PluginInfo plugin : plugins) {
                xml.append("    <plugin>\n");
                xml.append("      <name>").append(escapeXml(plugin.name)).append("</name>\n");
                xml.append("      <prefix>").append(escapeXml(plugin.prefix)).append("</prefix>\n");
                xml.append("      <artifactId>").append(escapeXml(plugin.artifactId)).append("</artifactId>\n");
                xml.append("    </plugin>\n");
            }
            xml.append("  </plugins>\n");
        }
        
        xml.append("</metadata>\n");
        return xml.toString();
    }
    
    /**
     * Generates artifact-level metadata with version information.
     * 
     * @param groupId The group ID
     * @param artifactId The artifact ID
     * @param versions List of available versions
     * @return XML content for artifact-level maven-metadata.xml
     */
    public String generateArtifactMetadata(String groupId, String artifactId, List<String> versions) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<metadata>\n");
        xml.append("  <groupId>").append(escapeXml(groupId)).append("</groupId>\n");
        xml.append("  <artifactId>").append(escapeXml(artifactId)).append("</artifactId>\n");
        
        if (versions != null && !versions.isEmpty()) {
            // Sort versions and determine latest/release
            List<String> sortedVersions = new ArrayList<>(versions);
            sortedVersions.sort(this::compareVersions);
            
            String latest = sortedVersions.get(sortedVersions.size() - 1);
            String release = findLatestRelease(sortedVersions);
            String lastUpdated = getCurrentTimestamp();
            
            xml.append("  <versioning>\n");
            xml.append("    <latest>").append(escapeXml(latest)).append("</latest>\n");
            if (release != null) {
                xml.append("    <release>").append(escapeXml(release)).append("</release>\n");
            }
            xml.append("    <versions>\n");
            for (String version : sortedVersions) {
                xml.append("      <version>").append(escapeXml(version)).append("</version>\n");
            }
            xml.append("    </versions>\n");
            xml.append("    <lastUpdated>").append(lastUpdated).append("</lastUpdated>\n");
            xml.append("  </versioning>\n");
        }
        
        xml.append("</metadata>\n");
        return xml.toString();
    }
    
    /**
     * Generates version-level metadata for SNAPSHOT versions.
     * 
     * @param groupId The group ID
     * @param artifactId The artifact ID
     * @param version The SNAPSHOT version
     * @param snapshotVersions List of snapshot versions with timestamps
     * @return XML content for version-level maven-metadata.xml
     */
    public String generateVersionMetadata(String groupId, String artifactId, String version, 
                                        List<SnapshotVersionInfo> snapshotVersions) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<metadata>\n");
        xml.append("  <groupId>").append(escapeXml(groupId)).append("</groupId>\n");
        xml.append("  <artifactId>").append(escapeXml(artifactId)).append("</artifactId>\n");
        xml.append("  <version>").append(escapeXml(version)).append("</version>\n");
        
        if (snapshotVersions != null && !snapshotVersions.isEmpty()) {
            // Find latest snapshot
            SnapshotVersionInfo latest = snapshotVersions.stream()
                .max(Comparator.comparing(sv -> sv.updated))
                .orElse(snapshotVersions.get(0));
            
            xml.append("  <versioning>\n");
            xml.append("    <snapshot>\n");
            xml.append("      <timestamp>").append(escapeXml(latest.timestamp)).append("</timestamp>\n");
            xml.append("      <buildNumber>").append(latest.buildNumber).append("</buildNumber>\n");
            xml.append("    </snapshot>\n");
            xml.append("    <lastUpdated>").append(escapeXml(latest.updated)).append("</lastUpdated>\n");
            xml.append("    <snapshotVersions>\n");
            for (SnapshotVersionInfo sv : snapshotVersions) {
                xml.append("      <snapshotVersion>\n");
                if (sv.classifier != null) {
                    xml.append("        <classifier>").append(escapeXml(sv.classifier)).append("</classifier>\n");
                }
                xml.append("        <extension>").append(escapeXml(sv.extension)).append("</extension>\n");
                xml.append("        <value>").append(escapeXml(sv.value)).append("</value>\n");
                xml.append("        <updated>").append(escapeXml(sv.updated)).append("</updated>\n");
                xml.append("      </snapshotVersion>\n");
            }
            xml.append("    </snapshotVersions>\n");
            xml.append("  </versioning>\n");
        }
        
        xml.append("</metadata>\n");
        return xml.toString();
    }
    
    /**
     * Parses existing metadata from XML content.
     * Note: This is a simplified implementation for basic parsing needs.
     * 
     * @param xmlContent The XML content to parse
     * @param metadataType The type of metadata (group, artifact, or version)
     * @return Simple map with parsed values
     */
    public Map<String, Object> parseMetadata(String xmlContent, MetadataType metadataType) {
        Map<String, Object> result = new HashMap<>();
        
        // Simple XML parsing - extract basic elements
        if (xmlContent.contains("<groupId>")) {
            String groupId = extractXmlValue(xmlContent, "groupId");
            result.put("groupId", groupId);
        }
        
        if (xmlContent.contains("<artifactId>")) {
            String artifactId = extractXmlValue(xmlContent, "artifactId");
            result.put("artifactId", artifactId);
        }
        
        if (xmlContent.contains("<version>")) {
            String version = extractXmlValue(xmlContent, "version");
            result.put("version", version);
        }
        
        if (xmlContent.contains("<latest>")) {
            String latest = extractXmlValue(xmlContent, "latest");
            result.put("latest", latest);
        }
        
        if (xmlContent.contains("<release>")) {
            String release = extractXmlValue(xmlContent, "release");
            result.put("release", release);
        }
        
        return result;
    }
    
    /**
     * Simple XML value extraction helper.
     */
    private String extractXmlValue(String xml, String tagName) {
        String startTag = "<" + tagName + ">";
        String endTag = "</" + tagName + ">";
        int startIndex = xml.indexOf(startTag);
        if (startIndex == -1) return null;
        startIndex += startTag.length();
        int endIndex = xml.indexOf(endTag, startIndex);
        if (endIndex == -1) return null;
        return xml.substring(startIndex, endIndex).trim();
    }
    
    private String getCurrentTimestamp() {
        return TIMESTAMP_FORMAT.format(new Date());
    }
    
    private String findLatestRelease(List<String> sortedVersions) {
        // Find the latest non-SNAPSHOT version
        for (int i = sortedVersions.size() - 1; i >= 0; i--) {
            String version = sortedVersions.get(i);
            if (!version.endsWith("-SNAPSHOT")) {
                return version;
            }
        }
        return null; // No release versions found
    }
    
    private int compareVersions(String v1, String v2) {
        // Simple version comparison - can be enhanced with proper semantic versioning
        String[] parts1 = v1.replaceAll("-SNAPSHOT", "").split("\\.");
        String[] parts2 = v2.replaceAll("-SNAPSHOT", "").split("\\.");
        
        int maxLength = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLength; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        
        // If base versions are equal, non-SNAPSHOT comes before SNAPSHOT
        if (v1.endsWith("-SNAPSHOT") && !v2.endsWith("-SNAPSHOT")) {
            return -1;
        } else if (!v1.endsWith("-SNAPSHOT") && v2.endsWith("-SNAPSHOT")) {
            return 1;
        }
        
        return 0;
    }
    
    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            // Handle non-numeric version parts (alpha, beta, etc.)
            return part.hashCode();
        }
    }
    
    /**
     * Escapes XML special characters.
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }
    
    // Metadata type enumeration
    public enum MetadataType {
        GROUP, ARTIFACT, VERSION
    }
    
    // Simple data classes for metadata information
    public static class PluginInfo {
        public String name;
        public String prefix;
        public String artifactId;
        
        public PluginInfo() {}
        
        public PluginInfo(String name, String prefix, String artifactId) {
            this.name = name;
            this.prefix = prefix;
            this.artifactId = artifactId;
        }
    }
    
    public static class SnapshotVersionInfo {
        public String classifier;
        public String extension;
        public String value;
        public String updated;
        public String timestamp;
        public int buildNumber;
        
        public SnapshotVersionInfo() {}
        
        public SnapshotVersionInfo(String classifier, String extension, String value, String updated, String timestamp, int buildNumber) {
            this.classifier = classifier;
            this.extension = extension;
            this.value = value;
            this.updated = updated;
            this.timestamp = timestamp;
            this.buildNumber = buildNumber;
        }
    }
}
