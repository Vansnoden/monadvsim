package com.monadvsim.app.models.entities;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * Large DataSet Handler
 *
 * Uses memory-mapped files for large raster dataSets
 *
 * Reduces memory footprint for high-resolution data
 *
 * Implements automatic memory management
 *
 * Falls back to in-memory storage for small dataSets
 * 
 * 
 * @author void
 */


public class MemoryMappedRasterLayer extends RasterLayer {
    private static final int DOUBLE_BYTES = Double.BYTES;
    
    private File dataFile;
    private MappedByteBuffer[] frameBuffers;
    private FileChannel fileChannel;
    private boolean memoryMapped = false;
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();
    
    // Memory thresholds (adjust based on available RAM)
    private static final long MEMORY_THRESHOLD = 100 * 1024 * 1024; // 100MB
    private static final long MAX_MEMORY_DATA = 500 * 1024 * 1024; // 500MB
    
    public MemoryMappedRasterLayer(String name, int width, int height, int frames) {
        super(name, width, height, frames);
        
        // Check if we should use memory mapping
        long estimatedSize = (long) width * height * frames * DOUBLE_BYTES;
        if (estimatedSize > MEMORY_THRESHOLD) {
            enableMemoryMapping();
        }
    }
    
    private void enableMemoryMapping() {
        try {
            // Create temporary file
            dataFile = File.createTempFile("raster_" + getName(), ".dat");
            dataFile.deleteOnExit();
            
            long fileSize = (long) getWidth() * getHeight() * getFrames() * DOUBLE_BYTES;
            
            // Open file channel
            fileChannel = FileChannel.open(
                dataFile.toPath(),
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE
            );
            
            // Map file to memory
            frameBuffers = new MappedByteBuffer[getFrames()];
            long frameSize = (long) getWidth() * getHeight() * DOUBLE_BYTES;
            
            for (int i = 0; i < getFrames(); i++) {
                long position = i * frameSize;
                frameBuffers[i] = fileChannel.map(
                    FileChannel.MapMode.READ_WRITE,
                    position,
                    frameSize
                );
            }
            
            memoryMapped = true;
            System.out.printf("✓ Memory-mapped raster: %s [%d MB]%n", 
                getName(), fileSize / (1024 * 1024));
            
        } catch (IOException e) {
            System.err.printf("Failed to enable memory mapping for %s: %s%n", 
                getName(), e.getMessage());
            memoryMapped = false;
            // Fall back to in-memory storage
            super.initialize(getWidth(), getHeight(), getFrames());
        }
    }
    
    @Override
    public double getValueAt(double lon, double lat) {
        if (!memoryMapped) {
            return super.getValueAt(lon, lat);
        }
        
        // Calculate pixel coordinates
        if (getMaxLon() == getMinLon() || getMaxLat() == getMinLat()) {
            return 0.0;
        }
        
        double xFrac = (lon - getMinLon()) / (getMaxLon() - getMinLon());
        double yFrac = (lat - getMinLat()) / (getMaxLat() - getMinLat());
        
        int x = (int) (xFrac * (getWidth() - 1));
        int y = (int) (yFrac * (getHeight() - 1));
        
        if (x < 0 || x >= getWidth() || y < 0 || y >= getHeight()) {
            return 0.0;
        }
        
        // Read from memory-mapped buffer
        fileLock.readLock().lock();
        try {
            int index = y * getWidth() + x;
            return frameBuffers[getActiveFrame()].getDouble(index * DOUBLE_BYTES);
        } finally {
            fileLock.readLock().unlock();
        }
    }
    
    @Override
    public void setData(int frame, int x, int y, double value) {
        if (!memoryMapped) {
            super.setData(frame, x, y, value);
            return;
        }
        
        if (frame < 0 || frame >= getFrames() || 
            x < 0 || x >= getWidth() || y < 0 || y >= getHeight()) {
            return;
        }
        
        fileLock.writeLock().lock();
        try {
            int index = y * getWidth() + x;
            frameBuffers[frame].putDouble(index * DOUBLE_BYTES, value);
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
    @Override
    public void setActiveFrame(int index) {
        if (memoryMapped) {
            // Ensure frame buffer is loaded
            if (index >= 0 && index < getFrames()) {
                // Force buffer load by accessing it
                if (frameBuffers[index] != null) {
                    frameBuffers[index].load();
                }
            }
        }
        super.setActiveFrame(index);
    }
    
    @Override
    public void initialize(int width, int height, int frames) {
        // Dispose existing resources
        dispose();
        
        // Call parent initialization
        super.initialize(width, height, frames);
        
        // Re-evaluate memory mapping
        long estimatedSize = (long) width * height * frames * DOUBLE_BYTES;
        if (estimatedSize > MEMORY_THRESHOLD) {
            enableMemoryMapping();
        }
    }
    
    /**
     * Dispose of resources
     */
    public void dispose() {
        fileLock.writeLock().lock();
        try {
            if (memoryMapped && frameBuffers != null) {
                // Unmap buffers
                for (MappedByteBuffer buffer : frameBuffers) {
                    if (buffer != null) {
                        // Java doesn't have direct unmap, but we can clear the reference
                        // The cleaner will handle it
                        buffer.clear();
                    }
                }
                frameBuffers = null;
            }
            
            // Close file channel
            if (fileChannel != null && fileChannel.isOpen()) {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    System.err.println("Error closing file channel: " + e.getMessage());
                }
            }
            
            // Delete temporary file
            if (dataFile != null && dataFile.exists()) {
                try {
                    Files.deleteIfExists(dataFile.toPath());
                } catch (IOException e) {
                    System.err.println("Error deleting temp file: " + e.getMessage());
                }
            }
            
            memoryMapped = false;
            
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            dispose();
        } finally {
            super.finalize();
        }
    }
    
    /**
     * Get memory usage statistics
     */
    public Map<String, Object> getMemoryStats() {
        Map<String, Object> stats = new HashMap<>();
        
        if (memoryMapped) {
            long fileSize = (long) getWidth() * getHeight() * getFrames() * DOUBLE_BYTES;
            stats.put("storage", "memory-mapped");
            stats.put("fileSizeMB", fileSize / (1024.0 * 1024.0));
            stats.put("framesMapped", frameBuffers != null ? frameBuffers.length : 0);
        } else {
            stats.put("storage", "in-memory");
            long memorySize = (long) getWidth() * getHeight() * getFrames() * DOUBLE_BYTES;
            stats.put("memorySizeMB", memorySize / (1024.0 * 1024.0));
        }
        
        stats.put("width", getWidth());
        stats.put("height", getHeight());
        stats.put("frames", getFrames());
        stats.put("activeFrame", getActiveFrame());
        
        return stats;
    }
}