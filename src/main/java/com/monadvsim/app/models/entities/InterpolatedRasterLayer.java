package com.monadvsim.app.models.entities;
import com.monadvsim.app.models.utils.SimulationLogger;

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
 */
public class InterpolatedRasterLayer extends Layer {
    private static final Logger LOGGER = Logger.getLogger(InterpolatedRasterLayer.class.getName());
    
    // Core data structures
    private double[][][] dataGrid; // [time][lat][lon]
    private double[] timeValues;   // Time values in seconds since reference
    private double[] latValues;    // Latitude values (degrees north)
    private double[] lonValues;    // Longitude values (degrees east)
    
    // Dimensions
    private int timeSize, latSize, lonSize;
    private final TimeManager timeManager;
    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();
    
    // Interpolation cache
    private double[][] cachedGrid;
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
    
    // Auto‑detected dimension names
    private String timeDimName = "time";
    private String latDimName = "latitude";
    private String lonDimName = "longitude";
    private String stepDimName = "step";
    
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
     * @param variableName Variable to load (e.g., "t2m", "tp")
     * @throws IOException If file cannot be read
     */
    public void loadFromNetCDF(String filePath, String variableName) throws IOException {
        dataLock.writeLock().lock();
        try (NetcdfFile ncfile = NetcdfFiles.open(filePath)) {
            LOGGER.info("Loading NetCDF file: " + filePath);
            printFileStructure(ncfile);
            
            // Auto‑detect dimension names from the file
            detectDimensions(ncfile);
            
            // Get the variable
            Variable var = ncfile.findVariable(variableName);
            if (var == null) {
                throw new IOException("Variable '" + variableName + "' not found. Available: " + 
                    getVariableNames(ncfile));
            }
            
            // Get dimensions
            Dimension timeDim = ncfile.findDimension(timeDimName);
            Dimension latDim = ncfile.findDimension(latDimName);
            Dimension lonDim = ncfile.findDimension(lonDimName);
            if (timeDim == null || latDim == null || lonDim == null) {
                throw new IOException("Missing required dimensions. Found: " + getDimensionNames(ncfile));
            }
            
            timeSize = timeDim.getLength();
            latSize = latDim.getLength();
            lonSize = lonDim.getLength();
            
            // Check if variable has a step dimension (4D)
            boolean hasStep = false;
            int stepSize = 1;
            for (Dimension d : var.getDimensions()) {
                if (d.getShortName().equals(stepDimName)) {
                    hasStep = true;
                    stepSize = d.getLength();
                    break;
                }
            }
            
            LOGGER.info(String.format("Dimensions: %s=%d, %s=%d, %s=%d%s",
                timeDimName, timeSize, latDimName, latSize, lonDimName, lonSize,
                hasStep ? ", step=" + stepSize : ""));
            
            // Allocate data grid
            dataGrid = new double[timeSize][latSize][lonSize];
            
            // Read coordinate variables
            readCoordinateVariables(ncfile);
            readTimeMetadata(ncfile);
            
            // Read the variable data (handles 3D and 4D, step reduction)
            readVariableData(var, ncfile, hasStep, stepSize);
            
            // Ensure latitude is increasing (south to north)
            if (latValues.length > 1 && latValues[0] > latValues[1]) {
                LOGGER.info("Reversing latitude axis (north to south -> south to north)");
                reverseLatitudeData();
            }
            
            // --- HEURISTIC FIX FOR TEMPERATURE UNITS ---
            // If this is a temperature variable and the average raw value is below 200 K,
            // assume it was stored in Celsius and convert to Kelvin.
            if (variableName.equalsIgnoreCase("t2m") || variableName.equalsIgnoreCase("temperature")) {
                double sum = 0.0;
                int count = 0;
                for (int t = 0; t < Math.min(timeSize, 3); t++) {
                    for (int lat = 0; lat < Math.min(latSize, 5); lat++) {
                        for (int lon = 0; lon < Math.min(lonSize, 5); lon++) {
                            double val = dataGrid[t][lat][lon];
                            if (!Double.isNaN(val)) {
                                sum += val;
                                count++;
                            }
                        }
                    }
                }
                if (count > 0) {
                    double avg = sum / count;
                    if (avg < 200) {
                        LOGGER.warning(String.format("Temperature average (%.2f) is below 200 K – assuming data is in Celsius and converting to Kelvin.", avg));
                        for (int t = 0; t < timeSize; t++) {
                            for (int lat = 0; lat < latSize; lat++) {
                                for (int lon = 0; lon < lonSize; lon++) {
                                    if (!Double.isNaN(dataGrid[t][lat][lon])) {
                                        dataGrid[t][lat][lon] += 273.15;
                                    }
                                }
                            }
                        }
                    } else {
                        LOGGER.info(String.format("Temperature average (%.2f K) is within expected range.", avg));
                    }
                }
            }
            
            // Debug: print first few values of the loaded data
            LOGGER.info("First few loaded values (time=0, lat=0, lon=0..2):");
            for (int lon = 0; lon < Math.min(3, lonSize); lon++) {
                LOGGER.info(String.format("  [%d] = %.4f", lon, dataGrid[0][0][lon]));
            }
            
            dataLoaded = true;
            cacheValid = false;
            LOGGER.info(String.format("Successfully loaded %s: %d time steps, [%.2f..%.2f]° lat, [%.2f..%.2f]° lon",
                variableName, timeSize, latValues[0], latValues[latValues.length-1],
                lonValues[0], lonValues[lonValues.length-1]));
        } finally {
            dataLock.writeLock().unlock();
        }
    }
    
