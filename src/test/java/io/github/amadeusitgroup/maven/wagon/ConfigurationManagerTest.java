package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Unit tests for ConfigurationManager - TDD approach.
 * Tests define the expected API and behavior for external configuration management.
 */
public class ConfigurationManagerTest {

    private ConfigurationManager configManager;
    private File testConfigDir;

    @BeforeEach
    public void setUp() throws IOException {
        ConfigurationManager.resetInstance();
        configManager = ConfigurationManager.getInstance();
        testConfigDir = Files.createTempDirectory("config-test").toFile();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (testConfigDir != null && testConfigDir.exists()) {
            deleteDirectory(testConfigDir);
        }
    }

    @Test
    public void testSingletonInstance() {
        ConfigurationManager instance1 = ConfigurationManager.getInstance();
        ConfigurationManager instance2 = ConfigurationManager.getInstance();
        assertSame(instance1, instance2, "ConfigurationManager should be a singleton");
    }

    @Test
    public void testDefaultConfiguration() {
        // Test default configuration values
        assertEquals(10, configManager.getInt("connection.pool.maxConnections", 5), 
            "Should return default max connections");
        assertEquals(30000, configManager.getInt("connection.timeout", 15000), 
            "Should return default connection timeout");
        assertEquals(5, configManager.getInt("retry.maxAttempts", 3), 
            "Should return default max retry attempts");
        assertEquals(60000, configManager.getLong("circuitBreaker.timeout", 30000), 
            "Should return default circuit breaker timeout");
        assertTrue(configManager.getBoolean("async.enabled", false), 
            "Should return default async enabled");
        assertEquals("gzip", configManager.getString("compression.algorithm", "none"), 
            "Should return default compression algorithm");
    }

    @Test
    public void testPropertiesFileConfiguration() throws IOException {
        // Test loading configuration from properties file
        File configFile = new File(testConfigDir, "ghrelasset.properties");
        Properties props = new Properties();
        props.setProperty("connection.pool.maxConnections", "20");
        props.setProperty("connection.timeout", "45000");
        props.setProperty("retry.maxAttempts", "7");
        props.setProperty("async.enabled", "false");
        props.setProperty("compression.algorithm", "lz4");
        props.setProperty("github.api.baseUrl", "https://api.github.com");
        
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
            props.store(fos, "Test configuration");
        }
        
        configManager.loadConfiguration(configFile);
        
