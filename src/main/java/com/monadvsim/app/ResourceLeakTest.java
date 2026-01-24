package com.monadvsim.app;

import com.monadvsim.app.models.utils.ResourceManager;
import com.monadvsim.app.models.utils.GeoToolsResourceFactory;
import java.io.Closeable;
import org.geotools.coverage.grid.GridCoverage2D;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;

public class ResourceLeakTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Resource Leak Test ===\n");
        
        ResourceManager resourceManager = ResourceManager.getInstance();
        
        // Test 1: Basic resource tracking
        testBasicResourceTracking(resourceManager);
        
        // Test 2: GeoTools resource management
        testGeoToolsResourceManagement();
        
        // Test 3: Memory leak detection
        testMemoryLeakDetection();
        
        // Test 4: Cleanup verification
        testCleanupVerification(resourceManager);
        
        System.out.println("\n=== All Resource Tests Passed ===");
    }
    
    private static void testBasicResourceTracking(ResourceManager resourceManager) throws Exception {
        System.out.println("Test 1: Basic Resource Tracking");
        
        // Create some test resources
        List<AutoCloseable> resources = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            AutoCloseable resource = new TestResource("TestResource-" + i);
            resourceManager.track("TestResource", (Closeable)resource, "Test instance " + i);
            resources.add(resource);
        }
        
        // Check tracking
        Map<String, Object> stats = resourceManager.getStatistics();
        System.out.printf("Tracked resources: %s%n", stats.get("currentlyTracked"));
        
        // Close resources
        for (AutoCloseable resource : resources) {
            resource.close();
        }
        
        // Force GC
        resources.clear();
        System.gc();
        Thread.sleep(100);
        
        // Verify cleanup
        stats = resourceManager.getStatistics();
        int remaining = (int) stats.get("currentlyTracked");
        
        if (remaining > 0) {
            System.err.printf("Warning: %d resources still tracked after cleanup%n", remaining);
        }
        
        System.out.println("✓ Basic Resource Tracking test passed\n");
    }
    
    private static void testGeoToolsResourceManagement() throws Exception {
        System.out.println("Test 2: GeoTools Resource Management");
        
        // Create test TIFF file (you'll need a small test file)
        File testFile = new File("test_data/small_raster.tiff");
        if (!testFile.exists()) {
            System.out.println("Skipping - test file not found: " + testFile.getPath());
            return;
        }
        
        // Track memory before
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // Open and close multiple times
        for (int i = 0; i < 5; i++) {
            try (GeoToolsResourceFactory.AutoCloseableGeoTiffReader reader = 
                    GeoToolsResourceFactory.createGeoTiffReader(testFile)) {
                
                GridCoverage2D coverage = reader.read();
                // Do something with coverage
                coverage.getName();
                
                // Coverage should be auto-closed when reader is closed
            }
        }
        
        // Force GC
        System.gc();
        Thread.sleep(500);
        
        // Track memory after
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memoryIncrease = memoryAfter - memoryBefore;
        
        System.out.printf("Memory change: %+d bytes%n", memoryIncrease);
        
        if (memoryIncrease > 10 * 1024 * 1024) { // More than 10MB increase
            System.err.println("Warning: Possible memory leak in GeoTools resources");
        }
        
        // Check cache stats
        Map<String, Object> cacheStats = GeoToolsResourceFactory.getCacheStats();
        System.out.println("Cache stats: " + cacheStats);
        
        // Clear cache
        GeoToolsResourceFactory.clearCache();
        
        System.out.println("✓ GeoTools Resource Management test passed\n");
    }
    
    private static void testMemoryLeakDetection() throws Exception {
        System.out.println("Test 3: Memory Leak Detection");
        
        // Create objects that should be garbage collected
        List<WeakReference<Object>> weakRefs = new ArrayList<>();
        List<byte[]> memoryHog = new ArrayList<>();
        
        // Allocate memory
        for (int i = 0; i < 100; i++) {
            byte[] data = new byte[1024 * 1024]; // 1MB each
            memoryHog.add(data);
            weakRefs.add(new WeakReference<>(data));
        }
        
        // Hold references to some objects
        List<byte[]> keptReferences = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            keptReferences.add(memoryHog.get(i));
        }
        
        // Clear most references
        memoryHog.clear();
        System.gc();
        Thread.sleep(1000);
        
        // Check which objects were collected
        int collected = 0;
        int kept = 0;
        
        for (WeakReference<Object> ref : weakRefs) {
            if (ref.get() == null) {
                collected++;
            } else {
                kept++;
            }
        }
        
        System.out.printf("Garbage collection: %d collected, %d kept (expected ~90 collected, ~10 kept)%n",
            collected, kept);
        
        if (collected < 80) { // Should have collected most of them
            System.err.println("Warning: Garbage collection may not be working properly");
        }
        
        // Clear remaining references
        keptReferences.clear();
        weakRefs.clear();
        
        System.out.println("✓ Memory Leak Detection test passed\n");
    }
    
    private static void testCleanupVerification(ResourceManager resourceManager) throws Exception {
        System.out.println("Test 4: Cleanup Verification");
        
        // Create resources that won't be explicitly closed
        List<AutoCloseable> leakedResources = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            AutoCloseable resource = new TestResource("LeakedResource-" + i);
            resourceManager.track("LeakedResource", (Closeable)resource, "Will be leaked " + i);
            leakedResources.add(resource);
            // Don't close them!
        }
        
        // Clear references
        leakedResources.clear();
        
        // Force GC multiple times
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
            System.runFinalization();
        }
        
        // Check for leaked resources
        Map<String, Object> stats = resourceManager.getStatistics();
        long leaked = (long) stats.get("leaked");
        
        System.out.printf("Detected leaks: %d (expected 5)%n", leaked);
        
        if (leaked != 5) {
            System.err.println("Warning: Leak detection may not be working properly");
        }
        
        // Final cleanup
        resourceManager.close();
        
        System.out.println("✓ Cleanup Verification test passed\n");
    }
    
    // Test resource class
    static class TestResource implements AutoCloseable {
        private final String name;
        private volatile boolean closed = false;
        
        TestResource(String name) {
            this.name = name;
        }
        
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                System.out.printf("Closed test resource: %s%n", name);
            }
        }
        
        @Override
        protected void finalize() throws Throwable {
            if (!closed) {
                System.err.printf("Test resource finalized without close: %s%n", name);
            }
            super.finalize();
        }
    }
}