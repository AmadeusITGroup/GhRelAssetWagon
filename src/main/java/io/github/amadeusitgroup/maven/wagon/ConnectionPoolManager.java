package io.github.amadeusitgroup.maven.wagon;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Connection pool manager for GitHub API connections to improve performance
 * and reduce connection overhead.
 */
public class ConnectionPoolManager {
    
    private static final int DEFAULT_MAX_CONNECTIONS = 10;
    private static final int DEFAULT_CONNECTION_TIMEOUT = 30000; // 30 seconds
    private static final int DEFAULT_READ_TIMEOUT = 60000; // 60 seconds
    
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<PooledConnection>> connectionPools;
    private final AtomicInteger totalConnections;
    private final int maxConnections;
    private final int connectionTimeout;
    private final int readTimeout;
    private final ReentrantLock poolLock;
    
    private static volatile ConnectionPoolManager instance;
    
    private ConnectionPoolManager() {
        this.connectionPools = new ConcurrentHashMap<>();
        this.totalConnections = new AtomicInteger(0);
        this.maxConnections = DEFAULT_MAX_CONNECTIONS;
        this.connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        this.readTimeout = DEFAULT_READ_TIMEOUT;
        this.poolLock = new ReentrantLock();
    }
    
    /**
     * Get singleton instance of connection pool manager
     */
    public static ConnectionPoolManager getInstance() {
        if (instance == null) {
            synchronized (ConnectionPoolManager.class) {
                if (instance == null) {
                    instance = new ConnectionPoolManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Get a pooled connection for the given host and port
     */
    public HttpURLConnection getConnection(String host, int port) throws IOException {
        String baseUrl = "https://" + host + (port != 443 ? ":" + port : "");
        return getConnection(baseUrl, null);
    }
    
    /**
     * Get a pooled connection for the given host
     */
    public HttpURLConnection getConnection(String baseUrl, String token) throws IOException {
        String poolKey = getPoolKey(baseUrl);
        
        // Try to get existing connection from pool
        ConcurrentLinkedQueue<PooledConnection> pool = connectionPools.get(poolKey);
        if (pool != null) {
            PooledConnection pooledConn = pool.poll();
            if (pooledConn != null && pooledConn.isValid()) {
                pooledConn.resetForReuse();
                return pooledConn.getConnection();
            }
        }
        
        // Create new connection if none available or pool doesn't exist
        return createNewConnection(baseUrl, token);
    }
    
    /**
     * Return a connection to the pool for reuse
     */
    public void returnConnection(String baseUrl, HttpURLConnection connection) {
        if (connection == null) {
            return;
        }
        
        String poolKey = getPoolKey(baseUrl);
        
        poolLock.lock();
        try {
            ConcurrentLinkedQueue<PooledConnection> pool = connectionPools.computeIfAbsent(
                poolKey, k -> new ConcurrentLinkedQueue<>()
            );
            
            // Only return to pool if we haven't exceeded max connections
            if (totalConnections.get() < maxConnections) {
                PooledConnection pooledConn = new PooledConnection(connection);
                pool.offer(pooledConn);
            } else {
                // Close connection if pool is full
                connection.disconnect();
                totalConnections.decrementAndGet();
            }
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Create a new HTTP connection with proper configuration
     */
    private HttpURLConnection createNewConnection(String baseUrl, String token) throws IOException {
        URL url = new URL(baseUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        // Configure connection
        connection.setConnectTimeout(connectionTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        connection.setRequestProperty("User-Agent", "GhRelAssetWagon/1.0");
        
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "token " + token);
        }
        
        totalConnections.incrementAndGet();
        return connection;
    }
    
    /**
     * Generate pool key from base URL
     */
    private String getPoolKey(String baseUrl) {
        try {
            URL url = new URL(baseUrl);
            return url.getHost() + ":" + url.getPort();
        } catch (Exception e) {
            return baseUrl;
        }
    }
    
    /**
     * Check if the pool is shut down
     */
    public boolean isShutdown() {
        return connectionPools.isEmpty() && totalConnections.get() == 0;
    }
    
    /**
     * Get statistics for monitoring
     */
    public Object getStatistics() {
        return "Connection pool active with " + totalConnections.get() + "/" + maxConnections + " connections";
    }
    
    /**
     * Shutdown the connection pool manager
     */
    public void shutdown() {
        poolLock.lock();
        try {
            // Close all pooled connections
            for (ConcurrentLinkedQueue<PooledConnection> pool : connectionPools.values()) {
                PooledConnection pooledConn;
                while ((pooledConn = pool.poll()) != null) {
                    pooledConn.getConnection().disconnect();
                }
            }
            connectionPools.clear();
            totalConnections.set(0);
        } finally {
            poolLock.unlock();
        }
    }
    
    /**
     * Get current pool statistics
     */
    public PoolStats getPoolStats() {
        int totalPooled = 0;
        int totalPools = connectionPools.size();
        
        for (ConcurrentLinkedQueue<PooledConnection> pool : connectionPools.values()) {
            totalPooled += pool.size();
        }
        
        return new PoolStats(totalConnections.get(), totalPooled, totalPools);
    }
    
    /**
     * Wrapper class for pooled connections
     */
    private static class PooledConnection {
        private final HttpURLConnection connection;
        private final long createdTime;
        private static final long MAX_IDLE_TIME = 300000; // 5 minutes
        
        public PooledConnection(HttpURLConnection connection) {
            this.connection = connection;
            this.createdTime = System.currentTimeMillis();
        }
        
        public boolean isValid() {
            return (System.currentTimeMillis() - createdTime) < MAX_IDLE_TIME;
        }
        
        public HttpURLConnection getConnection() {
            return connection;
        }
        
        public void resetForReuse() {
            // Reset connection state for reuse
            connection.setDoOutput(false);
            connection.setDoInput(true);
        }
        
        public void close() {
            connection.disconnect();
        }
    }
    
    /**
     * Pool statistics class
     */
    public static class PoolStats {
        private final int totalConnections;
        private final int pooledConnections;
        private final int activePools;
        
        public PoolStats(int totalConnections, int pooledConnections, int activePools) {
            this.totalConnections = totalConnections;
            this.pooledConnections = pooledConnections;
            this.activePools = activePools;
        }
        
        public int getTotalConnections() { return totalConnections; }
        public int getPooledConnections() { return pooledConnections; }
        public int getActivePools() { return activePools; }
        
        @Override
        public String toString() {
            return String.format("PoolStats{total=%d, pooled=%d, pools=%d}", 
                totalConnections, pooledConnections, activePools);
        }
    }
}
