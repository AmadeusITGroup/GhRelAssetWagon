package io.github.amadeusitgroup.maven.wagon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for MetricsCollector - TDD approach.
 * Tests define the expected API and behavior for metrics collection and monitoring.
 */
public class MetricsCollectorTest {

    private MetricsCollector metricsCollector;

    @BeforeEach
    public void setUp() {
        MetricsCollector.resetInstance();
        metricsCollector = MetricsCollector.getInstance();
    }

    @AfterEach
    public void tearDown() {
        if (metricsCollector != null) {
            metricsCollector.reset();
        }
    }

    @Test
    public void testSingletonInstance() {
        MetricsCollector instance1 = MetricsCollector.getInstance();
        MetricsCollector instance2 = MetricsCollector.getInstance();
        assertSame(instance1, instance2, "MetricsCollector should be a singleton");
    }

    @Test
    public void testCounterMetrics() {
        // Test basic counter operations
        metricsCollector.incrementCounter("upload.requests");
        metricsCollector.incrementCounter("upload.requests");
        metricsCollector.incrementCounter("download.requests");
        
        assertEquals(2, metricsCollector.getCounterValue("upload.requests"), 
            "Upload requests counter should be 2");
        assertEquals(1, metricsCollector.getCounterValue("download.requests"), 
            "Download requests counter should be 1");
        assertEquals(0, metricsCollector.getCounterValue("nonexistent.counter"), 
            "Non-existent counter should return 0");
        
        // Test counter increment by value
        metricsCollector.incrementCounter("bytes.uploaded", 1024);
        metricsCollector.incrementCounter("bytes.uploaded", 2048);
        
        assertEquals(3072, metricsCollector.getCounterValue("bytes.uploaded"), 
            "Bytes uploaded should be sum of increments");
    }

    @Test
    public void testGaugeMetrics() {
        // Test gauge operations
        metricsCollector.setGauge("active.connections", 5);
        metricsCollector.setGauge("memory.usage", 75.5);
        
        assertEquals(5, metricsCollector.getGaugeValue("active.connections"), 
            "Active connections gauge should be 5");
        assertEquals(75.5, metricsCollector.getGaugeValue("memory.usage"), 0.01, 
            "Memory usage gauge should be 75.5");
        
        // Test gauge updates
        metricsCollector.setGauge("active.connections", 8);
        assertEquals(8, metricsCollector.getGaugeValue("active.connections"), 
            "Gauge should update to new value");
        
        // Test gauge increment/decrement
        metricsCollector.incrementGauge("active.connections", 2);
        assertEquals(10, metricsCollector.getGaugeValue("active.connections"), 
            "Gauge should increment correctly");
        
        metricsCollector.decrementGauge("active.connections", 3);
        assertEquals(7, metricsCollector.getGaugeValue("active.connections"), 
            "Gauge should decrement correctly");
    }

    @Test
    public void testTimerMetrics() throws InterruptedException {
        // Test timer operations
        MetricsCollector.Timer timer = metricsCollector.startTimer("upload.duration");
        Thread.sleep(100); // Simulate some work
        timer.stop();
        
        long duration = metricsCollector.getTimerValue("upload.duration");
        assertTrue(duration >= 100, "Timer should record at least 100ms");
        assertTrue(duration < 200, "Timer should be less than 200ms for this test");
        
        // Test multiple timer recordings
        MetricsCollector.Timer timer2 = metricsCollector.startTimer("upload.duration");
        Thread.sleep(50);
        timer2.stop();
        
        // Should track multiple recordings
        assertEquals(2, metricsCollector.getTimerCount("upload.duration"), 
            "Should track 2 timer recordings");
        
        long averageDuration = metricsCollector.getAverageTimerValue("upload.duration");
        assertTrue(averageDuration >= 50, "Average should be at least 50ms");
        assertTrue(averageDuration <= 150, "Average should be reasonable");
    }

