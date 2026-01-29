package com.monadvsim.app.models.utils;


import java.io.Closeable;
import java.lang.ref.Cleaner;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Resource LifeCycle Manager
 *
 * Tracks and manages resources (file handles, memory mappings)
 *
 * Uses Cleaner API and PhantomReferences for resource cleanup
 *
 * Detects resource leaks and ensures proper disposal
 *
 * Specifically handles GeoTools GridCoverage2D resources
 * 
 * 
 * @author void
 */


public final class ResourceManager implements Closeable {
    private static final ResourceManager INSTANCE = new ResourceManager();
    private static final Cleaner CLEANER = Cleaner.create();
    
    // Resource tracking
    private final Map<String, ResourceTracker> trackedResources = new ConcurrentHashMap<>();
    private final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    private final Map<PhantomReference<?>, CleanupAction> phantomReferences = new ConcurrentHashMap<>();
    
    // Statistics
    private final AtomicLong totalResources = new AtomicLong(0);
    private final AtomicLong leakedResources = new AtomicLong(0);
    private final AtomicLong disposedResources = new AtomicLong(0);
    
    // Cleanup thread
    private final Thread cleanupThread;
    private volatile boolean running = true;
    
    private ResourceManager() {
        // Start cleanup thread
        cleanupThread = new Thread(this::cleanupLoop, "ResourceManager-Cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.setPriority(Thread.MIN_PRIORITY);
        cleanupThread.start();
        
        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("ResourceManager shutdown initiated...");
            close();
        }));
    }
    
    public static ResourceManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Register a resource for tracking
     */
    public <T extends AutoCloseable> T track(String resourceType, T resource, String description) {
        String id = UUID.randomUUID().toString();
        ResourceTracker tracker = new ResourceTracker(id, resourceType, description, System.currentTimeMillis());
        
        trackedResources.put(id, tracker);
        totalResources.incrementAndGet();
        
        // Register with Cleaner for automatic cleanup
        CLEANER.register(resource, new ResourceCleanup(id, tracker));
        
        // Also track with phantom reference
        PhantomReference<T> phantomRef = new PhantomReference<>(resource, referenceQueue);
        phantomReferences.put(phantomRef, new CleanupAction(id, resource));
        
        System.out.printf("[Resource] Tracked: %s - %s (%s)%n", 
            resourceType, description, id.substring(0, 8));
        
        return resource;
    }
    
    /**
     * Manually release a tracked resource
     */
    public void release(Closeable resource) {
        try {
            resource.close();
            disposedResources.incrementAndGet();
        } catch (Exception e) {
            System.err.println("Error releasing resource: " + e.getMessage());
        }
    }
    
    /**
     * Track GeoTools resources (GridCoverage2D, Readers, etc.)
     */
    public <T> T trackGeoTools(String resourceType, T resource, String filePath) {
        if (resource instanceof org.geotools.coverage.grid.GridCoverage2D) {
            // Wrap GridCoverage2D in AutoCloseable wrapper
            GridCoverageWrapper wrapper = new GridCoverageWrapper(
                (org.geotools.coverage.grid.GridCoverage2D) resource, filePath);
            return (T) wrapper;
        }
        return resource;
    }
    
    private void cleanupLoop() {
        while (running) {
            try {
                // Check for garbage collected resources
                PhantomReference<?> phantomRef = (PhantomReference<?>) referenceQueue.remove(1000);
                if (phantomRef != null) {
                    CleanupAction action = phantomReferences.remove(phantomRef);
                    if (action != null) {
                        System.err.printf("[Resource Leak] Resource was garbage collected without proper cleanup: %s%n",
                            action.resourceId);
                        leakedResources.incrementAndGet();
                        
                        // Attempt cleanup if possible
                        if (action.resource instanceof Closeable) {
                            try {
                                ((Closeable) action.resource).close();
                            } catch (Exception e) {
                                // Ignore - resource may already be closed
                            }
                        }
                    }
                }
                
                // Periodic cleanup of old tracking entries
                if (System.currentTimeMillis() % 30000 < 1000) { // Every ~30 seconds
                    cleanupOldEntries();
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error in cleanup loop: " + e.getMessage());
            }
        }
    }
    
    private void cleanupOldEntries() {
        long cutoff = System.currentTimeMillis() - 300000; // 5 minutes
        Iterator<Map.Entry<String, ResourceTracker>> it = trackedResources.entrySet().iterator();
        
        while (it.hasNext()) {
            Map.Entry<String, ResourceTracker> entry = it.next();
            if (entry.getValue().creationTime < cutoff) {
                it.remove();
            }
        }
    }
    
    @Override
    public void close() {
        running = false;
        cleanupThread.interrupt();
        
        // Force cleanup of all tracked resources
        System.out.println("\n=== Resource Manager Final Cleanup ===");
        System.out.printf("Total resources tracked: %,d%n", totalResources.get());
        System.out.printf("Disposed resources: %,d%n", disposedResources.get());
        System.out.printf("Leaked resources: %,d%n", leakedResources.get());
        
        // Attempt to close any remaining resources
        int remaining = trackedResources.size();
        if (remaining > 0) {
            System.out.printf("Warning: %,d resources still tracked%n", remaining);
            
            for (ResourceTracker tracker : trackedResources.values()) {
                System.err.printf("Leaked: %s - %s (created %d ms ago)%n",
                    tracker.resourceType, tracker.description,
                    System.currentTimeMillis() - tracker.creationTime);
            }
        }
        
        trackedResources.clear();
        phantomReferences.clear();
    }
    
    /**
     * Get resource statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTracked", totalResources.get());
        stats.put("currentlyTracked", trackedResources.size());
        stats.put("disposed", disposedResources.get());
        stats.put("leaked", leakedResources.get());
        
        // Group by resource type
        Map<String, Integer> byType = new HashMap<>();
        for (ResourceTracker tracker : trackedResources.values()) {
            byType.merge(tracker.resourceType, 1, Integer::sum);
        }
        stats.put("byType", byType);
        
        return stats;
    }
    
    // Inner classes
    private static class ResourceTracker {
        final String id;
        final String resourceType;
        final String description;
        final long creationTime;
        
        ResourceTracker(String id, String resourceType, String description, long creationTime) {
            this.id = id;
            this.resourceType = resourceType;
            this.description = description;
            this.creationTime = creationTime;
        }
    }
    
    private static class ResourceCleanup implements Runnable {
        private final String id;
        private final ResourceTracker tracker;
        
        ResourceCleanup(String id, ResourceTracker tracker) {
            this.id = id;
            this.tracker = tracker;
        }
        
        @Override
        public void run() {
            System.err.printf("[Cleaner] Resource cleanup triggered for: %s - %s%n",
                tracker.resourceType, tracker.description);
        }
    }
    
    private static class CleanupAction {
        final String resourceId;
        final Object resource;
        
        CleanupAction(String resourceId, Object resource) {
            this.resourceId = resourceId;
            this.resource = resource;
        }
    }
    
    /**
     * AutoCloseable wrapper for GridCoverage2D
     */
    public static class GridCoverageWrapper implements AutoCloseable {
        private final org.geotools.coverage.grid.GridCoverage2D coverage;
        private final String source;
        private volatile boolean disposed = false;
        
        public GridCoverageWrapper(org.geotools.coverage.grid.GridCoverage2D coverage, String source) {
            this.coverage = coverage;
            this.source = source;
        }
        
        public org.geotools.coverage.grid.GridCoverage2D getCoverage() {
            if (disposed) {
                throw new IllegalStateException("GridCoverage2D already disposed: " + source);
            }
            return coverage;
        }
        
        @Override
        public void close() {
            if (!disposed) {
                disposed = true;
                try {
                    coverage.dispose(true);
                    System.out.printf("[Resource] Disposed GridCoverage2D: %s%n", source);
                } catch (Exception e) {
                    System.err.printf("Error disposing GridCoverage2D (%s): %s%n", 
                        source, e.getMessage());
                }
            }
        }
        
        @Override
        protected void finalize() throws Throwable {
            if (!disposed) {
                System.err.printf("[Finalizer] GridCoverage2D not properly disposed: %s%n", source);
                close();
            }
            super.finalize();
        }
    }
}