package com.monadvsim.app.models.services;

import com.monadvsim.app.models.entities.RasterLayer;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import javax.media.jai.RasterFactory;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.awt.image.Raster;
import java.io.File;

public class RasterLoader {
    
    /**
     * Bulk load raster data using WritableRaster for performance
     */
    public static void loadRasterBulk(RasterLayer layer, String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new java.io.FileNotFoundException("Raster file not found: " + filePath);
        }
        
        GeoTiffReader reader = null;
        try {
            reader = new GeoTiffReader(file);
            GridCoverage2D coverage = reader.read(null);
            
            // Get the raster data in bulk
            Raster raster = coverage.getRenderedImage().getData();
            
            int width = raster.getWidth();
            int height = raster.getHeight();
            int frames = 1; // Single frame for TIFF
            
            // Initialize layer with correct dimensions
            layer.initialize(width, height, frames);
            
            // Bulk data transfer
            double[] pixelData = new double[width * height];
            raster.getPixels(0, 0, width, height, pixelData);
            
            // Fill layer data - much faster than pixel-by-pixel
            int index = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    layer.setData(0, x, y, pixelData[index++]);
                }
            }
            
            // Set bounds from coverage
            GridGeometry2D geometry = coverage.getGridGeometry();
            org.geotools.geometry.jts.ReferencedEnvelope bounds = geometry.getEnvelope2D();
            layer.setBounds(
                bounds.getMinX(), bounds.getMaxX(),
                bounds.getMinY(), bounds.getMaxY()
            );
            
            System.out.println("✓ Bulk loaded: " + layer.getName() + 
                             " [" + width + "x" + height + "]");
            
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }
    
    /**
     * Optimized NetCDF loading with chunking
     */
    public static void loadNetCDFOptimized(RasterLayer tempLayer, RasterLayer rainLayer, 
                                          String filePath, int maxFrames) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new java.io.FileNotFoundException("NetCDF file not found: " + filePath);
        }
        
        org.geotools.coverage.io.netcdf.NetCDFReader reader = null;
        try {
            reader = new org.geotools.coverage.io.netcdf.NetCDFReader(file, null);
            
            // Get dimensions
            int width = tempLayer.getWidth();
            int height = tempLayer.getHeight();
            
            // Process in chunks to avoid memory issues
            int chunkSize = 100; // Process 100 frames at a time
            int totalChunks = (int) Math.ceil((double) maxFrames / chunkSize);
            
            for (int chunk = 0; chunk < totalChunks; chunk++) {
                int startFrame = chunk * chunkSize;
                int endFrame = Math.min(startFrame + chunkSize, maxFrames);
                
                System.out.println("Processing frames " + startFrame + " to " + (endFrame-1));
                
                for (int frame = startFrame; frame < endFrame; frame++) {
                    // Use appropriate NetCDF reading strategy
                    // This depends on your NetCDF structure
                    processNetCDFFrame(reader, tempLayer, rainLayer, frame, width, height);
                }
                
                // Optional: Clear memory between chunks for large datasets
                if (totalChunks > 1) {
                    System.gc();
                }
            }
            
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }
    
    private static void processNetCDFFrame(org.geotools.coverage.io.netcdf.NetCDFReader reader,
                                          RasterLayer tempLayer, RasterLayer rainLayer,
                                          int frame, int width, int height) throws Exception {
        // Implementation depends on NetCDF structure
        // This is a placeholder - you'll need to adapt to your actual NetCDF format
        
        // Example approach:
        // 1. Set time dimension parameter
        // 2. Read coverage for this time step
        // 3. Extract raster data in bulk
        
        // For now, using a simplified approach
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // This is still pixel-by-pixel - you should optimize this
                // based on your actual NetCDF reading capabilities
                double tempValue = readNetCDFValue(reader, "temperature", frame, x, y);
                double rainValue = readNetCDFValue(reader, "precipitation", frame, x, y);
                
                tempLayer.setData(frame, x, y, tempValue);
                rainLayer.setData(frame, x, y, rainValue);
            }
        }
    }
    
    private static double readNetCDFValue(org.geotools.coverage.io.netcdf.NetCDFReader reader,
                                         String variable, int frame, int x, int y) {
        // Implement based on your NetCDF structure
        return 0.0;
    }
}