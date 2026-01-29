package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;
import ucar.ma2.Array;
import ucar.ma2.Index;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFiles;
import ucar.nc2.Variable;
import ucar.nc2.Dimension;
import ucar.nc2.Attribute;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;


/**
 * Time-Varying Raster
 *
 * Extends RasterLayer for climate/time-series data
 *
 * Loads NetCDF climate data with temporal interpolation
 *
 * Implements bilinear interpolation for smooth value retrieval
 *
 * Caches interpolated values for performance
 * 
 * 
 * @author void
 */


public class InterpolatedRasterLayer extends Layer {
    private static final Logger LOGGER = Logger.getLogger(InterpolatedRasterLayer.class.getName());
    
    // Core data structures
    private double[][][] dataGrid; // [time][lat][lon] or [time][lon][lat]
    private double[] timeValues; // Time values in hours since reference
    private double[] latValues; // Latitude values (degrees north)
    private double[] lonValues; // Longitude values (degrees east)
    
    // Dimensions
    private int timeSize, latSize, lonSize;
    private final TimeManager timeManager;
    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();
    
    // Interpolation cache for performance
    private double[][] cachedGrid; // Current interpolated grid
    private LocalDateTime lastCacheTime;
    private boolean cacheValid = false;
    
    // NetCDF metadata
    private String timeUnits = "seconds since 1970-01-01";
    private LocalDateTime timeReference = LocalDateTime.of(1970, 1, 1, 0, 0);
    private boolean dataLoaded = false;
    
    // Statistics
    private long cacheHits = 0;
    private long cacheMisses = 0;
    private long interpolationsPerformed = 0;
    
    // Variable name mapping
    private String timeDimName = "valid_time";
    private String latDimName = "latitude";
    private String lonDimName = "longitude";
    