    @Test
    public void testHistogramMetrics() {
        // Test histogram for tracking distributions
        metricsCollector.recordHistogram("file.sizes", 1024);
        metricsCollector.recordHistogram("file.sizes", 2048);
        metricsCollector.recordHistogram("file.sizes", 512);
        metricsCollector.recordHistogram("file.sizes", 4096);
        metricsCollector.recordHistogram("file.sizes", 1536);
        
        MetricsCollector.HistogramStats stats = metricsCollector.getHistogramStats("file.sizes");
        
        assertNotNull(stats, "Histogram stats should not be null");
        assertEquals(5, stats.getCount(), "Should have 5 recorded values");
        assertEquals(512, stats.getMin(), "Min should be 512");
        assertEquals(4096, stats.getMax(), "Max should be 4096");
        assertEquals(1843.2, stats.getMean(), 0.1, "Mean should be approximately 1843.2");
        
        // Test percentiles
        assertTrue(stats.getPercentile(50) >= 1024, "50th percentile should be at least 1024");
        assertTrue(stats.getPercentile(95) >= 2048, "95th percentile should be at least 2048");
    }

    @Test
    public void testOperationMetrics() {
        // Test operation-specific metrics tracking
        metricsCollector.recordOperation("upload", true, 1500, 2048);
        metricsCollector.recordOperation("upload", true, 2000, 4096);
        metricsCollector.recordOperation("upload", false, 500, 1024);
        metricsCollector.recordOperation("download", true, 800, 1536);
        
        MetricsCollector.OperationStats uploadStats = metricsCollector.getOperationStats("upload");
        
        assertNotNull(uploadStats, "Upload stats should not be null");
        assertEquals(3, uploadStats.getTotalOperations(), "Should have 3 upload operations");
        assertEquals(2, uploadStats.getSuccessfulOperations(), "Should have 2 successful uploads");
        assertEquals(1, uploadStats.getFailedOperations(), "Should have 1 failed upload");
        assertEquals(66.67, uploadStats.getSuccessRate(), 0.01, "Success rate should be ~66.67%");
        assertEquals(1333.33, uploadStats.getAverageDuration(), 0.01, "Average duration should be ~1333.33ms");
        assertEquals(7168, uploadStats.getTotalBytesProcessed(), "Total bytes should be 7168");
        
        MetricsCollector.OperationStats downloadStats = metricsCollector.getOperationStats("download");
        assertEquals(1, downloadStats.getTotalOperations(), "Should have 1 download operation");
        assertEquals(100.0, downloadStats.getSuccessRate(), 0.01, "Download success rate should be 100%");
    }

    @Test
    public void testHealthChecks() {
        // Test health check registration and execution
        metricsCollector.registerHealthCheck("github.api", () -> {
            // Simulate API health check
            return new MetricsCollector.HealthStatus(true, "GitHub API is accessible");
        });
        
        metricsCollector.registerHealthCheck("disk.space", () -> {
            // Simulate disk space check
            return new MetricsCollector.HealthStatus(false, "Disk space low: 95% used");
        });
        
        Map<String, MetricsCollector.HealthStatus> healthResults = metricsCollector.runHealthChecks();
        
        assertEquals(2, healthResults.size(), "Should have 2 health check results");
        
        MetricsCollector.HealthStatus apiHealth = healthResults.get("github.api");
        assertTrue(apiHealth.isHealthy(), "GitHub API should be healthy");
        assertEquals("GitHub API is accessible", apiHealth.getMessage(), "Should have correct message");
        
        MetricsCollector.HealthStatus diskHealth = healthResults.get("disk.space");
        assertFalse(diskHealth.isHealthy(), "Disk space should be unhealthy");
        assertTrue(diskHealth.getMessage().contains("Disk space low"), "Should contain disk space message");
        
        // Test overall health status
        assertFalse(metricsCollector.isSystemHealthy(), "System should be unhealthy due to disk space");
    }

    @Test
    public void testMetricsExport() {
        // Test metrics export in different formats
        metricsCollector.incrementCounter("test.counter", 42);
        metricsCollector.setGauge("test.gauge", 3.14);
        metricsCollector.recordHistogram("test.histogram", 100);
        
        // Test JSON export
        String jsonMetrics = metricsCollector.exportMetricsAsJson();
        assertNotNull(jsonMetrics, "JSON export should not be null");
        assertTrue(jsonMetrics.contains("test.counter"), "JSON should contain counter");
        assertTrue(jsonMetrics.contains("42"), "JSON should contain counter value");
        assertTrue(jsonMetrics.contains("test.gauge"), "JSON should contain gauge");
        assertTrue(jsonMetrics.contains("3.14"), "JSON should contain gauge value");
        
        // Test Prometheus format export
        String prometheusMetrics = metricsCollector.exportMetricsAsPrometheus();
        assertNotNull(prometheusMetrics, "Prometheus export should not be null");
        assertTrue(prometheusMetrics.contains("test_counter"), "Prometheus should contain counter");
        assertTrue(prometheusMetrics.contains("test_gauge"), "Prometheus should contain gauge");
        
        // Test CSV export
        String csvMetrics = metricsCollector.exportMetricsAsCsv();
        assertNotNull(csvMetrics, "CSV export should not be null");
        assertTrue(csvMetrics.contains("metric_name"), "CSV should have header");
        assertTrue(csvMetrics.contains("test.counter"), "CSV should contain metrics");
    }

