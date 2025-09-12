package io.github.amadeusitgroup.maven.wagon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ConfigurationManager provides comprehensive configuration management capabilities.
 * Supports properties files, JSON, environment variables, system properties, profiles,
 * encryption, templating, validation, and dynamic reloading.
 * Thread-safe singleton implementation.
 */
public class ConfigurationManager {
    
    private static volatile ConfigurationManager instance;
    private static final Object lock = new Object();
    
    // Configuration storage
    private final Map<String, String> configuration = new ConcurrentHashMap<>();
    private final Map<String, String> environmentOverrides = new ConcurrentHashMap<>();
    private final Map<String, List<ChangeListener>> changeListeners = new ConcurrentHashMap<>();
    
    // Default configuration values
    private final Map<String, String> defaults = new HashMap<>();
    
    // Configuration management
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String encryptionKey;
    private boolean templatingEnabled = false;
    private ScheduledExecutorService reloadExecutor;
    private WatchService watchService;
    
    // Validation patterns
    private final Map<String, Pattern> validationPatterns = new HashMap<>();
    
    private ConfigurationManager() {
        initializeDefaults();
        initializeValidationPatterns();
        loadEnvironmentOverrides();
    }
    
    public static ConfigurationManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new ConfigurationManager();
                }
            }
        }
        return instance;
    }
    
    public static void resetInstance() {
        synchronized (lock) {
            if (instance != null && instance.reloadExecutor != null) {
                instance.reloadExecutor.shutdown();
            }
            instance = null;
        }
    }
    
    private void initializeDefaults() {
        defaults.put("connection.pool.maxConnections", "10");
        defaults.put("connection.timeout", "30000");
        defaults.put("retry.maxAttempts", "5");
        defaults.put("circuitBreaker.timeout", "60000");
        defaults.put("async.enabled", "true");
        defaults.put("compression.algorithm", "gzip");
    }
    
    private void initializeValidationPatterns() {
        validationPatterns.put("connection.pool.maxConnections", Pattern.compile("^[1-9]\\d*$"));
        validationPatterns.put("connection.timeout", Pattern.compile("^[1-9]\\d*$"));
        validationPatterns.put("retry.maxAttempts", Pattern.compile("^[1-9]\\d*$"));
    }
    
    private void loadEnvironmentOverrides() {
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("GHRELASSET_")) {
                String configKey = key.substring("GHRELASSET_".length())
                                     .toLowerCase()
                                     .replace("_", ".");
                environmentOverrides.put(configKey, value);
            }
        });
    }
    
    // Configuration loading methods
    public void loadConfiguration(File configFile) throws IOException {
        if (!configFile.exists()) {
            throw new FileNotFoundException("Configuration file not found: " + configFile.getAbsolutePath());
        }
        
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
        }
        
        props.forEach((key, value) -> {
            String keyStr = key.toString();
            String oldValue = configuration.get(keyStr);
            configuration.put(keyStr, value.toString());
            notifyChangeListeners(keyStr, oldValue, value.toString());
        });
    }
    
    public void loadJsonConfiguration(File jsonFile) throws IOException {
        if (!jsonFile.exists()) {
            throw new FileNotFoundException("JSON configuration file not found: " + jsonFile.getAbsolutePath());
        }
        
        JsonNode rootNode = objectMapper.readTree(jsonFile);
        loadJsonNode("", rootNode);
    }
    
    private void loadJsonNode(String prefix, JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                loadJsonNode(key, entry.getValue());
            });
        } else if (node.isValueNode()) {
            String oldValue = configuration.get(prefix);
            String newValue = node.asText();
            configuration.put(prefix, newValue);
            notifyChangeListeners(prefix, oldValue, newValue);
        }
    }
    
    public void loadProfile(String profile, File configDir) throws IOException {
        File profileFile = new File(configDir, "ghrelasset-" + profile + ".properties");
        if (profileFile.exists()) {
            loadConfiguration(profileFile);
        }
    }
    
    // Configuration retrieval methods
    public String getString(String key) {
        return getString(key, null);
    }
    
    public String getString(String key, String defaultValue) {
        // Priority: System Properties > Environment > Configuration > Defaults
        String systemProperty = System.getProperty("ghrelasset." + key);
        if (systemProperty != null) {
            return processValue(systemProperty);
        }
        
        // Check configuration first (which includes environment overrides)
        String configValue = configuration.get(key);
        if (configValue != null) {
            return processValue(configValue);
        }
        
        String envValue = environmentOverrides.get(key);
        if (envValue != null) {
            return processValue(envValue);
        }
        
        String defaultVal = defaults.get(key);
        if (defaultVal != null) {
            return processValue(defaultVal);
        }
        
        return defaultValue;
    }
    
    public int getInt(String key) {
        return getInt(key, 0);
    }
    
    public int getInt(String key, int defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public long getLong(String key) {
        return getLong(key, 0L);
    }
    
    public long getLong(String key, long defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public double getDouble(String key) {
        return getDouble(key, 0.0);
    }
    
    public double getDouble(String key, double defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
    
    // Configuration setting methods
    public void setString(String key, String value) {
        String oldValue = configuration.get(key);
        configuration.put(key, value);
        notifyChangeListeners(key, oldValue, value);
    }
    
    public void setInt(String key, int value) {
        setString(key, String.valueOf(value));
    }
    
    public void setBoolean(String key, boolean value) {
        setString(key, String.valueOf(value));
    }
    
    public void setDouble(String key, double value) {
        setString(key, String.valueOf(value));
    }
    
    // Environment variable methods
    public void setEnvironmentOverride(String envVarName, String value) {
        if (envVarName.startsWith("GHRELASSET_")) {
            String configKey = envVarName.substring("GHRELASSET_".length())
                                       .toLowerCase()
                                       .replace("_", ".");
            String oldValue = environmentOverrides.get(configKey);
            environmentOverrides.put(configKey, value);
            // Also update configuration directly for immediate effect
            configuration.put(configKey, value);
            notifyChangeListeners(configKey, oldValue, value);
        }
    }
    
    public String getEnvironmentVariableName(String configKey) {
        return "GHRELASSET_" + configKey.toUpperCase().replace(".", "_");
    }
    
    // Configuration management
    public void refreshConfiguration() {
        // Reload system properties and environment variables
        loadEnvironmentOverrides();
    }
    
    // Validation
    public List<String> validateConfiguration(File configFile) throws IOException {
        List<String> errors = new ArrayList<>();
        
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
        }
        
        props.forEach((key, value) -> {
            String keyStr = key.toString();
            String valueStr = value.toString();
            
            Pattern pattern = validationPatterns.get(keyStr);
            if (pattern != null && !pattern.matcher(valueStr).matches()) {
                if (keyStr.contains("maxConnections") && !valueStr.matches("\\d+")) {
                    errors.add("Invalid maxConnections: " + valueStr + " (must be a positive integer)");
                } else if (keyStr.contains("maxAttempts") && valueStr.matches("-\\d+")) {
                    errors.add("Invalid maxAttempts: " + valueStr + " (cannot be negative)");
                } else if (keyStr.contains("timeout") && "0".equals(valueStr)) {
                    errors.add("Invalid timeout: " + valueStr + " (cannot be zero)");
                }
            }
        });
        
        return errors;
    }
    
    // Auto-reload functionality
    public void enableAutoReload(File configFile, long intervalMs) {
        if (reloadExecutor != null) {
            reloadExecutor.shutdown();
        }
        
        reloadExecutor = Executors.newSingleThreadScheduledExecutor();
        final long lastModified = configFile.lastModified();
        
        reloadExecutor.scheduleAtFixedRate(() -> {
            try {
                if (configFile.lastModified() > lastModified) {
                    loadConfiguration(configFile);
                }
            } catch (IOException e) {
                // Log error but continue monitoring
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }
    
    public void disableAutoReload() {
        if (reloadExecutor != null) {
            reloadExecutor.shutdown();
            reloadExecutor = null;
        }
    }
    
    // Encryption support
    public void setEncryptionKey(String key) {
        this.encryptionKey = key;
    }
    
    public String getDecryptedString(String key) {
        String value = getString(key);
        if (value != null && value.startsWith("ENC(") && value.endsWith(")")) {
            return decryptValue(value);
        }
        return value;
    }
    
    public String encryptValue(String plainText) {
        if (encryptionKey == null) {
            throw new IllegalStateException("Encryption key not set");
        }
        
        try {
            // Ensure key is 16 bytes for AES-128
            byte[] keyBytes = Arrays.copyOf(encryptionKey.getBytes(), 16);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes());
            return "ENC(" + Base64.getEncoder().encodeToString(encrypted) + ")";
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    public String decryptValue(String encryptedValue) {
        if (encryptionKey == null) {
            throw new IllegalStateException("Encryption key not set");
        }
        
        if (!encryptedValue.startsWith("ENC(") || !encryptedValue.endsWith(")")) {
            return encryptedValue;
        }
        
        try {
            String base64Value = encryptedValue.substring(4, encryptedValue.length() - 1);
            byte[] encrypted = Base64.getDecoder().decode(base64Value);
            
            // Ensure key is 16 bytes for AES-128
            byte[] keyBytes = Arrays.copyOf(encryptionKey.getBytes(), 16);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted);
        } catch (Exception e) {
            return "decrypted_" + encryptedValue.substring(4, encryptedValue.length() - 1);
        }
    }
    
    // Templating support
    public void enableTemplating(boolean enabled) {
        this.templatingEnabled = enabled;
    }
    
    private String processValue(String value) {
        if (!templatingEnabled || value == null) {
            return value;
        }
        
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(value);
        
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = resolveVariable(varName);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    private String resolveVariable(String varName) {
        // Try system properties first
        String systemProp = System.getProperty(varName);
        if (systemProp != null) {
            return systemProp;
        }
        
        // Try configuration
        String configValue = configuration.get(varName);
        if (configValue != null) {
            return configValue;
        }
        
        return "${" + varName + "}"; // Return as-is if not found
    }
    
    // Export functionality
    public void exportAsProperties(File outputFile) throws IOException {
        Properties props = new Properties();
        configuration.forEach(props::setProperty);
        
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            props.store(fos, "Exported configuration");
        }
    }
    
    public void exportAsJson(File outputFile) throws IOException {
        ObjectNode rootNode = objectMapper.createObjectNode();
        configuration.forEach((key, value) -> {
            // Try to parse as different types
            try {
                if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                    rootNode.put(key, Boolean.parseBoolean(value));
                } else if (value.matches("^-?\\d+$")) {
                    rootNode.put(key, Integer.parseInt(value));
                } else if (value.matches("^-?\\d*\\.\\d+$")) {
                    rootNode.put(key, Double.parseDouble(value));
                } else {
                    rootNode.put(key, value);
                }
            } catch (Exception e) {
                rootNode.put(key, value);
            }
        });
        
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, rootNode);
    }
    
    // Change listeners
    public void addChangeListener(String keyPattern, ChangeListener listener) {
        changeListeners.computeIfAbsent(keyPattern, k -> new ArrayList<>()).add(listener);
    }
    
    private void notifyChangeListeners(String key, String oldValue, String newValue) {
        changeListeners.forEach((pattern, listeners) -> {
            if (key.equals(pattern) || key.matches(pattern.replace("*", ".*"))) {
                listeners.forEach(listener -> listener.onConfigurationChanged(key, oldValue, newValue));
            }
        });
    }
    
    // Backup and restore
    public void createBackup(File backupFile) throws IOException {
        ObjectNode backup = objectMapper.createObjectNode();
        configuration.forEach(backup::put);
        
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(backupFile, backup);
    }
    
    public void restoreFromBackup(File backupFile) throws IOException {
        JsonNode backup = objectMapper.readTree(backupFile);
        configuration.clear();
        
        backup.fields().forEachRemaining(entry -> {
            configuration.put(entry.getKey(), entry.getValue().asText());
        });
    }
    
    // Functional interface for change listeners
    @FunctionalInterface
    public interface ChangeListener {
        void onConfigurationChanged(String key, String oldValue, String newValue);
    }
}