    /**
     * Constructor for creating an empty layer to be loaded from NetCDF
     */
    public InterpolatedRasterLayer(String name, TimeManager timeManager) {
        super(name);
        this.timeManager = timeManager;
        this.cachedGrid = null;
        this.lastCacheTime = null;
    }
    
    
    /**
     * Load data from a NetCDF file
     * @param filePath Path to NetCDF file
     * @param variableName Variable to load (e.g., "t2m", "tp", "rh")
     * @throws IOException If file cannot be read
     */
    public void loadFromNetCDF(String filePath, String variableName) throws IOException {
        dataLock.writeLock().lock();
        try (NetcdfFile ncfile = NetcdfFiles.open(filePath)) {
            LOGGER.info("Loading NetCDF file: " + filePath);
            
            // Debug: Print file structure
            printFileStructure(ncfile);
            
            // Get the variable
            Variable var = ncfile.findVariable(variableName);
            if (var == null) {
                throw new IOException("Variable '" + variableName + "' not found in file. Available variables: " + 
                    getVariableNames(ncfile));
            }
            
            // Get dimensions - using your file's dimension names
            Dimension timeDim = ncfile.findDimension(timeDimName);
            Dimension latDim = ncfile.findDimension(latDimName);
            Dimension lonDim = ncfile.findDimension(lonDimName);
            
            if (timeDim == null || latDim == null || lonDim == null) {
                throw new IOException("Could not find required dimensions. Found dimensions: " + 
                    getDimensionNames(ncfile) + "\nLooking for: " + timeDimName + ", " + latDimName + ", " + lonDimName);
            }
            
            timeSize = timeDim.getLength();
            latSize = latDim.getLength();
            lonSize = lonDim.getLength();
            
            LOGGER.info(String.format("Dimensions: %s=%d, %s=%d, %s=%d", 
                timeDimName, timeSize, latDimName, latSize, lonDimName, lonSize));
            
            // Allocate data grid (time, lat, lon)
            dataGrid = new double[timeSize][latSize][lonSize];
            
            // Read coordinate variables
            readCoordinateVariables(ncfile);
            
            // Read time variable and units
            readTimeMetadata(ncfile);
            
            // Read the data
            LOGGER.info("Reading variable data for: " + variableName);
            Array dataArray = var.read();
            Index index = dataArray.getIndex();
            
            // Check dimension order
            List<Dimension> varDims = var.getDimensions();
            if (varDims.size() != 3) {
                throw new IOException("Expected 3 dimensions, found " + varDims.size());
            }
            
            // Determine dimension order for your specific file
            // Based on your ncdump: (valid_time, latitude, longitude)
            int timeIndex = -1, latIndex = -1, lonIndex = -1;
            for (int i = 0; i < varDims.size(); i++) {
                String dimName = varDims.get(i).getShortName();
                if (dimName.equals(timeDimName)) {
                    timeIndex = i;
                } else if (dimName.equals(latDimName)) {
                    latIndex = i;
                } else if (dimName.equals(lonDimName)) {
                    lonIndex = i;
                }
            }
            
            if (timeIndex == -1 || latIndex == -1 || lonIndex == -1) {
                throw new IOException("Could not identify dimension order in variable");
            }
            
            LOGGER.info(String.format("Dimension order: time[%d], lat[%d], lon[%d]", 
                timeIndex, latIndex, lonIndex));
            
            // Fill data grid with proper dimension order
            for (int t = 0; t < timeSize; t++) {
                for (int lat = 0; lat < latSize; lat++) {
                    for (int lon = 0; lon < lonSize; lon++) {
                        // Set indices according to dimension order
                        if (timeIndex == 0 && latIndex == 1 && lonIndex == 2) {
                            // (time, lat, lon)
                            dataGrid[t][lat][lon] = dataArray.getDouble(index.set(t, lat, lon));
                        } else if (timeIndex == 0 && lonIndex == 1 && latIndex == 2) {
                            // (time, lon, lat)
                            dataGrid[t][lat][lon] = dataArray.getDouble(index.set(t, lon, lat));
                        } else {
                            // Fallback: assume (time, lat, lon)
                            dataGrid[t][lat][lon] = dataArray.getDouble(index.set(t, lat, lon));
                        }
                    }
                }
            }
            
            // Check if latitude is descending (your file has stored_direction = "decreasing")
            if (latValues.length > 1 && latValues[0] > latValues[1]) {
                LOGGER.info("Reversing latitude axis (north to south -> south to north)");
                reverseLatitudeData();
            }
            
            dataLoaded = true;
            cacheValid = false;
            
            LOGGER.info(String.format("Successfully loaded %s: %d time steps, %.2f° to %.2f° lat, %.2f° to %.2f° lon",
                variableName, timeSize, latValues[0], latValues[latValues.length-1],
                lonValues[0], lonValues[lonValues.length-1]));
                
        } finally {
            dataLock.writeLock().unlock();
        }
    }
    
    
    private void printFileStructure(NetcdfFile ncfile) {
        System.out.println("\n=== NetCDF File Structure ===");
        System.out.println("Dimensions:");
        for (Dimension dim : ncfile.getDimensions()) {
            System.out.printf("  %s: %d%n", dim.getShortName(), dim.getLength());
        }
        
        System.out.println("\nVariables:");
        for (Variable var : ncfile.getVariables()) {
            System.out.printf("  %s: ", var.getShortName());
            System.out.print("Dimensions [");
            for (Dimension dim : var.getDimensions()) {
                System.out.print(dim.getShortName() + " ");
            }
            System.out.println("]");
            
            // Print units if available
            Attribute units = var.findAttribute("units");
            if (units != null) {
                System.out.printf("    units: %s%n", units.getStringValue());
            }
        }
    }
    
    
    private String getVariableNames(NetcdfFile ncfile) {
        StringBuilder sb = new StringBuilder();
        for (Variable var : ncfile.getVariables()) {
            sb.append(var.getShortName()).append(", ");
        }
        return sb.toString();
    }
    
    
    private String getDimensionNames(NetcdfFile ncfile) {
        StringBuilder sb = new StringBuilder();
        for (Dimension dim : ncfile.getDimensions()) {
            sb.append(dim.getShortName()).append(" (").append(dim.getLength()).append("), ");
        }
        return sb.toString();
    }
    
    
    private void readCoordinateVariables(NetcdfFile ncfile) throws IOException {
        // Read latitude values
        Variable latVar = ncfile.findVariable(latDimName);
        if (latVar == null) {
            // Try alternative names
            latVar = ncfile.findVariable("lat");
        }
        
        if (latVar != null) {
            Array latArray = latVar.read();
            latValues = new double[latSize];
            for (int i = 0; i < latSize; i++) {
                latValues[i] = latArray.getDouble(i);
            }
            LOGGER.info(String.format("Latitude range: %.3f to %.3f", 
                latValues[0], latValues[latValues.length-1]));
        } else {
            throw new IOException("Latitude variable not found");
        }
        
        // Read longitude values
        Variable lonVar = ncfile.findVariable(lonDimName);
        if (lonVar == null) {
            // Try alternative names
            lonVar = ncfile.findVariable("lon");
        }
        
        if (lonVar != null) {
            Array lonArray = lonVar.read();
            lonValues = new double[lonSize];
            for (int i = 0; i < lonSize; i++) {
                lonValues[i] = lonArray.getDouble(i);
            }
            LOGGER.info(String.format("Longitude range: %.3f to %.3f", 
                lonValues[0], lonValues[lonValues.length-1]));
        } else {
            throw new IOException("Longitude variable not found");
        }
    }
    
    
    private void readTimeMetadata(NetcdfFile ncfile) throws IOException {
        Variable timeVar = ncfile.findVariable(timeDimName);
        if (timeVar == null) {
            LOGGER.warning("Time variable not found, using simulation time directly");
            timeValues = new double[timeSize];
            for (int i = 0; i < timeSize; i++) {
                timeValues[i] = i * 3600; // Assume hourly data in seconds
            }
            return;
        }
        
        // Read time units
        Attribute unitsAttr = timeVar.findAttribute("units");
        if (unitsAttr != null) {
            timeUnits = unitsAttr.getStringValue();
            parseTimeUnits(timeUnits);
            LOGGER.info("Time units: " + timeUnits);
        }
        
        // Read time values
        Array timeArray = timeVar.read();
        timeValues = new double[timeSize];
        for (int i = 0; i < timeSize; i++) {
            timeValues[i] = timeArray.getDouble(i);
        }
        
        LOGGER.info(String.format("Time range: %.0f to %.0f seconds (%d steps)",
            timeValues[0], timeValues[timeValues.length-1], timeSize));
        
        // Convert first and last times to human-readable
        LocalDateTime firstTime = timeReference.plusSeconds((long) timeValues[0]);
        LocalDateTime lastTime = timeReference.plusSeconds((long) timeValues[timeValues.length-1]);
        LOGGER.info(String.format("Time range: %s to %s", 
            firstTime.toString(), lastTime.toString()));
    }
    
    
    private void parseTimeUnits(String units) {
        // Parse units like "seconds since 1970-01-01 00:00:00" or "seconds since 1970-01-01"
        try {
            String[] parts = units.split("since");
            if (parts.length == 2) {
                String dateStr = parts[1].trim();
                // Handle different date formats
                if (dateStr.contains(" ")) {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                    timeReference = LocalDateTime.parse(dateStr, formatter);
                } else {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                    timeReference = LocalDateTime.parse(dateStr, formatter);
                }
                LOGGER.info("Time reference: " + timeReference);
            }
        } catch (Exception e) {
            LOGGER.warning("Could not parse time units: " + units + ", using 1970-01-01");
            timeReference = LocalDateTime.of(1970, 1, 1, 0, 0);
        }
    }
    
    
    private void reverseLatitudeData() {
        // Reverse latitude values
        double[] reversedLat = new double[latSize];
        for (int i = 0; i < latSize; i++) {
            reversedLat[i] = latValues[latSize - 1 - i];
        }
        latValues = reversedLat;
        
        // Reverse data along latitude dimension
        double[][][] reversedData = new double[timeSize][latSize][lonSize];
        for (int t = 0; t < timeSize; t++) {
            for (int lat = 0; lat < latSize; lat++) {
                for (int lon = 0; lon < lonSize; lon++) {
                    reversedData[t][lat][lon] = dataGrid[t][latSize - 1 - lat][lon];
                }
            }
        }
        dataGrid = reversedData;
    }
    
