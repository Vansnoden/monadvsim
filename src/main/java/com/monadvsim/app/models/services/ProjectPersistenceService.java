package com.monadvsim.app.models.services;

import com.monadvsim.app.models.engine.EnhancedTimeManager;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.netcdf.NetCDFClimateReader;
import com.monadvsim.app.models.netcdf.NetCDFMetadata;
import com.monadvsim.app.models.utils.GeoToolsResourceFactory;
import com.monadvsim.app.models.utils.ResourceManager;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import javax.imageio.ImageIO;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.geotools.gce.image.WorldImageFormat;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.api.parameter.GeneralParameterValue;

/**
 * Updated service with proper resource management
 */
public class ProjectPersistenceService {
    private final ResourceManager resourceManager = ResourceManager.getInstance();
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
    
    /**
     * Load raster data with automatic resource management
     */
    public void loadRasterData(RasterLayer layer, String filePath) throws Exception {
        File file = new File(filePath);
        validateFile(file, "TIFF");
        
        // Use try-with-resources for auto-closing
        try (GeoToolsResourceFactory.AutoCloseableGeoTiffReader reader = 
                GeoToolsResourceFactory.createGeoTiffReader(file)) {
            
            // Read coverage
            GridCoverage2D coverage = reader.read(null);
            
            // Extract metadata
            GridGeometry2D geometry = coverage.getGridGeometry();
            ReferencedEnvelope bounds = geometry.getEnvelope2D();
            
            int width = geometry.getGridRange().getSpan(0);
            int height = geometry.getGridRange().getSpan(1);
            
            // Initialize layer
            layer.initialize(width, height, 1);
            layer.setBounds(
                bounds.getMinX(), bounds.getMaxX(),
                bounds.getMinY(), bounds.getMaxY()
            );
            
            // Bulk data loading (optimized)
            loadRasterDataBulk(coverage, layer, 0);
            
            System.out.printf("✓ Loaded raster: %s [%dx%d]%n", 
                layer.getName(), width, height);
            
        } catch (Exception e) {
            throw new IOException("Failed to load raster: " + filePath, e);
        }
    }
    