    private void detectDimensions(NetcdfFile ncfile) {
        for (Dimension dim : ncfile.getDimensions()) {
            String name = dim.getShortName();
            if (name.equals("time") || name.equals("valid_time") || name.equals("Time")) timeDimName = name;
            if (name.equals("lat") || name.equals("latitude") || name.equals("Lat")) latDimName = name;
            if (name.equals("lon") || name.equals("longitude") || name.equals("Lon")) lonDimName = name;
            if (name.equals("step")) stepDimName = name;
        }
    }
    
    private void readVariableData(Variable var, NetcdfFile ncfile, boolean hasStep, int stepSize) throws IOException {
        Array dataArray = var.read();
        Index index = dataArray.getIndex();
        
        // Determine dimension order
        List<Dimension> varDims = var.getDimensions();
        int timeIdx = -1, latIdx = -1, lonIdx = -1, stepIdx = -1;
        for (int i = 0; i < varDims.size(); i++) {
            String d = varDims.get(i).getShortName();
            if (d.equals(timeDimName)) timeIdx = i;
            else if (d.equals(latDimName)) latIdx = i;
            else if (d.equals(lonDimName)) lonIdx = i;
            else if (d.equals(stepDimName)) stepIdx = i;
        }
        if (timeIdx == -1 || latIdx == -1 || lonIdx == -1) {
            throw new IOException("Cannot identify dimension order in variable " + var.getShortName());
        }
        
        // Read scale_factor, add_offset, _FillValue
        double scale = 1.0, offset = 0.0;
        double fillValue = Double.NaN;
        Attribute scaleAttr = var.findAttribute("scale_factor");
        if (scaleAttr != null) scale = scaleAttr.getNumericValue().doubleValue();
        Attribute offAttr = var.findAttribute("add_offset");
        if (offAttr != null) offset = offAttr.getNumericValue().doubleValue();
        Attribute fillAttr = var.findAttribute("_FillValue");
        if (fillAttr != null) fillValue = fillAttr.getNumericValue().doubleValue();
        
        // Fill data grid, reducing step dimension if present (sum across steps)
        for (int t = 0; t < timeSize; t++) {
            for (int lat = 0; lat < latSize; lat++) {
                for (int lon = 0; lon < lonSize; lon++) {
                    double sum = 0.0;
                    int count = 0;
                    for (int step = 0; step < stepSize; step++) {
                        double raw;
                        if (!hasStep) {
                            // 3D variable
                            if (timeIdx == 0 && latIdx == 1 && lonIdx == 2) {
                                raw = dataArray.getDouble(index.set(t, lat, lon));
                            } else if (timeIdx == 0 && lonIdx == 1 && latIdx == 2) {
                                raw = dataArray.getDouble(index.set(t, lon, lat));
                            } else {
                                raw = dataArray.getDouble(index.set(t, lat, lon));
                            }
                        } else {
                            // 4D variable (time, step, lat, lon) or (time, step, lon, lat)
                            if (timeIdx == 0 && stepIdx == 1 && latIdx == 2 && lonIdx == 3) {
                                raw = dataArray.getDouble(index.set(t, step, lat, lon));
                            } else if (timeIdx == 0 && stepIdx == 1 && lonIdx == 2 && latIdx == 3) {
                                raw = dataArray.getDouble(index.set(t, step, lon, lat));
                            } else {
                                raw = dataArray.getDouble(index.set(t, step, lat, lon));
                            }
                        }
                        double val = raw * scale + offset;
                        if (!Double.isNaN(fillValue) && Math.abs(raw - fillValue) < 1e-6) {
                            val = Double.NaN;
                        }
                        if (!Double.isNaN(val)) {
                            sum += val;
                            count++;
                        }
                    }
                    double finalVal = (count > 0) ? sum : Double.NaN;
                    dataGrid[t][lat][lon] = finalVal;
                }
            }
        }
        if (hasStep) {
            LOGGER.info("Reduced step dimension by summing across " + stepSize + " steps.");
        }
    }
    
