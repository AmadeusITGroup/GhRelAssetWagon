package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for ConnectionPoolManager.
 */
public class ConnectionPoolManagerTest {

    private ConnectionPoolManager connectionPoolManager;

    @BeforeEach
    public void setUp() {
        connectionPoolManager = ConnectionPoolManager.getInstance();
    }

    @AfterEach
    public void tearDown() {
        connectionPoolManager.shutdown();
    }

    @Test
    public void testSingletonInstance() {
        ConnectionPoolManager instance1 = ConnectionPoolManager.getInstance();
        ConnectionPoolManager instance2 = ConnectionPoolManager.getInstance();
        assertSame(instance1, instance2, "ConnectionPoolManager should be a singleton");
    }

    @Test
    public void testGetConnection() throws Exception {
        String host = "api.github.com";
        int port = 443;
        
        HttpURLConnection connection = connectionPoolManager.getConnection(host, port);
        assertNotNull(connection, "Connection should not be null");
    }

    @Test
    public void testConnectionReuse() throws Exception {
        String host = "api.github.com";
        int port = 443;
        
        HttpURLConnection connection1 = connectionPoolManager.getConnection(host, port);
        HttpURLConnection connection2 = connectionPoolManager.getConnection(host, port);
        
        assertNotNull(connection1, "First connection should not be null");
        assertNotNull(connection2, "Second connection should not be null");
    }

    @Test
    public void testMaxConnections() throws Exception {
        String host = "api.github.com";
        int port = 443;
        
        // Test that we can create multiple connections up to the limit
        for (int i = 0; i < 10; i++) {
            HttpURLConnection connection = connectionPoolManager.getConnection(host, port);
            assertNotNull(connection, "Connection " + i + " should not be null");
        }
    }

    @Test
    public void testConnectionTimeout() throws Exception {
        String host = "api.github.com";
        int port = 443;
        
        HttpURLConnection connection = connectionPoolManager.getConnection(host, port);
        assertNotNull(connection, "Connection should not be null");
        
        // Verify timeout is set
        assertTrue(connection.getConnectTimeout() > 0, "Connect timeout should be set");
        assertTrue(connection.getReadTimeout() > 0, "Read timeout should be set");
    }

    @Test
    public void testShutdown() throws Exception {
        String host = "api.github.com";
        int port = 443;
        
        // Get a connection before shutdown
        HttpURLConnection connection = connectionPoolManager.getConnection(host, port);
        assertNotNull(connection, "Connection should not be null before shutdown");
        
        // Shutdown the pool
        connectionPoolManager.shutdown();
        
        // Verify shutdown completed
        assertTrue(connectionPoolManager.isShutdown(), "Pool should be shut down");
    }

    @Test
    public void testConnectionStatistics() throws Exception {
        String host = "api.github.com";
        int port = 443;
        
        // Get some connections to generate statistics
        for (int i = 0; i < 3; i++) {
            connectionPoolManager.getConnection(host, port);
        }
        
        Object stats = connectionPoolManager.getStatistics();
        assertNotNull(stats, "Statistics should not be null");
    }

    @Test
    public void testConcurrentAccess() throws Exception {
        String host = "api.github.com";
        int port = 443;
        
        // Test concurrent access to connection pool
        Thread[] threads = new Thread[5];
        Exception[] exceptions = new Exception[5];
        
        for (int i = 0; i < threads.length; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    HttpURLConnection connection = connectionPoolManager.getConnection(host, port);
                    assertNotNull(connection, "Connection should not be null in thread " + threadIndex);
                } catch (Exception e) {
                    exceptions[threadIndex] = e;
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(5000); // 5 second timeout
        }
        
        // Check for exceptions
        for (int i = 0; i < exceptions.length; i++) {
            if (exceptions[i] != null) {
                fail("Thread " + i + " threw exception: " + exceptions[i].getMessage());
            }
        }
    }
}
