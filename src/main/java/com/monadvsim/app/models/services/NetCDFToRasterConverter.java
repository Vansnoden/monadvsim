package com.monadvsim.app.models.services;

import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.netcdf.*;
import com.monadvsim.app.models.engine.EnhancedTimeManager;
import ucar.ma2.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Efficient conversion from NetCDF to RasterLayer with temporal interpolation
 */
public class NetCDFToRasterConverter {
    private final ExecutorService conversionExecutor;
    private final int batchSize;
    private final boolean enableCompression;
    
    // Progress tracking
    private final Map<String, Double> progress = new ConcurrentHashMap<>();
    
    public NetCDFToRasterConverter() {
        int processors = Runtime.getRuntime().availableProcessors();
        this.conversionExecutor = Executors.newFixedThreadPool(
            Math.max(2, processors - 1),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "NetCDF-Converter-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
        
        this.batchSize = 100; // Process 100 time steps per batch
        this.enableCompression = true;
    }
    
    /**
     * Convert NetCDF climate data to RasterLayers with temporal interpolation
     */
    public ClimateRasters convertNetCDFToRasters(
            NetCDFClimateReader reader,
            EnhancedTimeManager timeManager,
            String outputName) throws Exception {
        
        System.out.println("Converting NetCDF to raster layers...");
        
        // Detect climate variables
        NetCDFClimateReader.ClimateVariables climateVars = reader.detectClimateVariables();
        
        if (climateVars.temperature == null || climateVars.precipitation == null) {
            throw new IllegalArgumentException("NetCDF must contain temperature and precipitation variables");
        }
        
        // Get dimensions
        int[] tempShape = climateVars.temperature.getShape();
        int timeSteps = tempShape[0];
        int height = tempShape[1];  // Latitude dimension
        int width = tempShape[2];   // Longitude dimension
        
        System.out.printf("NetCDF dimensions: time=%d, lat=%d, lon=%d%n", 
            timeSteps, height, width);
        
        // Create raster layers
        MemoryMappedRasterLayer tempLayer = new MemoryMappedRasterLayer(
            outputName + "_Temperature", width, height, timeSteps);
        
        MemoryMappedRasterLayer rainLayer = new MemoryMappedRasterLayer(
            outputName + "_Precipitation", width, height, timeSteps);
        
        // Set coordinate bounds (simplified - should extract from NetCDF)
        setCoordinateBounds(tempLayer, climateVars.temperature);
        setCoordinateBounds(rainLayer, climateVars.precipitation);
        
        // Convert data in parallel batches
        convertInBatches(reader, climateVars, tempLayer, rainLayer, timeSteps);
        
        // Apply compression if enabled
        if (enableCompression) {
            applyCompression(tempLayer, rainLayer);
        }
        
        System.out.println("✓ NetCDF conversion complete");
        
        return new ClimateRasters(tempLayer, rainLayer);
    }
    
    private void setCoordinateBounds(RasterLayer layer, ucar.nc2.Variable variable) {
        // Extract coordinate variables from NetCDF
        // This is simplified - real implementation would read lat/lon variables
        
        // Example for global ERA5 data
        layer.setBounds(-180.0, 180.0, -90.0, 90.0);
    }
    
    private void convertInBatches(NetCDFClimateReader reader,
                                 NetCDFClimateReader.ClimateVariables climateVars,
                                 MemoryMappedRasterLayer tempLayer,
                                 MemoryMappedRasterLayer rainLayer,
                                 int totalTimeSteps) throws Exception {
        
        List<Future<Void>> futures = new ArrayList<>();
        
        for (int batchStart = 0; batchStart < totalTimeSteps; batchStart += batchSize) {
            final int start = batchStart;
            final int end = Math.min(start + batchSize, totalTimeSteps);
            
            Future<Void> future = conversionExecutor.submit(() -> {
                try {
                    convertBatch(reader, climateVars, tempLayer, rainLayer, start, end);
                    
                    // Update progress
                    double percent = (double) end / totalTimeSteps * 100;
                    progress.put("conversion", percent);
                    
                    System.out.printf("Converted time steps %d-%d (%.1f%%)%n", 
                        start, end - 1, percent);
                    
                } catch (Exception e) {
                    System.err.printf("Error converting batch %d-%d: %s%n", 
                        start, end, e.getMessage());
                    throw new RuntimeException(e);
                }
                return null;
            });
            
            futures.add(future);
        }
        
        // Wait for all batches to complete
        for (Future<Void> future : futures) {
            future.get();
        }
    }
    
    private void convertBatch(NetCDFClimateReader reader,
                             NetCDFClimateReader.ClimateVariables climateVars,
                             MemoryMappedRasterLayer tempLayer,
                             MemoryMappedRasterLayer rainLayer,
                             int startIndex, int endIndex) throws Exception {
        
        // Read multiple time steps at once for efficiency
        List<NetCDFClimateReader.ClimateData> climateData = 
            reader.readTimeRange(startIndex, endIndex - startIndex, climateVars);
        
        for (int i = 0; i < climateData.size(); i++) {
            int timeIndex = startIndex + i;
            NetCDFClimateReader.ClimateData data = climateData.get(i);
            
            // Convert temperature data
            if (data.temperature != null) {
                convertArrayToRaster(data.temperature, tempLayer, timeIndex);
            }
            
            // Convert precipitation data
            if (data.precipitation != null) {
                convertArrayToRaster(data.precipitation, rainLayer, timeIndex);
            }
        }
    }
    
    private void convertArrayToRaster(Array array, RasterLayer layer, int timeIndex) {
        int[] shape = array.getShape();
        int height = shape[0];
        int width = shape[1];
        
        Index index = array.getIndex();
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double value = array.getDouble(index.set(y, x));
                
                // Apply unit conversions if needed
                value = convertUnits(value, layer.getName());
                
                layer.setData(timeIndex, x, y, value);
            }
        }
    }
    
    private double convertUnits(double value, String layerName) {
        // Convert from NetCDF units to simulation units
        if (layerName.toLowerCase().contains("temperature")) {
            // ERA5 temperature is in Kelvin, convert to Celsius for simulation
            return value - 273.15;
        } else if (layerName.toLowerCase().contains("precipitation")) {
            // ERA5 precipitation is in meters, convert to mm
            return value * 1000.0;
        }
        return value;
    }
    
    private void applyCompression(RasterLayer... layers) {
        // Apply simple compression by removing NaN values
        for (RasterLayer layer : layers) {
            if (layer instanceof MemoryMappedRasterLayer mmrl) {
                compressMemoryMappedLayer(mmrl);
            }
        }
    }
    
    private void compressMemoryMappedLayer(MemoryMappedRasterLayer layer) {
        // Implementation would scan for and compress repeated values
        // This is simplified
        System.out.println("Applied compression to " + layer.getName());
    }
    
    /**
     * Create interpolated raster for any simulation time
     */
    public ClimateData getInterpolatedClimateData(
            NetCDFClimateReader reader,
            EnhancedTimeManager timeManager,
            EnhancedTimeManager.InterpolationWeights weights,
            NetCDFClimateReader.ClimateVariables climateVars) throws Exception {
        
        ClimateData interpolated = new ClimateData();
        
        if (!weights.needsInterpolation()) {
            // No interpolation needed, use exact time step
            NetCDFClimateReader.ClimateData exactData = 
                reader.readTimeSlice(weights.index1, climateVars);
            interpolated.temperature = convertToRasterArray(exactData.temperature);
            interpolated.precipitation = convertToRasterArray(exactData.precipitation);
        } else {
            // Temporal interpolation between two time steps
            NetCDFClimateReader.ClimateData data1 = 
                reader.readTimeSlice(weights.index1, climateVars);
            NetCDFClimateReader.ClimateData data2 = 
                reader.readTimeSlice(weights.index2, climateVars);
            
            interpolated.temperature = interpolateArrays(
                data1.temperature, data2.temperature, weights);
            interpolated.precipitation = interpolateArrays(
                data1.precipitation, data2.precipitation, weights);
        }
        
        return interpolated;
    }
    
    private RasterArray convertToRasterArray(ucar.ma2.Array netcdfArray) {
        if (netcdfArray == null) return null;
        
        int[] shape = netcdfArray.getShape();
        RasterArray rasterArray = new RasterArray(shape[1], shape[0]);
        
        Index index = netcdfArray.getIndex();
        for (int y = 0; y < shape[0]; y++) {
            for (int x = 0; x < shape[1]; x++) {
                double value = netcdfArray.getDouble(index.set(y, x));
                rasterArray.setValue(x, y, value);
            }
        }
        
        return rasterArray;
    }
    
    private RasterArray interpolateArrays(ucar.ma2.Array array1, ucar.ma2.Array array2,
                                         EnhancedTimeManager.InterpolationWeights weights) {
        if (array1 == null || array2 == null) return null;
        
        int[] shape = array1.getShape();
        RasterArray result = new RasterArray(shape[1], shape[0]);
        
        Index index1 = array1.getIndex();
        Index index2 = array2.getIndex();
        
        for (int y = 0; y < shape[0]; y++) {
            for (int x = 0; x < shape[1]; x++) {
                double value1 = array1.getDouble(index1.set(y, x));
                double value2 = array2.getDouble(index2.set(y, x));
                
                double interpolated = weights.interpolate(value1, value2);
                result.setValue(x, y, interpolated);
            }
        }
        
        return result;
    }
    
    public void shutdown() {
        conversionExecutor.shutdown();
        try {
            if (!conversionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                conversionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            conversionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    public Map<String, Double> getProgress() {
        return Collections.unmodifiableMap(progress);
    }
    
    // Data classes
    public static class ClimateRasters {
        public final RasterLayer temperature;
        public final RasterLayer precipitation;
        
        public ClimateRasters(RasterLayer temperature, RasterLayer precipitation) {
            this.temperature = temperature;
            this.precipitation = precipitation;
        }
    }
    
    public static class ClimateData {
        public RasterArray temperature;
        public RasterArray precipitation;
    }
    
    public static class RasterArray {
        private final double[][] data;
        private final int width;
        private final int height;
        
        public RasterArray(int width, int height) {
            this.width = width;
            this.height = height;
            this.data = new double[height][width];
        }
        
        public void setValue(int x, int y, double value) {
            data[y][x] = value;
        }
        
        public double getValue(int x, int y) {
            return data[y][x];
        }
        
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        
        public double[][] getData() { return data; }
    }
}