    private void readCoordinateVariables(NetcdfFile ncfile) throws IOException {
        Variable latVar = ncfile.findVariable(latDimName);
        if (latVar == null) latVar = ncfile.findVariable("lat");
        if (latVar != null) {
            Array latArray = latVar.read();
            latValues = new double[latSize];
            for (int i = 0; i < latSize; i++) latValues[i] = latArray.getDouble(i);
            LOGGER.info(String.format("Latitude range: %.3f to %.3f", latValues[0], latValues[latValues.length-1]));
        } else {
            throw new IOException("Latitude variable not found");
        }
        
        Variable lonVar = ncfile.findVariable(lonDimName);
        if (lonVar == null) lonVar = ncfile.findVariable("lon");
        if (lonVar != null) {
            Array lonArray = lonVar.read();
            lonValues = new double[lonSize];
            for (int i = 0; i < lonSize; i++) lonValues[i] = lonArray.getDouble(i);
            LOGGER.info(String.format("Longitude range: %.3f to %.3f", lonValues[0], lonValues[lonValues.length-1]));
        } else {
            throw new IOException("Longitude variable not found");
        }
    }
    
    private void readTimeMetadata(NetcdfFile ncfile) throws IOException {
        Variable timeVar = ncfile.findVariable(timeDimName);
        if (timeVar == null) {
            LOGGER.warning("Time variable not found, using simulation time directly");
            timeValues = new double[timeSize];
            for (int i = 0; i < timeSize; i++) timeValues[i] = i * 3600;
            return;
        }
        Attribute unitsAttr = timeVar.findAttribute("units");
        if (unitsAttr != null) {
            timeUnits = unitsAttr.getStringValue();
            parseTimeUnits(timeUnits);
            LOGGER.info("Time units: " + timeUnits);
        }
        Array timeArray = timeVar.read();
        timeValues = new double[timeSize];
        for (int i = 0; i < timeSize; i++) {
            double raw = timeArray.getDouble(i);
            // Convert to seconds if units are days
            if (timeUnits.startsWith("days")) {
                timeValues[i] = raw * 24 * 3600;
            } else {
                timeValues[i] = raw;
            }
        }
        LOGGER.info(String.format("Time range: %.0f to %.0f seconds", timeValues[0], timeValues[timeValues.length-1]));
    }
    