    @Test
    public void testMetricsFiltering() {
        // Test metrics filtering and querying
        metricsCollector.incrementCounter("upload.success");
        metricsCollector.incrementCounter("upload.failure");
        metricsCollector.incrementCounter("download.success");
        metricsCollector.setGauge("memory.heap", 512);
        metricsCollector.setGauge("memory.nonheap", 128);
        
        // Test filtering by prefix
        Map<String, Object> uploadMetrics = metricsCollector.getMetricsByPrefix("upload");
        assertEquals(2, uploadMetrics.size(), "Should have 2 upload metrics");
        assertTrue(uploadMetrics.containsKey("upload.success"), "Should contain upload.success");
        assertTrue(uploadMetrics.containsKey("upload.failure"), "Should contain upload.failure");
        
        Map<String, Object> memoryMetrics = metricsCollector.getMetricsByPrefix("memory");
        assertEquals(2, memoryMetrics.size(), "Should have 2 memory metrics");
        
        // Test filtering by type
        Map<String, Long> counters = metricsCollector.getAllCounters();
        assertEquals(3, counters.size(), "Should have 3 counters");
        
        Map<String, Double> gauges = metricsCollector.getAllGauges();
        assertEquals(2, gauges.size(), "Should have 2 gauges");
        
        // Test metric search
        List<String> matchingMetrics = metricsCollector.findMetrics("*.success");
        assertEquals(2, matchingMetrics.size(), "Should find 2 success metrics");
        assertTrue(matchingMetrics.contains("upload.success"), "Should find upload.success");
        assertTrue(matchingMetrics.contains("download.success"), "Should find download.success");
    }

    @Test
    public void testMetricsAggregation() {
        // Test metrics aggregation over time windows
        Instant now = Instant.now();
        
        // Record metrics at different times
        metricsCollector.recordTimestampedCounter("requests", 10, now.minusSeconds(300)); // 5 min ago
        metricsCollector.recordTimestampedCounter("requests", 15, now.minusSeconds(240)); // 4 min ago
        metricsCollector.recordTimestampedCounter("requests", 20, now.minusSeconds(180)); // 3 min ago
        metricsCollector.recordTimestampedCounter("requests", 25, now.minusSeconds(120)); // 2 min ago
        metricsCollector.recordTimestampedCounter("requests", 30, now.minusSeconds(60));  // 1 min ago
        
        // Test aggregation over last 5 minutes (use the same 'now' reference)
        MetricsCollector.AggregatedMetrics last5Min = metricsCollector.aggregateMetrics(
            "requests", Duration.ofMinutes(5), now);
        
        assertEquals(100, last5Min.getSum(), "Sum over 5 minutes should be 100");
        assertEquals(20.0, last5Min.getAverage(), 0.01, "Average should be 20");
        assertEquals(10, last5Min.getMin(), "Min should be 10");
        assertEquals(30, last5Min.getMax(), "Max should be 30");
        assertEquals(5, last5Min.getCount(), "Count should be 5");
        
        // Test aggregation over last 3 minutes
        MetricsCollector.AggregatedMetrics last3Min = metricsCollector.aggregateMetrics(
            "requests", Duration.ofMinutes(3), now);
        
        assertEquals(75, last3Min.getSum(), "Sum over 3 minutes should be 75");
        assertEquals(25.0, last3Min.getAverage(), 0.01, "Average should be 25");
        assertEquals(3, last3Min.getCount(), "Count should be 3");
    }