    @Override
    public double getValueAt(double lon, double lat) {
        if (!dataLoaded) {
            return Double.NaN;
        }
        
        // Check cache first
        LocalDateTime currentTime = timeManager.getCurrentDateTime();
        if (cacheValid && lastCacheTime != null && 
            lastCacheTime.equals(currentTime) && cachedGrid != null) {
            cacheHits++;
            return getCachedValue(lon, lat);
        }
        
        // Cache miss - need to interpolate
        cacheMisses++;
        interpolationsPerformed++;
        
        // Update cache
        updateCache(currentTime);
        
        return getCachedValue(lon, lat);
    }
    
    
    private void updateCache(LocalDateTime currentTime) {
        dataLock.readLock().lock();
        try {
            // Convert current time to seconds since reference
            long secondsSinceReference = currentTime.toEpochSecond(ZoneOffset.UTC) - 
                                        timeReference.toEpochSecond(ZoneOffset.UTC);
            
            // Find bounding time indices
            int lowerIdx = -1;
            int upperIdx = -1;
            double alpha = 0.0;
            
            for (int i = 0; i < timeValues.length - 1; i++) {
                if (secondsSinceReference >= timeValues[i] && 
                    secondsSinceReference <= timeValues[i + 1]) {
                    lowerIdx = i;
                    upperIdx = i + 1;
                    alpha = (secondsSinceReference - timeValues[i]) / 
                           (timeValues[i + 1] - timeValues[i]);
                    break;
                }
            }
            
            // Handle boundaries
            if (lowerIdx == -1) {
                if (secondsSinceReference <= timeValues[0]) {
                    lowerIdx = upperIdx = 0;
                    alpha = 0.0;
                } else {
                    lowerIdx = upperIdx = timeValues.length - 1;
                    alpha = 0.0;
                }
            }
            
            // Create or resize cache grid
            if (cachedGrid == null || cachedGrid.length != latSize || 
                cachedGrid[0].length != lonSize) {
                cachedGrid = new double[latSize][lonSize];
            }
            
            // Perform temporal interpolation
            if (lowerIdx == upperIdx || alpha == 0.0) {
                // No interpolation needed
                for (int lat = 0; lat < latSize; lat++) {
                    for (int lon = 0; lon < lonSize; lon++) {
                        cachedGrid[lat][lon] = dataGrid[lowerIdx][lat][lon];
                    }
                }
            } else {
                // Linear interpolation between time steps
                for (int lat = 0; lat < latSize; lat++) {
                    for (int lon = 0; lon < lonSize; lon++) {
                        double lowerValue = dataGrid[lowerIdx][lat][lon];
                        double upperValue = dataGrid[upperIdx][lat][lon];
                        cachedGrid[lat][lon] = lowerValue + 
                            (upperValue - lowerValue) * alpha;
                    }
                }
            }
            
            lastCacheTime = currentTime;
            cacheValid = true;
            
        } finally {
            dataLock.readLock().unlock();
        }
    }
    
    
    private int findNearestIndex(double[] array, double value) {
        if (array == null || array.length == 0) return -1;
        
        // Binary search for nearest value
        int low = 0;
        int high = array.length - 1;
        
        while (low <= high) {
            int mid = (low + high) / 2;
            if (array[mid] == value) {
                return mid;
            } else if (array[mid] < value) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        
        // Return nearest of low or high (clamped to array bounds)
        if (low >= array.length) return array.length - 1;
        if (high < 0) return 0;
        
        return Math.abs(array[low] - value) < Math.abs(array[high] - value) ? low : high;
    }
    
    
    private double getCachedValue(double lon, double lat) {
        // Find nearest grid cell (simple nearest neighbor)
        int latIdx = findNearestIndex(latValues, lat);
        int lonIdx = findNearestIndex(lonValues, lon);
        
        if (latIdx >= 0 && latIdx < latSize && lonIdx >= 0 && lonIdx < lonSize) {
            return cachedGrid[latIdx][lonIdx];
        }
        
        return Double.NaN;
    }
    
    
    private Dimension findDimension(NetcdfFile ncfile, String dimName) {
        // Try exact match first
        Dimension dim = ncfile.findDimension(dimName);
        if (dim != null) return dim;
        
        // Try case-insensitive
        for (Dimension d : ncfile.getDimensions()) {
            if (d.getShortName().equalsIgnoreCase(dimName)) {
                return d;
            }
        }
        
        // Try common alternatives
        if (dimName.equals("lat")) {
            return ncfile.findDimension("latitude");
        } else if (dimName.equals("lon")) {
            return ncfile.findDimension("longitude");
        }
        
        return null;
    }
    
   
    
    /**
     * Get bilinear interpolated value (more accurate but slower)
     */
    public double getValueAtBilinear(double lon, double lat) {
        if (!dataLoaded) return Double.NaN;
        
        // Update cache if needed
        LocalDateTime currentTime = timeManager.getCurrentDateTime();
        if (!cacheValid || !currentTime.equals(lastCacheTime)) {
            updateCache(currentTime);
        }
        
        // Find surrounding grid cells
        int latIdx1 = findLowerIndex(latValues, lat);
        int lonIdx1 = findLowerIndex(lonValues, lon);
        
        if (latIdx1 == -1 || lonIdx1 == -1 || 
            latIdx1 >= latSize - 1 || lonIdx1 >= lonSize - 1) {
            return getCachedValue(lon, lat); // Fall back to nearest neighbor
        }
        
        int latIdx2 = latIdx1 + 1;
        int lonIdx2 = lonIdx1 + 1;
        
        // Get the four surrounding values
        double q11 = cachedGrid[latIdx1][lonIdx1];
        double q12 = cachedGrid[latIdx1][lonIdx2];
        double q21 = cachedGrid[latIdx2][lonIdx1];
        double q22 = cachedGrid[latIdx2][lonIdx2];
        
        // Calculate interpolation weights
        double lat1 = latValues[latIdx1];
        double lat2 = latValues[latIdx2];
        double lon1 = lonValues[lonIdx1];
        double lon2 = lonValues[lonIdx2];
        
        double x = (lon - lon1) / (lon2 - lon1);
        double y = (lat - lat1) / (lat2 - lat1);
        
        // Bilinear interpolation formula
        double value = q11 * (1 - x) * (1 - y) +
                      q21 * x * (1 - y) +
                      q12 * (1 - x) * y +
                      q22 * x * y;
        
        return value;
    }
    
    private int findLowerIndex(double[] array, double value) {
        if (array == null || array.length < 2) return -1;
        
        for (int i = 0; i < array.length - 1; i++) {
            if (value >= array[i] && value <= array[i + 1]) {
                return i;
            }
        }
        
        return -1;
    }
    
    @Override
    public void update(Project project) {
        // Invalidate cache when time changes
        LocalDateTime currentTime = timeManager.getCurrentDateTime();
        if (lastCacheTime == null || !lastCacheTime.equals(currentTime)) {
            cacheValid = false;
        }
    }
    
    /**
     * Get layer statistics
     */
    public ClimateLayerStats getStatistics() {
        return new ClimateLayerStats(
            getName(),
            dataLoaded,
            timeSize,
            latSize,
            lonSize,
            cacheHits,
            cacheMisses,
            interpolationsPerformed,
            getCacheHitRatio()
        );
    }
    
    /**
     * Print detailed statistics
     */
    public void printStatistics() {
        ClimateLayerStats stats = getStatistics();
        System.out.println(stats.toString());
    }
    
    private double getCacheHitRatio() {
        long total = cacheHits + cacheMisses;
        return total > 0 ? (double) cacheHits / total : 0.0;
    }
    
    // Getters for metadata
    public boolean isDataLoaded() { return dataLoaded; }
    public int getTimeSize() { return timeSize; }
    public int getLatSize() { return latSize; }
    public int getLonSize() { return lonSize; }
    public double getMinLat() { return latValues != null && latValues.length > 0 ? latValues[0] : Double.NaN; }
    public double getMaxLat() { return latValues != null && latValues.length > 0 ? latValues[latValues.length-1] : Double.NaN; }
    public double getMinLon() { return lonValues != null && lonValues.length > 0 ? lonValues[0] : Double.NaN; }
    public double getMaxLon() { return lonValues != null && lonValues.length > 0 ? lonValues[lonValues.length-1] : Double.NaN; }
    public double[] getTimeValues() { return timeValues; }
    public double[] getLatValues() { return latValues; }
    public double[] getLonValues() { return lonValues; }
    
    /**
     * Statistics container class
     */
    public static class ClimateLayerStats {
        private final String layerName;
        private final boolean dataLoaded;
        private final int timeSteps;
        private final int latPoints;
        private final int lonPoints;
        private final long cacheHits;
        private final long cacheMisses;
        private final long totalInterpolations;
        private final double cacheHitRatio;
        
        public ClimateLayerStats(String layerName, boolean dataLoaded, 
                                int timeSteps, int latPoints, int lonPoints,
                                long cacheHits, long cacheMisses,
                                long totalInterpolations, double cacheHitRatio) {
            this.layerName = layerName;
            this.dataLoaded = dataLoaded;
            this.timeSteps = timeSteps;
            this.latPoints = latPoints;
            this.lonPoints = lonPoints;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.totalInterpolations = totalInterpolations;
            this.cacheHitRatio = cacheHitRatio;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Climate Layer '%s':\n" +
                "  Data loaded: %b\n" +
                "  Dimensions: %d time steps, %d×%d grid\n" +
                "  Cache: %d hits, %d misses (%.1f%% hit rate)\n" +
                "  Interpolations performed: %d",
                layerName, dataLoaded, timeSteps, latPoints, lonPoints,
                cacheHits, cacheMisses, cacheHitRatio * 100, totalInterpolations
            );
        }
    }
}