    private void parseTimeUnits(String units) {
        try {
            String[] parts = units.split("since");
            if (parts.length == 2) {
                String dateStr = parts[1].trim();
                if (dateStr.contains(" ")) {
                    timeReference = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else {
                    timeReference = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                }
                LOGGER.info("Time reference: " + timeReference);
            }
        } catch (Exception e) {
            LOGGER.warning("Could not parse time units: " + units + ", using 1970-01-01");
            timeReference = LocalDateTime.of(1970, 1, 1, 0, 0);
        }
    }
    
    private void reverseLatitudeData() {
        double[] revLat = new double[latSize];
        for (int i = 0; i < latSize; i++) revLat[i] = latValues[latSize - 1 - i];
        latValues = revLat;
        double[][][] revData = new double[timeSize][latSize][lonSize];
        for (int t = 0; t < timeSize; t++)
            for (int lat = 0; lat < latSize; lat++)
                System.arraycopy(dataGrid[t][latSize - 1 - lat], 0, revData[t][lat], 0, lonSize);
        dataGrid = revData;
    }
    
    @Override
    public double getValueAt(double lon, double lat) {
        if (!dataLoaded) return Double.NaN;
        LocalDateTime currentTime = timeManager.getCurrentDateTime();
        if (cacheValid && lastCacheTime != null && lastCacheTime.equals(currentTime) && cachedGrid != null) {
            cacheHits++;
            return getCachedValue(lon, lat);
        }
        cacheMisses++;
        interpolationsPerformed++;
        updateCache(currentTime);
        return getCachedValue(lon, lat);
    }
    
    private void updateCache(LocalDateTime currentTime) {
        dataLock.readLock().lock();
        try {
            long seconds = currentTime.toEpochSecond(ZoneOffset.UTC) - timeReference.toEpochSecond(ZoneOffset.UTC);
            int lower = -1, upper = -1;
            double alpha = 0.0;
            for (int i = 0; i < timeValues.length - 1; i++) {
                if (seconds >= timeValues[i] && seconds <= timeValues[i+1]) {
                    lower = i; upper = i+1;
                    alpha = (seconds - timeValues[i]) / (timeValues[i+1] - timeValues[i]);
                    break;
                }
            }
            if (lower == -1) {
                if (seconds <= timeValues[0]) { lower = upper = 0; alpha = 0.0; }
                else { lower = upper = timeValues.length - 1; alpha = 0.0; }
            }
            if (cachedGrid == null || cachedGrid.length != latSize || cachedGrid[0].length != lonSize)
                cachedGrid = new double[latSize][lonSize];
            if (lower == upper || alpha == 0.0) {
                for (int lat = 0; lat < latSize; lat++)
                    System.arraycopy(dataGrid[lower][lat], 0, cachedGrid[lat], 0, lonSize);
            } else {
                for (int lat = 0; lat < latSize; lat++)
                    for (int lon = 0; lon < lonSize; lon++)
                        cachedGrid[lat][lon] = dataGrid[lower][lat][lon] + alpha * (dataGrid[upper][lat][lon] - dataGrid[lower][lat][lon]);
            }
            lastCacheTime = currentTime;
            cacheValid = true;
        } finally {
            dataLock.readLock().unlock();
        }
    }
    
    private int findNearestIndex(double[] array, double value) {
        if (array == null || array.length == 0) return -1;
        int low = 0, high = array.length - 1;
        while (low <= high) {
            int mid = (low + high) / 2;
            if (array[mid] == value) return mid;
            else if (array[mid] < value) low = mid + 1;
            else high = mid - 1;
        }
        if (low >= array.length) return array.length - 1;
        if (high < 0) return 0;
        return Math.abs(array[low] - value) < Math.abs(array[high] - value) ? low : high;
    }
    
    private double getCachedValue(double lon, double lat) {
        int latIdx = findNearestIndex(latValues, lat);
        int lonIdx = findNearestIndex(lonValues, lon);
        if (latIdx >= 0 && latIdx < latSize && lonIdx >= 0 && lonIdx < lonSize)
            return cachedGrid[latIdx][lonIdx];
        return Double.NaN;
    }
    
    private void printFileStructure(NetcdfFile ncfile) {
        SimulationLogger.info("\n=== NetCDF File Structure ===");
        SimulationLogger.info("Dimensions:");
        for (Dimension dim : ncfile.getDimensions())
            SimulationLogger.info("  %s: %d%n", dim.getShortName(), dim.getLength());
        SimulationLogger.info("\nVariables:");
        for (Variable var : ncfile.getVariables()) {
            SimulationLogger.info("  %s: ", var.getShortName());
            System.out.print("Dimensions [");
            for (Dimension dim : var.getDimensions())
                System.out.print(dim.getShortName() + " ");
            SimulationLogger.info("]");
            Attribute units = var.findAttribute("units");
            if (units != null) SimulationLogger.info("    units: %s%n", units.getStringValue());
        }
    }
    
    private String getVariableNames(NetcdfFile ncfile) {
        StringBuilder sb = new StringBuilder();
        for (Variable v : ncfile.getVariables()) sb.append(v.getShortName()).append(", ");
        return sb.toString();
    }
    
    private String getDimensionNames(NetcdfFile ncfile) {
        StringBuilder sb = new StringBuilder();
        for (Dimension d : ncfile.getDimensions()) sb.append(d.getShortName()).append(" (").append(d.getLength()).append("), ");
        return sb.toString();
    }
    
    public double getValueAtBilinear(double lon, double lat) {
        if (!dataLoaded) return Double.NaN;
        LocalDateTime currentTime = timeManager.getCurrentDateTime();
        if (!cacheValid || !currentTime.equals(lastCacheTime)) updateCache(currentTime);
        int latIdx1 = findLowerIndex(latValues, lat);
        int lonIdx1 = findLowerIndex(lonValues, lon);
        if (latIdx1 == -1 || lonIdx1 == -1 || latIdx1 >= latSize-1 || lonIdx1 >= lonSize-1)
            return getCachedValue(lon, lat);
        int latIdx2 = latIdx1+1, lonIdx2 = lonIdx1+1;
        double q11 = cachedGrid[latIdx1][lonIdx1], q12 = cachedGrid[latIdx1][lonIdx2];
        double q21 = cachedGrid[latIdx2][lonIdx1], q22 = cachedGrid[latIdx2][lonIdx2];
        double lat1 = latValues[latIdx1], lat2 = latValues[latIdx2];
        double lon1 = lonValues[lonIdx1], lon2 = lonValues[lonIdx2];
        double x = (lon - lon1) / (lon2 - lon1);
        double y = (lat - lat1) / (lat2 - lat1);
        return q11*(1-x)*(1-y) + q21*x*(1-y) + q12*(1-x)*y + q22*x*y;
    }
    
    private int findLowerIndex(double[] array, double value) {
        if (array == null || array.length < 2) return -1;
        for (int i = 0; i < array.length-1; i++)
            if (value >= array[i] && value <= array[i+1]) return i;
        return -1;
    }
    
    @Override
    public void update(Project project) {
        LocalDateTime current = timeManager.getCurrentDateTime();
        if (lastCacheTime == null || !lastCacheTime.equals(current)) cacheValid = false;
    }
    
    public ClimateLayerStats getStatistics() {
        return new ClimateLayerStats(getName(), dataLoaded, timeSize, latSize, lonSize,
            cacheHits, cacheMisses, interpolationsPerformed, getCacheHitRatio());
    }
    
    public void printStatistics() {
        SimulationLogger.info(getStatistics().toString());
    }
    
    private double getCacheHitRatio() {
        long total = cacheHits + cacheMisses;
        return total > 0 ? (double) cacheHits / total : 0.0;
    }
    
    // Getters
    public boolean isDataLoaded() { return dataLoaded; }
    public int getTimeSize() { return timeSize; }
    public int getLatSize() { return latSize; }
    public int getLonSize() { return lonSize; }
    public double getMinLat() { return latValues != null && latValues.length > 0 ? latValues[0] : Double.NaN; }
    public double getMaxLat() { return latValues != null ? latValues[latValues.length-1] : Double.NaN; }
    public double getMinLon() { return lonValues != null && lonValues.length > 0 ? lonValues[0] : Double.NaN; }
    public double getMaxLon() { return lonValues != null ? lonValues[lonValues.length-1] : Double.NaN; }
    public double[] getTimeValues() { return timeValues; }
    public double[] getLatValues() { return latValues; }
    public double[] getLonValues() { return lonValues; }
    
    public static class ClimateLayerStats {
        private final String layerName;
        private final boolean dataLoaded;
        private final int timeSteps, latPoints, lonPoints;
        private final long cacheHits, cacheMisses, totalInterpolations;
        private final double cacheHitRatio;
        
        public ClimateLayerStats(String name, boolean loaded, int t, int lat, int lon,
                                 long hits, long misses, long interp, double ratio) {
            this.layerName = name; this.dataLoaded = loaded; this.timeSteps = t;
            this.latPoints = lat; this.lonPoints = lon; this.cacheHits = hits;
            this.cacheMisses = misses; this.totalInterpolations = interp;
            this.cacheHitRatio = ratio;
        }
        
        @Override
        public String toString() {
            return String.format("Climate Layer '%s':\n  Loaded: %b\n  Dims: %d x %d x %d\n  Cache: %d hits, %d misses (%.1f%%)\n  Interpolations: %d",
                layerName, dataLoaded, timeSteps, latPoints, lonPoints, cacheHits, cacheMisses, cacheHitRatio*100, totalInterpolations);
        }
    }
}