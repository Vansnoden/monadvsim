package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.utils.SimulationLogger;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Large Raster Handler using memory‑mapped files.
 * Avoids heap allocation of the full 3D array.
 */
public class MemoryMappedRasterLayer extends RasterLayer {

    private static final int DOUBLE_BYTES = Double.BYTES;
    private static final long MEMORY_THRESHOLD = 100 * 1024 * 1024; // 100 MB

    private File dataFile;
    private MappedByteBuffer[] frameBuffers;
    private FileChannel fileChannel;
    private boolean memoryMapped = false;
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();

    // Local copy of dimensions (superclass fields are also available)
    private int localWidth, localHeight, localFrames;

    public MemoryMappedRasterLayer(String name, int width, int height, int frames) {
        super(name);   // now valid – uses the name‑only constructor
        this.localWidth = width;
        this.localHeight = height;
        this.localFrames = frames;

        // Set dimensions in superclass (so getWidth() etc. work)
        setWidth(width);
        setHeight(height);
        setFrames(frames);

        long estimatedSize = (long) width * height * frames * DOUBLE_BYTES;
        if (estimatedSize > MEMORY_THRESHOLD) {
            enableMemoryMapping();
        } else {
            // Fallback: allocate in‑memory array via super.initialize()
            super.initialize(width, height, frames);
            memoryMapped = false;
        }
    }

    private void enableMemoryMapping() {
        try {
            dataFile = File.createTempFile("raster_" + getName(), ".dat");
            dataFile.deleteOnExit();

            long fileSize = (long) localWidth * localHeight * localFrames * DOUBLE_BYTES;
            fileChannel = FileChannel.open(dataFile.toPath(),
                    StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);

            frameBuffers = new MappedByteBuffer[localFrames];
            long frameSize = (long) localWidth * localHeight * DOUBLE_BYTES;

            for (int i = 0; i < localFrames; i++) {
                long position = i * frameSize;
                frameBuffers[i] = fileChannel.map(FileChannel.MapMode.READ_WRITE, position, frameSize);
            }

            memoryMapped = true;
            SimulationLogger.info("✓ Memory‑mapped raster: %s [%d MB]",
                    getName(), fileSize / (1024 * 1024));
        } catch (IOException e) {
            SimulationLogger.severe("Failed to enable memory mapping for %s: %s", getName(), e.getMessage());
            memoryMapped = false;
            super.initialize(localWidth, localHeight, localFrames);
        }
    }

    @Override
    public double getValueAt(double lon, double lat) {
        if (!memoryMapped) return super.getValueAt(lon, lat);

        if (getMaxLon() == getMinLon() || getMaxLat() == getMinLat()) return 0.0;

        double xFrac = (lon - getMinLon()) / (getMaxLon() - getMinLon());
        double yFrac = (lat - getMinLat()) / (getMaxLat() - getMinLat());

        int x = (int) (xFrac * (localWidth - 1));
        int y = (int) (yFrac * (localHeight - 1));

        if (x < 0 || x >= localWidth || y < 0 || y >= localHeight) return 0.0;

        fileLock.readLock().lock();
        try {
            int index = y * localWidth + x;
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
        if (frame < 0 || frame >= localFrames || x < 0 || x >= localWidth || y < 0 || y >= localHeight)
            return;

        fileLock.writeLock().lock();
        try {
            int index = y * localWidth + x;
            frameBuffers[frame].putDouble(index * DOUBLE_BYTES, value);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    @Override
    public void setActiveFrame(int index) {
        if (memoryMapped && index >= 0 && index < localFrames && frameBuffers[index] != null) {
            frameBuffers[index].load();
        }
        super.setActiveFrame(index);
    }

    @Override
    public void initialize(int width, int height, int frames) {
        // Called only if we fall back to in‑memory storage.
        if (!memoryMapped) {
            super.initialize(width, height, frames);
        }
    }

    public void dispose() {
        fileLock.writeLock().lock();
        try {
            if (memoryMapped && frameBuffers != null) {
                for (MappedByteBuffer buf : frameBuffers) {
                    if (buf != null) clean(buf);
                }
                frameBuffers = null;
            }
            if (fileChannel != null && fileChannel.isOpen()) {
                fileChannel.close();
            }
            if (dataFile != null && dataFile.exists()) {
                Files.deleteIfExists(dataFile.toPath());
            }
            memoryMapped = false;
        } catch (IOException e) {
            SimulationLogger.severe("Error disposing memory‑mapped raster: " + e.getMessage());
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private void clean(MappedByteBuffer buffer) {
        try {
            Method cleaner = buffer.getClass().getMethod("cleaner");
            cleaner.setAccessible(true);
            Object cleanerObj = cleaner.invoke(buffer);
            Method clean = cleanerObj.getClass().getMethod("clean");
            clean.invoke(cleanerObj);
        } catch (Exception e) {
            SimulationLogger.warning("Could not unmap buffer: " + e.getMessage());
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try { dispose(); } finally { super.finalize(); }
    }

    public Map<String, Object> getMemoryStats() {
        Map<String, Object> stats = new HashMap<>();
        if (memoryMapped) {
            long fileSize = (long) localWidth * localHeight * localFrames * DOUBLE_BYTES;
            stats.put("storage", "memory‑mapped");
            stats.put("fileSizeMB", fileSize / (1024.0 * 1024.0));
            stats.put("framesMapped", frameBuffers != null ? frameBuffers.length : 0);
        } else {
            stats.put("storage", "in‑memory");
            long memSize = (long) localWidth * localHeight * localFrames * DOUBLE_BYTES;
            stats.put("memorySizeMB", memSize / (1024.0 * 1024.0));
        }
        stats.put("width", localWidth);
        stats.put("height", localHeight);
        stats.put("frames", localFrames);
        stats.put("activeFrame", getActiveFrame());
        return stats;
    }
}