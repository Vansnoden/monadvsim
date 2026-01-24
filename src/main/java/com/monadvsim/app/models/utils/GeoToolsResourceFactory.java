package com.monadvsim.app.models.utils;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.coverage.io.netcdf.NetCDFReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating auto-closeable GeoTools readers
 */
public final class GeoToolsResourceFactory {
    private static final ResourceManager resourceManager = ResourceManager.getInstance();
    private static final ConcurrentHashMap<String, WeakReference<GridCoverage2D>> coverageCache = 
        new ConcurrentHashMap<>();
    
    private GeoToolsResourceFactory() {}
    
    /**
     * Create an auto-closeable GeoTiffReader
     */
    public static AutoCloseableGeoTiffReader createGeoTiffReader(File file) throws IOException {
        return new AutoCloseableGeoTiffReader(file);
    }
    
    /**
     * Create an auto-closeable NetCDFReader
     */
    public static AutoCloseableNetCDFReader createNetCDFReader(File file) throws IOException {
        return new AutoCloseableNetCDFReader(file);
    }
    
    /**
     * Get cached GridCoverage2D or create new one
     */
    public static GridCoverage2D getOrCreateCoverage(String filePath, 
                                                    CoverageSupplier supplier) throws Exception {
        // Check cache
        WeakReference<GridCoverage2D> cachedRef = coverageCache.get(filePath);
        GridCoverage2D cached = cachedRef != null ? cachedRef.get() : null;
        
        if (cached != null && !isDisposed(cached)) {
            return cached;
        }
        
        // Create new coverage
        GridCoverage2D coverage = supplier.get();
        
        // Track the coverage - trackGeoTools returns the same GridCoverage2D
        GridCoverage2D trackedCoverage = resourceManager.trackGeoTools(
            "GridCoverage2D", coverage, filePath);
        
        // Cache weak reference
        coverageCache.put(filePath, new WeakReference<>(trackedCoverage));
        
        return trackedCoverage;
    }
    
    private static boolean isDisposed(GridCoverage2D coverage) {
        try {
            // Try to access a property to check if disposed
            coverage.getName();
            return false;
        } catch (Exception e) {
            return true;
        }
    }
    
    /**
     * Clear the coverage cache
     */
    public static void clearCache() {
        coverageCache.clear();
        System.gc();
    }
    
    /**
     * Get cache statistics
     */
    public static Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", coverageCache.size());
        
        int aliveCount = 0;
        for (WeakReference<GridCoverage2D> ref : coverageCache.values()) {
            if (ref.get() != null) {
                aliveCount++;
            }
        }
        stats.put("aliveInCache", aliveCount);
        
        return stats;
    }
    
    // Functional interface for coverage creation
    @FunctionalInterface
    public interface CoverageSupplier {
        GridCoverage2D get() throws Exception;
    }
    
    /**
     * AutoCloseable wrapper for GeoTiffReader
     */
    public static class AutoCloseableGeoTiffReader implements AutoCloseable, Closeable {
        private final GeoTiffReader reader;
        private final File file;
        private volatile boolean closed = false;
        
        public AutoCloseableGeoTiffReader(File file) throws IOException {
            this.file = file;
            this.reader = new GeoTiffReader(file);
            
            // Track resource - now this works because we implement Closeable
            resourceManager.track("GeoTiffReader", this, file.getAbsolutePath());
        }
        
        public GridCoverage2D read(org.geotools.api.parameter.GeneralParameterValue[] params) 
                throws IOException {
            checkClosed();
            GridCoverage2D coverage = reader.read(params);
            
            // Track the coverage - trackGeoTools returns GridCoverage2D
            return resourceManager.trackGeoTools(
                "GridCoverage2D", coverage, "From: " + file.getAbsolutePath());
        }
        
        public GridCoverage2D read() throws IOException {
            return read(null);
        }
        
        public File getFile() {
            return file;
        }
        
        public GeoTiffReader getReader() {
            checkClosed();
            return reader;
        }
        
        private void checkClosed() {
            if (closed) {
                throw new IllegalStateException("GeoTiffReader already closed: " + file);
            }
        }
        
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    reader.dispose();
                    System.out.printf("[Resource] Closed GeoTiffReader: %s%n", file.getAbsolutePath());
                } catch (Exception e) {
                    System.err.printf("Error closing GeoTiffReader (%s): %s%n", 
                        file.getAbsolutePath(), e.getMessage());
                }
            }
        }
        
        @Override
        protected void finalize() throws Throwable {
            if (!closed) {
                System.err.printf("[Finalizer] GeoTiffReader not properly closed: %s%n", 
                    file.getAbsolutePath());
                close();
            }
            super.finalize();
        }
    }
    
    /**
     * AutoCloseable wrapper for NetCDFReader
     */
    public static class AutoCloseableNetCDFReader implements AutoCloseable, Closeable {
        private final NetCDFReader reader;
        private final File file;
        private volatile boolean closed = false;
        
        public AutoCloseableNetCDFReader(File file) throws IOException {
            this.file = file;
            this.reader = new NetCDFReader(file, null);
            
            // Track resource - now this works because we implement Closeable
            resourceManager.track("NetCDFReader", this, file.getAbsolutePath());
        }
        
        public GridCoverage2D read(String coverageName, 
                                  org.geotools.api.parameter.GeneralParameterValue[] params) 
                throws IOException {
            checkClosed();
            GridCoverage2D coverage = reader.read(coverageName, params);
            
            // Track the coverage - trackGeoTools returns GridCoverage2D
            return resourceManager.trackGeoTools(
                "GridCoverage2D", coverage, 
                coverageName + " from: " + file.getAbsolutePath());
        }
        
        public String[] getGridCoverageNames() {
            checkClosed();
            return reader.getGridCoverageNames();
        }
        
        public File getFile() {
            return file;
        }
        
        public NetCDFReader getReader() {
            checkClosed();
            return reader;
        }
        
        private void checkClosed() {
            if (closed) {
                throw new IllegalStateException("NetCDFReader already closed: " + file);
            }
        }
        
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    reader.dispose();
                    System.out.printf("[Resource] Closed NetCDFReader: %s%n", file.getAbsolutePath());
                } catch (Exception e) {
                    System.err.printf("Error closing NetCDFReader (%s): %s%n", 
                        file.getAbsolutePath(), e.getMessage());
                }
            }
        }
        
        @Override
        protected void finalize() throws Throwable {
            if (!closed) {
                System.err.printf("[Finalizer] NetCDFReader not properly closed: %s%n", 
                    file.getAbsolutePath());
                close();
            }
            super.finalize();
        }
    }
}