    @Test
    public void testMetricsAlerts() {
        // Test metrics alerting system
        metricsCollector.addAlert("high.error.rate", 
            () -> metricsCollector.getCounterValue("errors") > 10,
            "Error rate is too high");
        
        metricsCollector.addAlert("low.disk.space",
            () -> metricsCollector.getGaugeValue("disk.usage") > 90.0,
            "Disk space is running low");
        
        // Initially no alerts should be triggered
        List<MetricsCollector.Alert> triggeredAlerts = metricsCollector.getTriggeredAlerts();
        assertEquals(0, triggeredAlerts.size(), "No alerts should be triggered initially");
        
        // Trigger first alert
        metricsCollector.incrementCounter("errors", 15);
        triggeredAlerts = metricsCollector.getTriggeredAlerts();
        assertEquals(1, triggeredAlerts.size(), "One alert should be triggered");
        assertEquals("high.error.rate", triggeredAlerts.get(0).getName(), "Should be error rate alert");
        
        // Trigger second alert
        metricsCollector.setGauge("disk.usage", 95.0);
        triggeredAlerts = metricsCollector.getTriggeredAlerts();
        assertEquals(2, triggeredAlerts.size(), "Two alerts should be triggered");
        
        // Test alert acknowledgment
        metricsCollector.acknowledgeAlert("high.error.rate");
        List<MetricsCollector.Alert> unacknowledgedAlerts = metricsCollector.getUnacknowledgedAlerts();
        assertEquals(1, unacknowledgedAlerts.size(), "One alert should remain unacknowledged");
        assertEquals("low.disk.space", unacknowledgedAlerts.get(0).getName(), "Should be disk space alert");
    }

    @Test
    public void testMetricsRetention() {
        // Test metrics retention and cleanup
        Instant now = Instant.now();
        
        // Record old metrics
        metricsCollector.recordTimestampedCounter("old.metric", 100, now.minusSeconds(7200)); // 2 hours ago
        metricsCollector.recordTimestampedCounter("recent.metric", 200, now.minusSeconds(1800)); // 30 min ago
        
        // Set retention policy to 1 hour
        metricsCollector.setRetentionPolicy(Duration.ofHours(1));
        
        // Trigger cleanup
        metricsCollector.cleanupOldMetrics();
        
        // Old metric should be cleaned up
        assertEquals(0, metricsCollector.getTimestampedCounterValue("old.metric", now.minusSeconds(7200)), 
            "Old metric should be cleaned up");
        
        // Recent metric should still exist
        assertEquals(200, metricsCollector.getTimestampedCounterValue("recent.metric", now.minusSeconds(1800)), 
            "Recent metric should still exist");
    }

    @Test
    public void testConcurrentMetricsAccess() throws InterruptedException {
        // Test thread-safe metrics operations
        int threadCount = 10;
        int incrementsPerThread = 100;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    metricsCollector.incrementCounter("concurrent.counter");
                    metricsCollector.setGauge("thread.gauge", Thread.currentThread().getId());
                    metricsCollector.recordHistogram("thread.histogram", j);
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify results
        assertEquals(threadCount * incrementsPerThread, 
            metricsCollector.getCounterValue("concurrent.counter"), 
            "Counter should reflect all increments from all threads");
        
        MetricsCollector.HistogramStats histogramStats = metricsCollector.getHistogramStats("thread.histogram");
        assertEquals(threadCount * incrementsPerThread, histogramStats.getCount(), 
            "Histogram should have all recorded values");
    }

    @Test
    public void testMetricsReset() {
        // Test metrics reset functionality
        metricsCollector.incrementCounter("test.counter", 42);
        metricsCollector.setGauge("test.gauge", 3.14);
        metricsCollector.recordHistogram("test.histogram", 100);
        
        // Verify metrics exist
        assertEquals(42, metricsCollector.getCounterValue("test.counter"), "Counter should have value");
        assertEquals(3.14, metricsCollector.getGaugeValue("test.gauge"), 0.01, "Gauge should have value");
        assertNotNull(metricsCollector.getHistogramStats("test.histogram"), "Histogram should exist");
        
        // Reset all metrics
        metricsCollector.reset();
        
        // Verify metrics are cleared
        assertEquals(0, metricsCollector.getCounterValue("test.counter"), "Counter should be reset");
        assertEquals(0.0, metricsCollector.getGaugeValue("test.gauge"), 0.01, "Gauge should be reset");
        assertNull(metricsCollector.getHistogramStats("test.histogram"), "Histogram should be cleared");
        
        // Test selective reset
        metricsCollector.incrementCounter("keep.counter", 10);
        metricsCollector.incrementCounter("reset.counter", 20);
        
        metricsCollector.resetMetricsByPrefix("reset");
        
        assertEquals(10, metricsCollector.getCounterValue("keep.counter"), "Keep counter should remain");
        assertEquals(0, metricsCollector.getCounterValue("reset.counter"), "Reset counter should be cleared");
    }
}
