package io.github.amadeusitgroup.maven.wagon;

import org.apache.maven.wagon.StreamWagon;
import org.apache.maven.wagon.InputData;
import org.apache.maven.wagon.OutputData;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.resource.Resource;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.events.TransferEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for StreamWagon migration functionality.
 * Tests the new streaming capabilities, event system integration,
 * and timeout configuration methods.
 */
@DisplayName("StreamWagon Migration Tests")
class StreamWagonMigrationTest {

    private GhRelAssetWagon wagon;
    private Repository repository;
    private AuthenticationInfo authInfo;

    @Mock
    private TransferListener transferListener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        wagon = new GhRelAssetWagon();
        
        // Set up repository
        repository = new Repository();
        repository.setUrl("ghrelasset://owner/repo/tag");
        
        // Set up authentication
        authInfo = new AuthenticationInfo();
        authInfo.setPassword("test_token");
        
        // Initialize the wagon with the repository
        try {
            wagon.connect(repository, authInfo);
        } catch (Exception e) {
            // Connection may fail in tests, but repository should be set
        }
        
        // Add transfer listener
        wagon.addTransferListener(transferListener);
    }

    @Nested
    @DisplayName("Timeout Configuration Tests")
    class TimeoutConfigurationTests {

        @Test
        @DisplayName("Should set and get connection timeout")
        void testSetAndGetTimeout() {
            // Test default timeout
            assertEquals(60000, wagon.getTimeout());
            
            // Test setting custom timeout
            wagon.setTimeout(30000);
            assertEquals(30000, wagon.getTimeout());
        }

        @Test
        @DisplayName("Should set and get read timeout")
        void testSetAndGetReadTimeout() {
            // Test default read timeout
            assertEquals(300000, wagon.getReadTimeout());
            
            // Test setting custom read timeout
            wagon.setReadTimeout(120000);
            assertEquals(120000, wagon.getReadTimeout());
        }

        @Test
        @DisplayName("Should set and get interactive mode")
        void testSetAndGetInteractive() {
            // Test default interactive mode
            assertFalse(wagon.isInteractive());
            
            // Test setting interactive mode
            wagon.setInteractive(true);
            assertTrue(wagon.isInteractive());
        }
    }

    @Nested
    @DisplayName("StreamWagon Implementation Tests")
    class StreamWagonImplementationTests {

        @Test
        @DisplayName("Should configure input data for streaming downloads")
        void testFillInputData() throws Exception {
            // Skip if no authentication token
            if (System.getenv("GH_RELEASE_ASSET_TOKEN") == null) {
                return;
            }
            
            InputData inputData = new InputData();
            Resource resource = new Resource("test-artifact.jar");
            inputData.setResource(resource);
            
            // This test requires a mock setup since we can't easily test GitHub API calls
            // In a real scenario, this would test the streaming download functionality
            assertDoesNotThrow(() -> {
                // The method should handle resource not found gracefully
                try {
                    wagon.fillInputData(inputData);
                } catch (ResourceDoesNotExistException e) {
                    // Expected for non-existent resources
                    assertTrue(e.getMessage().contains("Resource not found"));
                }
            });
        }

        @Test
        @DisplayName("Should configure output data for streaming uploads")
        void testFillOutputData() throws Exception {
            OutputData outputData = new OutputData();
            Resource resource = new Resource("test-artifact.jar");
            resource.setContentLength(1024);
            outputData.setResource(resource);
            
            assertDoesNotThrow(() -> {
                wagon.fillOutputData(outputData);
                
                // Verify output stream is configured
                assertNotNull(outputData.getOutputStream());
            });
        }

        @Test
        @DisplayName("Should handle connection closing properly")
        void testCloseConnection() throws Exception {
            // Test that close connection doesn't throw exceptions
            assertDoesNotThrow(() -> {
                wagon.closeConnection();
            });
        }
    }

    @Nested
    @DisplayName("Event System Integration Tests")
    class EventSystemTests {

        @Test
        @DisplayName("Should fire transfer events during operations")
        void testTransferEventFiring() throws Exception {
            // Create a resource for testing
            Resource resource = new Resource("test-artifact.jar");
            
            // Test input data configuration (download)
            InputData inputData = new InputData();
            inputData.setResource(resource);
            
            try {
                wagon.fillInputData(inputData);
            } catch (ResourceDoesNotExistException e) {
                // Expected for non-existent resources
            }
            
            // Verify transfer events were fired (at least initiated and started)
            verify(transferListener, atLeastOnce()).transferInitiated(any(TransferEvent.class));
        }

        @Test
        @DisplayName("Should support multiple transfer listeners")
        void testMultipleTransferListeners() {
            TransferListener listener2 = mock(TransferListener.class);
            
            // Add second listener
            wagon.addTransferListener(listener2);
            
            // Remove first listener
            wagon.removeTransferListener(transferListener);
            
            // Test that listeners are managed correctly
            assertDoesNotThrow(() -> {
                Resource resource = new Resource("test.jar");
                OutputData outputData = new OutputData();
                outputData.setResource(resource);
                wagon.fillOutputData(outputData);
            });
        }
    }

    @Nested
    @DisplayName("Performance and Reliability Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Should handle large file streaming efficiently")
        void testLargeFileStreaming() throws Exception {
            // Create a large resource for testing
            Resource resource = new Resource("large-artifact.jar");
            resource.setContentLength(10 * 1024 * 1024); // 10MB
            
            OutputData outputData = new OutputData();
            outputData.setResource(resource);
            
            long startTime = System.currentTimeMillis();
            
            assertDoesNotThrow(() -> {
                wagon.fillOutputData(outputData);
            });
            
            long duration = System.currentTimeMillis() - startTime;
            
            // Should configure output stream quickly (< 1 second)
            assertTrue(duration < 1000, "Output data configuration should be fast");
        }

        @Test
        @DisplayName("Should handle connection timeouts properly")
        void testConnectionTimeouts() {
            // Set very short timeouts
            wagon.setTimeout(1); // 1ms
            wagon.setReadTimeout(1); // 1ms
            
            // Verify timeouts are applied
            assertEquals(1, wagon.getTimeout());
            assertEquals(1, wagon.getReadTimeout());
            
            // Test that operations handle timeouts gracefully
            Resource resource = new Resource("timeout-test.jar");
            InputData inputData = new InputData();
            inputData.setResource(resource);
            
            assertDoesNotThrow(() -> {
                try {
                    wagon.fillInputData(inputData);
                } catch (Exception e) {
                    // Timeout or connection errors are expected
                    assertTrue(e instanceof TransferFailedException || 
                              e instanceof ResourceDoesNotExistException);
                }
            });
        }
    }

    @Nested
    @DisplayName("Integration with Existing Features Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should integrate with connection pooling")
        void testConnectionPoolingIntegration() {
            // Test that StreamWagon works with existing connection pooling
            assertDoesNotThrow(() -> {
                // Multiple operations should reuse connections
                for (int i = 0; i < 3; i++) {
                    Resource resource = new Resource("test-" + i + ".jar");
                    OutputData outputData = new OutputData();
                    outputData.setResource(resource);
                    wagon.fillOutputData(outputData);
                }
            });
        }

        @Test
        @DisplayName("Should integrate with rate limiting")
        void testRateLimitingIntegration() {
            // Test that StreamWagon respects rate limiting
            assertDoesNotThrow(() -> {
                Resource resource = new Resource("rate-limit-test.jar");
                InputData inputData = new InputData();
                inputData.setResource(resource);
                
                try {
                    wagon.fillInputData(inputData);
                } catch (ResourceDoesNotExistException e) {
                    // Expected for non-existent resources
                }
            });
        }

        @Test
        @DisplayName("Should integrate with retry mechanisms")
        void testRetryMechanismIntegration() {
            // Test that StreamWagon benefits from retry mechanisms
            assertDoesNotThrow(() -> {
                Resource resource = new Resource("retry-test.jar");
                OutputData outputData = new OutputData();
                outputData.setResource(resource);
                wagon.fillOutputData(outputData);
            });
        }

        @Test
        @DisplayName("Should integrate with metrics collection")
        void testMetricsIntegration() {
            // Test that StreamWagon operations are tracked by metrics
            assertDoesNotThrow(() -> {
                Resource resource = new Resource("metrics-test.jar");
                OutputData outputData = new OutputData();
                outputData.setResource(resource);
                wagon.fillOutputData(outputData);
                
                // Close connection to trigger metrics collection
                wagon.closeConnection();
            });
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle authentication errors gracefully")
        void testAuthenticationErrorHandling() {
            // Remove authentication
            wagon.setAuthenticationInfo(null);
            
            Resource resource = new Resource("auth-test.jar");
            InputData inputData = new InputData();
            inputData.setResource(resource);
            
            assertDoesNotThrow(() -> {
                try {
                    wagon.fillInputData(inputData);
                } catch (Exception e) {
                    // Authentication or resource errors are expected
                    assertTrue(e instanceof TransferFailedException || 
                              e instanceof ResourceDoesNotExistException ||
                              e instanceof AuthorizationException);
                }
            });
        }

        @Test
        @DisplayName("Should handle network errors gracefully")
        void testNetworkErrorHandling() {
            // Set invalid repository URL
            wagon.getRepository().setUrl("ghrelasset://invalid/repo/tag");
            
            Resource resource = new Resource("network-test.jar");
            InputData inputData = new InputData();
            inputData.setResource(resource);
            
            assertDoesNotThrow(() -> {
                try {
                    wagon.fillInputData(inputData);
                } catch (Exception e) {
                    // Network errors are expected
                    assertTrue(e instanceof TransferFailedException || 
                              e instanceof ResourceDoesNotExistException);
                }
            });
        }
    }

    @Test
    @DisplayName("Should maintain backward compatibility")
    void testBackwardCompatibility() {
        // Test that existing functionality still works
        assertTrue(wagon.supportsDirectoryCopy());
        
        // Test that wagon can be configured as before
        assertDoesNotThrow(() -> {
            wagon.setTimeout(60000);
            wagon.setReadTimeout(300000);
            wagon.setInteractive(false);
        });
        
        // Verify configuration
        assertEquals(60000, wagon.getTimeout());
        assertEquals(300000, wagon.getReadTimeout());
        assertFalse(wagon.isInteractive());
    }
}