        assertEquals(20, configManager.getInt("connection.pool.maxConnections"), 
            "Should load max connections from file");
        assertEquals(45000, configManager.getInt("connection.timeout"), 
            "Should load connection timeout from file");
        assertEquals(7, configManager.getInt("retry.maxAttempts"), 
            "Should load max retry attempts from file");
        assertFalse(configManager.getBoolean("async.enabled"), 
            "Should load async enabled from file");
        assertEquals("lz4", configManager.getString("compression.algorithm"), 
            "Should load compression algorithm from file");
        assertEquals("https://api.github.com", configManager.getString("github.api.baseUrl"), 
            "Should load GitHub API base URL from file");
    }

    @Test
    public void testJsonConfiguration() throws IOException {
        // Test loading configuration from JSON file
        File jsonConfigFile = new File(testConfigDir, "ghrelasset.json");
        String jsonConfig = "{\n" +
            "  \"connection\": {\n" +
            "    \"pool\": {\n" +
            "      \"maxConnections\": 25,\n" +
            "      \"connectionTimeout\": 50000\n" +
            "    }\n" +
            "  },\n" +
            "  \"retry\": {\n" +
            "    \"maxAttempts\": 8,\n" +
            "    \"backoffMultiplier\": 2.5\n" +
            "  },\n" +
            "  \"compression\": {\n" +
            "    \"enabled\": true,\n" +
            "    \"algorithm\": \"gzip\",\n" +
            "    \"level\": 6\n" +
            "  },\n" +
            "  \"github\": {\n" +
            "    \"api\": {\n" +
            "      \"baseUrl\": \"https://api.github.com\",\n" +
            "      \"rateLimit\": {\n" +
            "        \"enabled\": true,\n" +
            "        \"requestsPerHour\": 5000\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        
        Files.write(jsonConfigFile.toPath(), jsonConfig.getBytes());
        
        configManager.loadJsonConfiguration(jsonConfigFile);
        
        assertEquals(25, configManager.getInt("connection.pool.maxConnections"), 
            "Should load nested max connections from JSON");
        assertEquals(50000, configManager.getInt("connection.pool.connectionTimeout"), 
            "Should load nested connection timeout from JSON");
        assertEquals(8, configManager.getInt("retry.maxAttempts"), 
            "Should load retry attempts from JSON");
        assertEquals(2.5, configManager.getDouble("retry.backoffMultiplier"), 0.01, 
            "Should load backoff multiplier from JSON");
        assertTrue(configManager.getBoolean("compression.enabled"), 
            "Should load compression enabled from JSON");
        assertEquals(6, configManager.getInt("compression.level"), 
            "Should load compression level from JSON");
        assertEquals(5000, configManager.getInt("github.api.rateLimit.requestsPerHour"), 
            "Should load nested rate limit from JSON");
    }

    @Test
    public void testEnvironmentVariableOverrides() {
        // Test environment variable overrides
        configManager.setEnvironmentOverride("GHRELASSET_CONNECTION_POOL_MAXCONNECTIONS", "30");
        configManager.setEnvironmentOverride("GHRELASSET_RETRY_MAXATTEMPTS", "10");
        configManager.setEnvironmentOverride("GHRELASSET_ASYNC_ENABLED", "true");
        
        assertEquals(30, configManager.getInt("connection.pool.maxconnections"), 
            "Environment variable should override default");
        assertEquals(10, configManager.getInt("retry.maxattempts"), 
            "Environment variable should override default");
        assertTrue(configManager.getBoolean("async.enabled"), 
            "Environment variable should override default");
        
        // Test environment variable naming convention
        assertEquals("GHRELASSET_CONNECTION_TIMEOUT", 
            configManager.getEnvironmentVariableName("connection.timeout"), 
            "Should convert property name to environment variable format");
    }

    @Test
    public void testSystemPropertyOverrides() {
        // Test system property overrides
        System.setProperty("ghrelasset.connection.pool.maxConnections", "35");
        System.setProperty("ghrelasset.compression.algorithm", "deflate");
        
        configManager.refreshConfiguration();
        
        assertEquals(35, configManager.getInt("connection.pool.maxConnections"), 
            "System property should override configuration");
        assertEquals("deflate", configManager.getString("compression.algorithm"), 
            "System property should override configuration");
        
        // Clean up system properties
        System.clearProperty("ghrelasset.connection.pool.maxConnections");
        System.clearProperty("ghrelasset.compression.algorithm");
    }

    @Test
    public void testConfigurationPrecedence() throws IOException {
        // Test configuration precedence: System Properties > Environment > Config File > Defaults
        
        // Set up config file
        File configFile = new File(testConfigDir, "test.properties");
        Properties props = new Properties();
        props.setProperty("test.value", "file");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
            props.store(fos, "Test");
        }
        configManager.loadConfiguration(configFile);
        
        assertEquals("file", configManager.getString("test.value"), 
            "Should use config file value");
        
        // Override with environment variable
        configManager.setEnvironmentOverride("GHRELASSET_TEST_VALUE", "environment");
        assertEquals("environment", configManager.getString("test.value"), 
            "Environment should override config file");
        
        // Override with system property
        System.setProperty("ghrelasset.test.value", "system");
        configManager.refreshConfiguration();
        assertEquals("system", configManager.getString("test.value"), 
            "System property should override environment");
        
        // Clean up
        System.clearProperty("ghrelasset.test.value");
    }

    @Test
    public void testConfigurationValidation() throws IOException {
        // Test configuration validation
        File invalidConfigFile = new File(testConfigDir, "invalid.properties");
        Properties props = new Properties();
        props.setProperty("connection.pool.maxConnections", "invalid");
        props.setProperty("retry.maxAttempts", "-5");
        props.setProperty("connection.timeout", "0");
        
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(invalidConfigFile)) {
            props.store(fos, "Invalid configuration");
        }
        
        List<String> validationErrors = configManager.validateConfiguration(invalidConfigFile);
        
        assertFalse(validationErrors.isEmpty(), "Should detect validation errors");
        assertTrue(validationErrors.stream().anyMatch(error -> 
            error.contains("maxConnections") && error.contains("invalid")), 
            "Should detect invalid maxConnections");
        assertTrue(validationErrors.stream().anyMatch(error -> 
            error.contains("maxAttempts") && error.contains("negative")), 
            "Should detect negative maxAttempts");
        assertTrue(validationErrors.stream().anyMatch(error -> 
            error.contains("timeout") && error.contains("zero")), 
            "Should detect zero timeout");
    }

    @Test
    public void testConfigurationProfiles() throws IOException {
        // Test configuration profiles (development, production, test)
        File devConfigFile = new File(testConfigDir, "ghrelasset-dev.properties");
        Properties devProps = new Properties();
        devProps.setProperty("github.api.baseUrl", "https://api.github.dev");
        devProps.setProperty("connection.pool.maxConnections", "5");
        devProps.setProperty("logging.level", "DEBUG");
        
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(devConfigFile)) {
            devProps.store(fos, "Development configuration");
        }
        
        File prodConfigFile = new File(testConfigDir, "ghrelasset-prod.properties");
        Properties prodProps = new Properties();
        prodProps.setProperty("github.api.baseUrl", "https://api.github.com");
        prodProps.setProperty("connection.pool.maxConnections", "50");
        prodProps.setProperty("logging.level", "INFO");
        
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(prodConfigFile)) {
            prodProps.store(fos, "Production configuration");
        }
        
        // Load development profile
        configManager.loadProfile("dev", testConfigDir);
        assertEquals("https://api.github.dev", configManager.getString("github.api.baseUrl"), 
            "Should load dev API URL");
        assertEquals(5, configManager.getInt("connection.pool.maxConnections"), 
            "Should load dev max connections");
        assertEquals("DEBUG", configManager.getString("logging.level"), 
            "Should load dev logging level");
        
        // Switch to production profile
        configManager.loadProfile("prod", testConfigDir);
        assertEquals("https://api.github.com", configManager.getString("github.api.baseUrl"), 
            "Should load prod API URL");
        assertEquals(50, configManager.getInt("connection.pool.maxConnections"), 
            "Should load prod max connections");
        assertEquals("INFO", configManager.getString("logging.level"), 
            "Should load prod logging level");
    }

    @Test
    public void testDynamicConfigurationReload() throws IOException, InterruptedException {
        // Test dynamic configuration reloading
        File configFile = new File(testConfigDir, "dynamic.properties");
        Properties props = new Properties();
        props.setProperty("dynamic.value", "initial");
        
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
            props.store(fos, "Dynamic configuration");
        }
        
        configManager.loadConfiguration(configFile);
        configManager.enableAutoReload(configFile, 100); // Check every 100ms
        
        assertEquals("initial", configManager.getString("dynamic.value"), 
            "Should load initial value");
        
        // Modify configuration file
        Thread.sleep(150); // Wait for initial check
        props.setProperty("dynamic.value", "updated");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile)) {
            props.store(fos, "Updated configuration");
        }
        
        // Wait for auto-reload
        Thread.sleep(200);
        
        assertEquals("updated", configManager.getString("dynamic.value"), 
            "Should auto-reload updated value");
        
        configManager.disableAutoReload();
    }

    @Test
    public void testConfigurationEncryption() throws IOException {
        // Test encrypted configuration values
        File encryptedConfigFile = new File(testConfigDir, "encrypted.properties");
        Properties props = new Properties();
        props.setProperty("github.token", "ENC(encrypted_token_value)");
        props.setProperty("database.password", "ENC(encrypted_password)");
        props.setProperty("plain.value", "not_encrypted");
        
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(encryptedConfigFile)) {
            props.store(fos, "Encrypted configuration");
        }
        
        // Set up encryption key
        configManager.setEncryptionKey("test-encryption-key");
        configManager.loadConfiguration(encryptedConfigFile);
        
        String token = configManager.getDecryptedString("github.token");
        assertNotNull(token, "Should decrypt token");
        assertNotEquals("ENC(encrypted_token_value)", token, "Should not return encrypted value");
        
        String password = configManager.getDecryptedString("database.password");
        assertNotNull(password, "Should decrypt password");
        assertNotEquals("ENC(encrypted_password)", password, "Should not return encrypted value");
        
        String plainValue = configManager.getString("plain.value");
        assertEquals("not_encrypted", plainValue, "Should return plain value as-is");
        
        // Test encryption of new values
        String encryptedValue = configManager.encryptValue("sensitive_data");
        assertTrue(encryptedValue.startsWith("ENC("), "Encrypted value should have ENC prefix");
        
        String decryptedValue = configManager.decryptValue(encryptedValue);
        assertEquals("sensitive_data", decryptedValue, "Should decrypt to original value");
    }

    @Test
    public void testConfigurationTemplating() throws IOException {
        // Test configuration templating with variable substitution
        File templateConfigFile = new File(testConfigDir, "template.properties");
        Properties props = new Properties();
        props.setProperty("app.name", "GhRelAssetWagon");
        props.setProperty("app.version", "1.0.0");
        props.setProperty("github.api.baseUrl", "https://api.github.com");
        props.setProperty("cache.dir", "${user.home}/.${app.name}/cache");
        props.setProperty("log.file", "${user.home}/.${app.name}/logs/${app.name}-${app.version}.log");
        props.setProperty("api.url", "${github.api.baseUrl}/repos");
        
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(templateConfigFile)) {
            props.store(fos, "Template configuration");
        }
        
        configManager.loadConfiguration(templateConfigFile);
        configManager.enableTemplating(true);
        
        String cacheDir = configManager.getString("cache.dir");
        assertTrue(cacheDir.contains("GhRelAssetWagon"), "Should substitute app.name");
        assertTrue(cacheDir.startsWith(System.getProperty("user.home")), "Should substitute user.home");
        
        String logFile = configManager.getString("log.file");
        assertTrue(logFile.contains("GhRelAssetWagon-1.0.0.log"), "Should substitute multiple variables");
        
        String apiUrl = configManager.getString("api.url");
        assertEquals("https://api.github.com/repos", apiUrl, "Should substitute nested variables");
    }

    @Test
    public void testConfigurationExport() throws IOException {
        // Test configuration export functionality
        configManager.setString("test.string", "value");
        configManager.setInt("test.int", 42);
        configManager.setBoolean("test.boolean", true);
        configManager.setDouble("test.double", 3.14);
        
        // Export as properties
        File exportedPropsFile = new File(testConfigDir, "exported.properties");
        configManager.exportAsProperties(exportedPropsFile);
        
        assertTrue(exportedPropsFile.exists(), "Exported properties file should exist");
        
        Properties loadedProps = new Properties();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(exportedPropsFile)) {
            loadedProps.load(fis);
        }
        
        assertEquals("value", loadedProps.getProperty("test.string"), "Should export string value");
        assertEquals("42", loadedProps.getProperty("test.int"), "Should export int value");
        assertEquals("true", loadedProps.getProperty("test.boolean"), "Should export boolean value");
        assertEquals("3.14", loadedProps.getProperty("test.double"), "Should export double value");
        
        // Export as JSON
        File exportedJsonFile = new File(testConfigDir, "exported.json");
        configManager.exportAsJson(exportedJsonFile);
        
        assertTrue(exportedJsonFile.exists(), "Exported JSON file should exist");
        
        String jsonContent = new String(Files.readAllBytes(exportedJsonFile.toPath()));
        assertTrue(jsonContent.contains("\"test.string\"") && jsonContent.contains("\"value\""), "Should export string in JSON");
        assertTrue(jsonContent.contains("\"test.int\"") && jsonContent.contains("42"), "Should export int in JSON");
        assertTrue(jsonContent.contains("\"test.boolean\"") && jsonContent.contains("true"), "Should export boolean in JSON");
    }

    @Test
    public void testConfigurationChangeListeners() {
        // Test configuration change listeners
        StringBuilder changeLog = new StringBuilder();
        
        configManager.addChangeListener("test.monitored", (key, oldValue, newValue) -> {
            changeLog.append(String.format("Changed %s from %s to %s; ", key, oldValue, newValue));
        });
        
        configManager.addChangeListener("test.*", (key, oldValue, newValue) -> {
            changeLog.append(String.format("Wildcard: %s changed; ", key));
        });
        
        configManager.setString("test.monitored", "initial");
        configManager.setString("test.monitored", "updated");
        configManager.setString("test.other", "value");
        
        String log = changeLog.toString();
        assertTrue(log.contains("Changed test.monitored from null to initial"), 
            "Should notify of initial value set");
        assertTrue(log.contains("Changed test.monitored from initial to updated"), 
            "Should notify of value change");
        assertTrue(log.contains("Wildcard: test.monitored changed"), 
            "Should notify wildcard listeners");
        assertTrue(log.contains("Wildcard: test.other changed"), 
            "Should notify wildcard listeners for other keys");
    }

    @Test
    public void testConfigurationBackup() throws IOException {
        // Test configuration backup and restore
        configManager.setString("backup.test1", "value1");
        configManager.setInt("backup.test2", 100);
        configManager.setBoolean("backup.test3", true);
        
        File backupFile = new File(testConfigDir, "config-backup.json");
        configManager.createBackup(backupFile);
        
        assertTrue(backupFile.exists(), "Backup file should be created");
        
        // Modify configuration
        configManager.setString("backup.test1", "modified");
        configManager.setInt("backup.test2", 200);
        
        assertEquals("modified", configManager.getString("backup.test1"), "Value should be modified");
        assertEquals(200, configManager.getInt("backup.test2"), "Value should be modified");
        
        // Restore from backup
        configManager.restoreFromBackup(backupFile);
        
        assertEquals("value1", configManager.getString("backup.test1"), "Should restore original value");
        assertEquals(100, configManager.getInt("backup.test2"), "Should restore original value");
        assertTrue(configManager.getBoolean("backup.test3"), "Should restore original value");
    }

    // Helper methods
    private void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}
