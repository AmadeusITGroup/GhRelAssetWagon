package io.github.amadeusitgroup.maven.wagon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * MetricsCollector provides comprehensive metrics collection and monitoring capabilities.
 * Supports counters, gauges, timers, histograms, health checks, and alerting.
 * Thread-safe singleton implementation.
 */
public class MetricsCollector {
    
    private static volatile MetricsCollector instance;
    private static final Object lock = new Object();
    
    // Metrics storage
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Double> gauges = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> timers = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> histograms = new ConcurrentHashMap<>();
    private final Map<String, List<OperationRecord>> operations = new ConcurrentHashMap<>();
    private final Map<String, List<TimestampedValue>> timestampedCounters = new ConcurrentHashMap<>();
    
    // Health checks and alerts
    private final Map<String, Supplier<HealthStatus>> healthChecks = new ConcurrentHashMap<>();
    private final Map<String, AlertDefinition> alertDefinitions = new ConcurrentHashMap<>();
    private final Map<String, Alert> triggeredAlerts = new ConcurrentHashMap<>();
    private final Set<String> acknowledgedAlerts = ConcurrentHashMap.newKeySet();
    
    // Configuration
    private Duration retentionPolicy = Duration.ofHours(24);
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private MetricsCollector() {}
    
    public static MetricsCollector getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new MetricsCollector();
                }
            }
        }
        return instance;
    }
    
    public static void resetInstance() {
        synchronized (lock) {
            instance = null;
        }
    }
    
    // Counter operations
    public void incrementCounter(String name) {
        incrementCounter(name, 1);
    }
    
    public void incrementCounter(String name, long value) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(value);
    }
    
    public long getCounterValue(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }
    
    // Gauge operations
    public void setGauge(String name, double value) {
        gauges.put(name, value);
    }
    
    public void incrementGauge(String name, double value) {
        gauges.merge(name, value, Double::sum);
    }
    
    public void decrementGauge(String name, double value) {
        gauges.merge(name, -value, Double::sum);
    }
    
    public double getGaugeValue(String name) {
        return gauges.getOrDefault(name, 0.0);
    }
    
    // Timer operations
    public Timer startTimer(String name) {
        return new Timer(name);
    }
    
    public long getTimerValue(String name) {
        List<Long> timerList = timers.get(name);
        return timerList != null && !timerList.isEmpty() ? timerList.get(timerList.size() - 1) : 0;
    }
    
    public int getTimerCount(String name) {
        List<Long> timerList = timers.get(name);
        return timerList != null ? timerList.size() : 0;
    }
    
    public long getAverageTimerValue(String name) {
        List<Long> timerList = timers.get(name);
        if (timerList == null || timerList.isEmpty()) {
            return 0;
        }
        return (long) timerList.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
    
    // Histogram operations
    public void recordHistogram(String name, double value) {
        rwLock.writeLock().lock();
        try {
            histograms.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    public HistogramStats getHistogramStats(String name) {
        List<Double> values = histograms.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new HistogramStats(values);
    }
    
    // Operation tracking
    public void recordOperation(String operationType, boolean success, long duration, long bytesProcessed) {
        rwLock.writeLock().lock();
        try {
            operations.computeIfAbsent(operationType, k -> Collections.synchronizedList(new ArrayList<>()))
                     .add(new OperationRecord(success, duration, bytesProcessed, Instant.now()));
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    public OperationStats getOperationStats(String operationType) {
        List<OperationRecord> records = operations.get(operationType);
        if (records == null || records.isEmpty()) {
            return null;
        }
        return new OperationStats(records);
    }
    
    // Health checks
    public void registerHealthCheck(String name, Supplier<HealthStatus> healthCheck) {
        healthChecks.put(name, healthCheck);
    }
    
    public Map<String, HealthStatus> runHealthChecks() {
        Map<String, HealthStatus> results = new HashMap<>();
        for (Map.Entry<String, Supplier<HealthStatus>> entry : healthChecks.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().get());
            } catch (Exception e) {
                results.put(entry.getKey(), new HealthStatus(false, "Health check failed: " + e.getMessage()));
            }
        }
        return results;
    }
    
    public boolean isSystemHealthy() {
        return runHealthChecks().values().stream().allMatch(HealthStatus::isHealthy);
    }
    
    // Metrics export
    public String exportMetricsAsJson() {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            
            ObjectNode countersNode = objectMapper.createObjectNode();
            counters.forEach((k, v) -> countersNode.put(k, v.get()));
            root.set("counters", countersNode);
            
            ObjectNode gaugesNode = objectMapper.createObjectNode();
            gauges.forEach(gaugesNode::put);
            root.set("gauges", gaugesNode);
            
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }
    
    public String exportMetricsAsPrometheus() {
        StringBuilder sb = new StringBuilder();
        
        counters.forEach((name, value) -> {
            String promName = name.replace('.', '_');
            sb.append("# TYPE ").append(promName).append(" counter\n");
            sb.append(promName).append(" ").append(value.get()).append("\n");
        });
        
        gauges.forEach((name, value) -> {
            String promName = name.replace('.', '_');
            sb.append("# TYPE ").append(promName).append(" gauge\n");
            sb.append(promName).append(" ").append(value).append("\n");
        });
        
        return sb.toString();
    }
    
    public String exportMetricsAsCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("metric_name,metric_type,value\n");
        
        counters.forEach((name, value) -> 
            sb.append(name).append(",counter,").append(value.get()).append("\n"));
        
        gauges.forEach((name, value) -> 
            sb.append(name).append(",gauge,").append(value).append("\n"));
        
        return sb.toString();
    }
    
    // Metrics filtering and querying
    public Map<String, Object> getMetricsByPrefix(String prefix) {
        Map<String, Object> result = new HashMap<>();
        
        counters.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .forEach(e -> result.put(e.getKey(), e.getValue().get()));
        
        gauges.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        
        return result;
    }
    
    public Map<String, Long> getAllCounters() {
        return counters.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
    
    public Map<String, Double> getAllGauges() {
        return new HashMap<>(gauges);
    }
    
    public List<String> findMetrics(String pattern) {
        String regex = pattern.replace("*", ".*");
        List<String> result = new ArrayList<>();
        
        counters.keySet().stream()
                .filter(name -> name.matches(regex))
                .forEach(result::add);
        
        gauges.keySet().stream()
                .filter(name -> name.matches(regex))
                .forEach(result::add);
        
        return result;
    }
    
    // Timestamped metrics
    public void recordTimestampedCounter(String name, long value, Instant timestamp) {
        rwLock.writeLock().lock();
        try {
            timestampedCounters.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>()))
                              .add(new TimestampedValue(value, timestamp));
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    public long getTimestampedCounterValue(String name, Instant timestamp) {
        List<TimestampedValue> values = timestampedCounters.get(name);
        if (values == null) return 0;
        
        return values.stream()
                .filter(v -> v.timestamp.equals(timestamp))
                .mapToLong(v -> v.value)
                .findFirst()
                .orElse(0);
    }
    
    // Metrics aggregation
    public AggregatedMetrics aggregateMetrics(String name, Duration timeWindow) {
        return aggregateMetrics(name, timeWindow, Instant.now());
    }
    
    public AggregatedMetrics aggregateMetrics(String name, Duration timeWindow, Instant referenceTime) {
        List<TimestampedValue> values = timestampedCounters.get(name);
        if (values == null || values.isEmpty()) {
            return new AggregatedMetrics(0, 0, 0, 0, 0);
        }
        
        Instant cutoff = referenceTime.minus(timeWindow);
        List<Long> recentValues = values.stream()
                .filter(v -> !v.timestamp.isBefore(cutoff))
                .map(v -> v.value)
                .collect(Collectors.toList());
        
        if (recentValues.isEmpty()) {
            return new AggregatedMetrics(0, 0, 0, 0, 0);
        }
        
        long sum = recentValues.stream().mapToLong(Long::longValue).sum();
        double average = recentValues.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long min = recentValues.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = recentValues.stream().mapToLong(Long::longValue).max().orElse(0);
        
        return new AggregatedMetrics(sum, average, min, max, recentValues.size());
    }
    
    // Alerting
    public void addAlert(String name, Supplier<Boolean> condition, String message) {
        alertDefinitions.put(name, new AlertDefinition(condition, message));
    }
    
    public List<Alert> getTriggeredAlerts() {
        List<Alert> alerts = new ArrayList<>();
        
        for (Map.Entry<String, AlertDefinition> entry : alertDefinitions.entrySet()) {
            try {
                if (entry.getValue().condition.get()) {
                    Alert alert = new Alert(entry.getKey(), entry.getValue().message, Instant.now());
                    triggeredAlerts.put(entry.getKey(), alert);
                    alerts.add(alert);
                }
            } catch (Exception e) {
                // Ignore alert evaluation errors
            }
        }
        
        return alerts;
    }
    
    public List<Alert> getUnacknowledgedAlerts() {
        return getTriggeredAlerts().stream()
                .filter(alert -> !acknowledgedAlerts.contains(alert.getName()))
                .collect(Collectors.toList());
    }
    
    public void acknowledgeAlert(String alertName) {
        acknowledgedAlerts.add(alertName);
    }
    
    // Retention and cleanup
    public void setRetentionPolicy(Duration retention) {
        this.retentionPolicy = retention;
    }
    
    public void cleanupOldMetrics() {
        Instant cutoff = Instant.now().minus(retentionPolicy);
        
        timestampedCounters.values().forEach(values -> 
            values.removeIf(v -> v.timestamp.isBefore(cutoff)));
    }
    
    // Reset operations
    public void reset() {
        rwLock.writeLock().lock();
        try {
            counters.clear();
            gauges.clear();
            timers.clear();
            histograms.clear();
            operations.clear();
            timestampedCounters.clear();
            triggeredAlerts.clear();
            acknowledgedAlerts.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    public void resetMetricsByPrefix(String prefix) {
        rwLock.writeLock().lock();
        try {
            counters.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
            gauges.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
            timers.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
            histograms.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    // Inner classes and data structures
    public class Timer {
        private final String name;
        private final long startTime;
        
        public Timer(String name) {
            this.name = name;
            this.startTime = System.currentTimeMillis();
        }
        
        public void stop() {
            long duration = System.currentTimeMillis() - startTime;
            rwLock.writeLock().lock();
            try {
                timers.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>())).add(duration);
            } finally {
                rwLock.writeLock().unlock();
            }
        }
    }
    
    public static class HistogramStats {
        private final List<Double> values;
        
        public HistogramStats(List<Double> values) {
            this.values = new ArrayList<>(values);
            Collections.sort(this.values);
        }
        
        public int getCount() { return values.size(); }
        public double getMin() { return values.isEmpty() ? 0 : values.get(0); }
        public double getMax() { return values.isEmpty() ? 0 : values.get(values.size() - 1); }
        public double getMean() { return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0); }
        
        public double getPercentile(double percentile) {
            if (values.isEmpty()) return 0;
            int index = (int) Math.ceil(percentile / 100.0 * values.size()) - 1;
            return values.get(Math.max(0, Math.min(index, values.size() - 1)));
        }
    }
    
    public static class OperationStats {
        private final List<OperationRecord> records;
        
        public OperationStats(List<OperationRecord> records) {
            this.records = records;
        }
        
        public int getTotalOperations() { return records.size(); }
        public int getSuccessfulOperations() { return (int) records.stream().mapToInt(r -> r.success ? 1 : 0).sum(); }
        public int getFailedOperations() { return getTotalOperations() - getSuccessfulOperations(); }
        public double getSuccessRate() { return (double) getSuccessfulOperations() / getTotalOperations() * 100; }
        public double getAverageDuration() { return records.stream().mapToLong(r -> r.duration).average().orElse(0.0); }
        public long getTotalBytesProcessed() { return records.stream().mapToLong(r -> r.bytesProcessed).sum(); }
    }
    
    public static class HealthStatus {
        private final boolean healthy;
        private final String message;
        
        public HealthStatus(boolean healthy, String message) {
            this.healthy = healthy;
            this.message = message;
        }
        
        public boolean isHealthy() { return healthy; }
        public String getMessage() { return message; }
    }
    
    public static class Alert {
        private final String name;
        private final String message;
        private final Instant timestamp;
        
        public Alert(String name, String message, Instant timestamp) {
            this.name = name;
            this.message = message;
            this.timestamp = timestamp;
        }
        
        public String getName() { return name; }
        public String getMessage() { return message; }
        public Instant getTimestamp() { return timestamp; }
    }
    
    public static class AggregatedMetrics {
        private final long sum;
        private final double average;
        private final long min;
        private final long max;
        private final int count;
        
        public AggregatedMetrics(long sum, double average, long min, long max, int count) {
            this.sum = sum;
            this.average = average;
            this.min = min;
            this.max = max;
            this.count = count;
        }
        
        public long getSum() { return sum; }
        public double getAverage() { return average; }
        public long getMin() { return min; }
        public long getMax() { return max; }
        public int getCount() { return count; }
    }
    
    private static class OperationRecord {
        final boolean success;
        final long duration;
        final long bytesProcessed;
        final Instant timestamp;
        
        OperationRecord(boolean success, long duration, long bytesProcessed, Instant timestamp) {
            this.success = success;
            this.duration = duration;
            this.bytesProcessed = bytesProcessed;
            this.timestamp = timestamp;
        }
    }
    
    private static class TimestampedValue {
        final long value;
        final Instant timestamp;
        
        TimestampedValue(long value, Instant timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }
    
    private static class AlertDefinition {
        final Supplier<Boolean> condition;
        final String message;
        
        AlertDefinition(Supplier<Boolean> condition, String message) {
            this.condition = condition;
            this.message = message;
        }
    }
}