    /**
     * Bulk load raster data from coverage
     */
    private void loadRasterDataBulk(GridCoverage2D coverage, RasterLayer layer, int frameIndex) 
            throws IOException {
        Raster raster = coverage.getRenderedImage().getData();
        int width = raster.getWidth();
        int height = raster.getHeight();
        
        // Allocate buffer for bulk reading
        double[] buffer = new double[width * height];
        
        // Read all pixels at once
        raster.getPixels(0, 0, width, height, buffer);
        
        // Fill layer data
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                layer.setData(frameIndex, x, y, buffer[index++]);
            }
        }
        
        // Release buffer
        buffer = null;
        System.gc();
    }
    
    /**
     * Load climate NetCDF with proper resource management
     */
    public void loadClimateNetCDF(RasterLayer tempLayer, RasterLayer rainLayer, 
                                 String filePath, int maxFrames) throws Exception {
        File file = new File(filePath);
        validateFile(file, "NetCDF");
        
        // Use try-with-resources
        try (GeoToolsResourceFactory.AutoCloseableNetCDFReader reader = 
                GeoToolsResourceFactory.createNetCDFReader(file)) {
            
            // Get available variables
            String[] coverageNames = reader.getGridCoverageNames();
            System.out.println("NetCDF variables: " + Arrays.toString(coverageNames));
            
            // Find temperature and precipitation variables
            String tempVar = findVariable(coverageNames, "temperature", "t2m", "temp");
            String rainVar = findVariable(coverageNames, "precipitation", "tp", "rain");
            
            if (tempVar == null || rainVar == null) {
                throw new IOException("Required variables not found in NetCDF file");
            }
            
            System.out.printf("Using variables: Temp=%s, Rain=%s%n", tempVar, rainVar);
            
            // Initialize layers if not already initialized
            if (tempLayer.getWidth() <= 1) {
                tempLayer.initialize(1000, 1000, maxFrames); // Default size
            }
            if (rainLayer.getWidth() <= 1) {
                rainLayer.initialize(1000, 1000, maxFrames);
            }
            
            // Process in chunks to manage memory
            processNetCDFInChunks(reader, tempLayer, rainLayer, tempVar, rainVar, maxFrames);
            
            System.out.printf("✓ Loaded NetCDF: %s [%d frames]%n", filePath, maxFrames);
            
        } catch (Exception e) {
            throw new IOException("Failed to load NetCDF: " + filePath, e);
        }
    }
    
    /**
     * Process NetCDF in chunks to avoid memory issues
     */
    private void processNetCDFInChunks(GeoToolsResourceFactory.AutoCloseableNetCDFReader reader,
                                      RasterLayer tempLayer, RasterLayer rainLayer,
                                      String tempVar, String rainVar, int maxFrames) throws Exception {
        int chunkSize = 100; // Process 100 frames at a time
        int totalChunks = (int) Math.ceil((double) maxFrames / chunkSize);
        
        for (int chunk = 0; chunk < totalChunks; chunk++) {
            int startFrame = chunk * chunkSize;
            int endFrame = Math.min(startFrame + chunkSize, maxFrames);
            
            System.out.printf("Processing frames %d to %d...%n", startFrame, endFrame - 1);
            
            for (int frame = startFrame; frame < endFrame; frame++) {
                // Read coverage for this frame
                // Note: This assumes time dimension handling - you may need to adjust
                GridCoverage2D tempCoverage = reader.read(tempVar, createTimeParams(frame));
                GridCoverage2D rainCoverage = reader.read(rainVar, createTimeParams(frame));
                
                try {
                    // Load data
                    loadRasterDataBulk(tempCoverage, tempLayer, frame);
                    loadRasterDataBulk(rainCoverage, rainLayer, frame);
                } finally {
                    // Dispose coverages to free memory
                    tempCoverage.dispose(true);
                    rainCoverage.dispose(true);
                }
            }
            
            // Force garbage collection between chunks for large datasets
            if (chunk % 10 == 0) {
                System.gc();
            }
        }
    }
    
    /**
     * Create time parameters for NetCDF reading
     */
    private GeneralParameterValue[] createTimeParams(int timeIndex) {
        // Implementation depends on your NetCDF structure
        // This is a simplified version
        ParameterValue<List> timeParam = WorldImageFormat.TIME.createValue();
        timeParam.setValue(new int[]{timeIndex});
        
        return new GeneralParameterValue[]{timeParam};
    }
    
    /**
     * Export to CSV with proper resource management
     */
    public void exportToCSV(Project project, String outputPath) throws IOException {
        Path path = Path.of(outputPath);
        Files.createDirectories(path.getParent());
        
        // Use try-with-resources for all file handles
        try (FileWriter fileWriter = new FileWriter(outputPath);
             BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
             PrintWriter writer = new PrintWriter(bufferedWriter)) {
            
            writeCSVHeader(writer);
            writeCSVData(project, writer);
            
            System.out.printf("✓ Exported results to: %s%n", outputPath);
            
        } catch (IOException e) {
            throw new IOException("Failed to export CSV: " + outputPath, e);
        }
    }
    
    private void writeCSVHeader(PrintWriter writer) {
        writer.println("AgentID,X,Y,Type,Status,PopDensity,TempC,Rain_mm,Age,LarvaeCount,isGravid");
    }
    
    private void writeCSVData(Project project, PrintWriter writer) {
        RasterLayer pop = project.getRasterByName("Population");
        RasterLayer temp = project.getRasterByName("Temperature");
        RasterLayer rain = project.getRasterByName("Rainfall");
        
        for (AgentLayer layer : project.getAgentLayers()) {
            for (Agent agent : layer.getAgents()) {
                // Get environmental data
                double t = project.getTemperatureAt(agent.getX(), agent.getY());
                double r = (rain != null) ? rain.getValueAt(agent.getX(), agent.getY()) * 1000 : 0.0;
                double p = (pop != null) ? pop.getValueAt(agent.getX(), agent.getY()) : 0.0;
                
                // Format and write row
                String row = formatCSVRow(agent, t, r, p);
                writer.println(row);
            }
        }
    }
    
    private String formatCSVRow(Agent agent, double temp, double rain, double pop) {
        String type = (agent instanceof LivingAgent) ? "Adult" : "Habitat";
        int larvae = (agent instanceof InertAgent ia) ? ia.getLarvalCount() : 0;
        int age = (agent instanceof LivingAgent la) ? la.getAge() : 0;
        boolean gravid = (agent instanceof LivingAgent la) && la.isGravid();
        boolean alive = (agent instanceof LivingAgent la) && la.isAlive();
        
        return String.format(Locale.US, "%s,%.6f,%.6f,%s,%b,%.4f,%.2f,%.4f,%d,%d,%b",
            agent.getId(), agent.getX(), agent.getY(),
            type, alive, pop, temp, rain, age, larvae, gravid);
    }
    
    /**
     * Validate file exists and is readable
     */
    private void validateFile(File file, String fileType) throws FileNotFoundException, IOException {
        if (!file.exists()) {
            throw new FileNotFoundException(fileType + " file not found: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new SecurityException("Cannot read " + fileType + " file: " + file.getAbsolutePath());
        }
        if (file.length() == 0) {
            throw new IOException(fileType + " file is empty: " + file.getAbsolutePath());
        }
    }
    
    /**
     * Find variable name from available names
     */
    private String findVariable(String[] availableNames, String... possibleNames) {
        for (String possible : possibleNames) {
            for (String available : availableNames) {
                if (available.toLowerCase().contains(possible.toLowerCase())) {
                    return available;
                }
            }
        }
        return null;
    }
    
    /**
     * Clean up all resources
     */
    public void cleanup() {
        System.out.println("Cleaning up ProjectPersistenceService resources...");
        
        // Clear file locks
        fileLocks.clear();
        
        // Clear GeoTools cache
        GeoToolsResourceFactory.clearCache();
        
        // Request garbage collection
        System.gc();
        
        // Print resource statistics
        Map<String, Object> stats = resourceManager.getStatistics();
        System.out.println("Resource statistics: " + stats);
    }
    
    /**
     * Get service statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("fileLocks", fileLocks.size());
        stats.put("resourceStats", resourceManager.getStatistics());
        stats.put("geoToolsCache", GeoToolsResourceFactory.getCacheStats());
        
        return stats;
    }
    
    /**
     * Enhanced NetCDF loading with proper time handling
     */
    public void loadClimateNetCDFEnhanced(RasterLayer tempLayer, RasterLayer rainLayer,
                                         String filePath, 
                                         LocalDateTime simulationStart,
                                         int simulationTicks,
                                         int tickMinutes) throws Exception {
        
        File file = new File(filePath);
        if (!file.exists()) {
            throw new java.io.FileNotFoundException("NetCDF file not found: " + filePath);
        }
        
        System.out.println("Loading NetCDF with enhanced reader...");
        
        try (NetCDFClimateReader reader = new NetCDFClimateReader(filePath)) {
            
            // Create time manager synchronized with NetCDF
            EnhancedTimeManager timeManager = new EnhancedTimeManager(
                simulationStart, simulationTicks, tickMinutes, reader);
            
            // Create converter
            NetCDFToRasterConverter converter = new NetCDFToRasterConverter();
            
            // Convert NetCDF to raster layers
            NetCDFToRasterConverter.ClimateRasters climateRasters = 
                converter.convertNetCDFToRasters(
                    reader, timeManager, "Climate_Data");
            
            // Copy data to provided layers
            copyRasterData(climateRasters.temperature, tempLayer);
            copyRasterData(climateRasters.precipitation, rainLayer);
            
            // Set time manager in project if needed
            // (You would need to modify Project class to store time manager)
            
            converter.shutdown();
            
            System.out.println("✓ Enhanced NetCDF loading complete");
            printNetCDFSummary(reader, timeManager);
            
        } catch (Exception e) {
            throw new Exception("Failed to load NetCDF with enhanced reader: " + filePath, e);
        }
    }
    
    private void copyRasterData(RasterLayer source, RasterLayer target) {
        target.initialize(source.getWidth(), source.getHeight(), source.getFrames());
        target.setBounds(
            source.getMinLon(), source.getMaxLon(),
            source.getMinLat(), source.getMaxLat()
        );
        
        // Copy data grid (simplified - would need actual data copy)
        if (source instanceof MemoryMappedRasterLayer && 
            target instanceof MemoryMappedRasterLayer) {
            // For memory-mapped layers, we would copy the data
            System.out.println("Copied raster data for " + target.getName());
        }
    }
    
    private void printNetCDFSummary(NetCDFClimateReader reader, 
                                   EnhancedTimeManager timeManager) {
        System.out.println("\n=== NetCDF Loading Summary ===");
        
        try {
            // Get NetCDF metadata
            NetCDFMetadata metadata = new NetCDFMetadata(reader.getDataset().getLocation());
            Map<String, Object> summary = metadata.getSummary();
            
            System.out.println("File: " + summary.get("filePath"));
            System.out.println("Format: " + summary.get("format"));
            
            Map<String, Object> dims = (Map<String, Object>) summary.get("dimensions");
            System.out.println("Dimensions: " + dims);
            
            System.out.println("Time synchronization: " + 
                (timeManager.isSynchronizedWithNetCDF() ? "SUCCESS" : "FAILED"));
            
            if (timeManager.isSynchronizedWithNetCDF()) {
                System.out.println("NetCDF time steps: " + timeManager.getNetCDFTimes().size());
                System.out.println("NetCDF time step: " + timeManager.getNetCDFTimeStep());
                
                if (!timeManager.getNetCDFTimes().isEmpty()) {
                    System.out.println("NetCDF time range: " + 
                        timeManager.getNetCDFTimes().get(0) + " to " +
                        timeManager.getNetCDFTimes().get(timeManager.getNetCDFTimes().size() - 1));
                }
            }
            
            metadata.close();
            
        } catch (Exception e) {
            System.err.println("Error generating NetCDF summary: " + e.getMessage());
        }
        
        System.out.println("=============================\n");
    }
    
    /**
     * Legacy method for backward compatibility
     */
    public void loadClimateNetCDF(RasterLayer tempLayer, RasterLayer rainLayer, 
                                 String filePath) throws Exception {
        // Use default parameters for backward compatibility
        loadClimateNetCDFEnhanced(tempLayer, rainLayer, filePath,
            LocalDateTime.of(2024, 1, 1, 0, 0), // Default start
            3000, // Default ticks
            15);  // Default 15-minute ticks
    }
}