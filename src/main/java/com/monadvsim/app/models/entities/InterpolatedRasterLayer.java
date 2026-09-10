package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;
import com.monadvsim.app.models.utils.SimulationLogger;
import ucar.ma2.Array;
import ucar.ma2.Index;
import ucar.nc2.*;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Time‑interpolated raster layer for climate data (NetCDF).
 * Supports temporal interpolation between time steps.
 */
public class InterpolatedRasterLayer extends Layer {

    private double[][][] dataGrid;          // [time][lat][lon]
    private double[] timeValues;             // seconds since reference
    private double[] latValues;
    private double[] lonValues;
    private int timeSize, latSize, lonSize;

    private final TimeManager timeManager;
    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();

    // Caching of the current interpolated 2D grid
    private double[][] cachedGrid;
    private LocalDateTime lastCacheTime;
    private boolean cacheValid = false;

    private LocalDateTime timeReference;     // reference date for timeValues
    private boolean dataLoaded = false;

    // Statistics
    private long cacheHits = 0, cacheMisses = 0;

    // Dimension names (may vary per NetCDF)
    private String timeDimName = "time";
    private String latDimName = "latitude";
    private String lonDimName = "longitude";
    private String stepDimName = "step";

    public InterpolatedRasterLayer(String name, TimeManager timeManager) {
        super(name);
        this.timeManager = timeManager;
    }

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------
    public void loadFromNetCDF(String filePath, String variableName) throws IOException {
        dataLock.writeLock().lock();
        try (NetcdfFile ncfile = NetcdfFiles.open(filePath)) {
            SimulationLogger.info("Loading NetCDF: %s, variable: %s", filePath, variableName);
            detectDimensions(ncfile);

            Variable var = ncfile.findVariable(variableName);
            if (var == null) throw new IOException("Variable not found: " + variableName);

            Dimension timeDim = ncfile.findDimension(timeDimName);
            Dimension latDim = ncfile.findDimension(latDimName);
            Dimension lonDim = ncfile.findDimension(lonDimName);
            if (timeDim == null || latDim == null || lonDim == null)
                throw new IOException("Missing required dimensions");

            timeSize = timeDim.getLength();
            latSize = latDim.getLength();
            lonSize = lonDim.getLength();

            // Check for 'step' dimension (some ERA5 files have it)
            boolean hasStep = false;
            int stepSize = 1;
            for (Dimension d : var.getDimensions()) {
                if (d.getShortName().equals(stepDimName)) {
                    hasStep = true;
                    stepSize = d.getLength();
                    break;
                }
            }

            dataGrid = new double[timeSize][latSize][lonSize];

            readCoordinateVariables(ncfile);
            readTimeMetadata(ncfile);
            readVariableData(var, ncfile, hasStep, stepSize);

            // Ensure latitudes are increasing
            if (latValues.length > 1 && latValues[0] > latValues[1]) {
                reverseLatitudeData();
            }

            // Auto‑convert temperature from Celsius to Kelvin if needed
            if (variableName.equalsIgnoreCase("t2m") || variableName.equalsIgnoreCase("temperature")) {
                double sum = 0.0;
                int count = 0;
                outer:
                for (int t = 0; t < Math.min(timeSize, 5); t++) {
                    for (int lat = 0; lat < Math.min(latSize, 5); lat++) {
                        for (int lon = 0; lon < Math.min(lonSize, 5); lon++) {
                            double v = dataGrid[t][lat][lon];
                            if (!Double.isNaN(v)) {
                                sum += v;
                                count++;
                                if (count >= 25) break outer;
                            }
                        }
                    }
                }
                if (count > 0) {
                    double avg = sum / count;
                    if (avg < 200) {
                        SimulationLogger.info("Temperature variable appears to be in Celsius; converting to Kelvin.");
                        for (int t = 0; t < timeSize; t++) {
                            for (int lat = 0; lat < latSize; lat++) {
                                for (int lon = 0; lon < lonSize; lon++) {
                                    if (!Double.isNaN(dataGrid[t][lat][lon]))
                                        dataGrid[t][lat][lon] += 273.15;
                                }
                            }
                        }
                    }
                }
            }

            // --- DIAGNOSTIC: Print time range and sample values ---
            printDiagnostics(variableName);

            dataLoaded = true;
            cacheValid = false;
            cachedGrid = new double[latSize][lonSize];
            SimulationLogger.info("Loaded %s: %d time steps, %d x %d grid", variableName, timeSize, latSize, lonSize);
        } finally {
            dataLock.writeLock().unlock();
        }
    }

    private void printDiagnostics(String variableName) {
        SimulationLogger.info("=== Climate Data Diagnostics for %s ===", variableName);
        if (timeReference != null) {
            SimulationLogger.info("  Time reference: %s", timeReference);
        } else {
            SimulationLogger.info("  Time reference: NULL (using default)");
        }
        if (timeValues != null && timeValues.length > 0) {
            SimulationLogger.info("  Time range: %.2f to %.2f seconds since reference", timeValues[0], timeValues[timeValues.length - 1]);
            SimulationLogger.info("  Number of time steps: %d", timeValues.length);
        } else {
            SimulationLogger.info("  No time values loaded!");
        }
        if (latValues != null && lonValues != null) {
            SimulationLogger.info("  Grid size: %d x %d", latValues.length, lonValues.length);
        }
        
        // Print sample data at center
        if (dataGrid != null && dataGrid.length > 0 && latValues != null && lonValues != null) {
            int centerLat = latValues.length / 2;
            int centerLon = lonValues.length / 2;
            int midTime = timeValues.length / 2;
            if (midTime < dataGrid.length && centerLat < dataGrid[midTime].length && centerLon < dataGrid[midTime][centerLat].length) {
                double sample = dataGrid[midTime][centerLat][centerLon];
                String unit = variableName.equalsIgnoreCase("t2m") ? "K" : "m";
                SimulationLogger.info("  Sample value at center (time %d): %.4f %s", midTime, sample, unit);
            }
        }
        SimulationLogger.info("=====================================");
    }

    @Override
    public double getValueAt(double lon, double lat) {
        if (!dataLoaded) return Double.NaN;
        LocalDateTime now = timeManager.getCurrentDateTime();
        if (lastCacheTime == null || !lastCacheTime.equals(now)) {
            updateCache(now);
        }
        return getCachedValue(lon, lat);
    }

    @Override
    public void update(Project project) {
        // Nothing needed – caching is handled in getValueAt
    }

    // ------------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------------
    private void detectDimensions(NetcdfFile ncfile) {
        for (Dimension dim : ncfile.getDimensions()) {
            String name = dim.getShortName();
            if (name.equals("time") || name.equals("valid_time") || name.equals("Time")) timeDimName = name;
            if (name.equals("lat") || name.equals("latitude") || name.equals("Lat")) latDimName = name;
            if (name.equals("lon") || name.equals("longitude") || name.equals("Lon")) lonDimName = name;
            if (name.equals("step")) stepDimName = name;
        }
    }

    private void readCoordinateVariables(NetcdfFile ncfile) throws IOException {
        Variable latVar = ncfile.findVariable(latDimName);
        if (latVar == null) latVar = ncfile.findVariable("lat");
        if (latVar != null) {
            Array latArray = latVar.read();
            latValues = new double[latSize];
            for (int i = 0; i < latSize; i++) latValues[i] = latArray.getDouble(i);
        } else {
            throw new IOException("Latitude variable not found");
        }

        Variable lonVar = ncfile.findVariable(lonDimName);
        if (lonVar == null) lonVar = ncfile.findVariable("lon");
        if (lonVar != null) {
            Array lonArray = lonVar.read();
            lonValues = new double[lonSize];
            for (int i = 0; i < lonSize; i++) lonValues[i] = lonArray.getDouble(i);
        } else {
            throw new IOException("Longitude variable not found");
        }
    }

//    private void readTimeMetadata(NetcdfFile ncfile) throws IOException {
//        Variable timeVar = ncfile.findVariable(timeDimName);
//        if (timeVar == null) {
//            SimulationLogger.warning("No time variable found; using step index.");
//            timeValues = new double[timeSize];
//            for (int i = 0; i < timeSize; i++) timeValues[i] = i * 3600.0; // 1‑hour steps
//            timeReference = timeManager.getStartDateTime();
//            return;
//        }
//
//        Attribute unitsAttr = timeVar.findAttribute("units");
//        String units = (unitsAttr != null) ? unitsAttr.getStringValue() : "";
//        SimulationLogger.info("Time units attribute: %s", units);
//        parseTimeUnits(units);
//
//        Array timeArray = timeVar.read();
//        final double scale;
//        if (units.contains("days") || units.contains("day")) scale = 86400.0;
//        else if (units.contains("hours") || units.contains("hour")) scale = 3600.0;
//        else if (units.contains("minutes") || units.contains("minute")) scale = 60.0;
//        else {
//            SimulationLogger.warning("Unknown time unit format: %s, using seconds", units);
//            scale = 1.0;
//        }
//
//        timeValues = new double[timeSize];
//        for (int i = 0; i < timeSize; i++) {
//            double raw = timeArray.getDouble(i);
//            timeValues[i] = raw * scale;
//        }
//        SimulationLogger.info("Time range: %.2f to %.2f seconds since reference", timeValues[0], timeValues[timeSize-1]);
//        
//        // Verify time reference is valid
//        if (timeReference == null) {
//            SimulationLogger.warning("Time reference is null! Using simulation start time.");
//            timeReference = timeManager.getStartDateTime();
//        }
//    }
    
    private void readTimeMetadata(NetcdfFile ncfile) throws IOException {
        Variable timeVar = ncfile.findVariable(timeDimName);
        if (timeVar == null) {
            SimulationLogger.warning("No time variable found; using step index.");
            timeValues = new double[timeSize];
            for (int i = 0; i < timeSize; i++) timeValues[i] = i * 3600.0; // 1-hour steps
            timeReference = timeManager.getStartDateTime();
            // CRITICAL FIX: Set dataStartDateTime in TimeManager
            timeManager.setDataStartDateTime(timeReference);
            return;
        }

        Attribute unitsAttr = timeVar.findAttribute("units");
        String units = (unitsAttr != null) ? unitsAttr.getStringValue() : "";
        SimulationLogger.info("Time units attribute: %s", units);
        parseTimeUnits(units);

        // CRITICAL FIX: If timeReference is still null after parsing, set it to simulation start
        if (timeReference == null) {
            SimulationLogger.warning("timeReference is null after parsing, using simulation start time");
            timeReference = timeManager.getStartDateTime();
            timeManager.setDataStartDateTime(timeReference);
        }
        
        Array timeArray = timeVar.read();
        final double scale;
        if (units.contains("days") || units.contains("day")) scale = 86400.0;
        else if (units.contains("hours") || units.contains("hour")) scale = 3600.0;
        else if (units.contains("minutes") || units.contains("minute")) scale = 60.0;
        else {
            SimulationLogger.warning("Unknown time unit format: %s, using seconds", units);
            scale = 1.0;
        }

        timeValues = new double[timeSize];
        for (int i = 0; i < timeSize; i++) {
            double raw = timeArray.getDouble(i);
            timeValues[i] = raw * scale;
        }
        SimulationLogger.info("Time range: %.2f to %.2f seconds since reference", timeValues[0], timeValues[timeSize-1]);
        
        // Verify time reference is valid
        if (timeReference == null) {
            SimulationLogger.warning("Time reference is null! Using simulation start time.");
            timeReference = timeManager.getStartDateTime();
        }
    }

    private void parseTimeUnits(String units) {
        // Default reference: 1970-01-01
        timeReference = LocalDateTime.of(1970, 1, 1, 0, 0);
        if (units == null || units.isEmpty()) {
            SimulationLogger.warning("Empty time units, using default reference: 1970-01-01");
            return;
        }

        String[] parts = units.split("since");
        if (parts.length == 2) {
            String dateStr = parts[1].trim();
            try {
                // Try with time
                if (dateStr.contains(" ")) {
                    try {
                        // FIRST: Try strict format
                        timeReference = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    } catch (DateTimeParseException e) {
                        // SECOND: Try format without leading zeros (2020-1-1)
                        try {
                            timeReference = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss"));
                        } catch (DateTimeParseException e2) {
                            // THIRD: Try without seconds
                            timeReference = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-M-d HH:mm"));
                        }
                    }
                } else {
                    // Date only
                    try {
                        timeReference = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    } catch (DateTimeParseException e) {
                        // Try without leading zeros
                        timeReference = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-M-d"));
                    }
                }
                SimulationLogger.info("Time reference parsed: %s", timeReference);
                return;
            } catch (Exception e) {
                SimulationLogger.warning("Could not parse time units: %s - %s", units, e.getMessage());
                // Try alternative format with .0 suffix
                try {
                    String cleanDate = dateStr.replace(".0", "");
                    if (cleanDate.contains(" ")) {
                        try {
                            timeReference = LocalDateTime.parse(cleanDate, DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss"));
                        } catch (DateTimeParseException e2) {
                            timeReference = LocalDateTime.parse(cleanDate, DateTimeFormatter.ofPattern("yyyy-M-d HH:mm"));
                        }
                    } else {
                        timeReference = LocalDateTime.parse(cleanDate, DateTimeFormatter.ofPattern("yyyy-M-d"));
                    }
                    SimulationLogger.info("Time reference parsed (alt): %s", timeReference);
                } catch (Exception e2) {
                    SimulationLogger.warning("Could not parse time units (alt): %s", units);
                    timeReference = timeManager.getStartDateTime();
                    SimulationLogger.info("Using simulation start time as reference: %s", timeReference);
                }
            }
        } else {
            SimulationLogger.warning("Unexpected time units format: %s", units);
            timeReference = timeManager.getStartDateTime();
            SimulationLogger.info("Using simulation start time as reference: %s", timeReference);
        }
    }

    private void readVariableData(Variable var, NetcdfFile ncfile, boolean hasStep, int stepSize) throws IOException {
        Array dataArray = var.read();
        Index index = dataArray.getIndex();

        // Determine dimension order
        List<Dimension> dims = var.getDimensions();
        int timeIdx = -1, latIdx = -1, lonIdx = -1, stepIdx = -1;
        for (int i = 0; i < dims.size(); i++) {
            String d = dims.get(i).getShortName();
            if (d.equals(timeDimName)) timeIdx = i;
            else if (d.equals(latDimName)) latIdx = i;
            else if (d.equals(lonDimName)) lonIdx = i;
            else if (d.equals(stepDimName)) stepIdx = i;
        }
        if (timeIdx == -1 || latIdx == -1 || lonIdx == -1)
            throw new IOException("Cannot identify dimension order for variable " + var.getShortName());

        // Read scale/offset
        final double scale;
        final double offset;
        final double fillValue;

        Attribute scaleAttr = var.findAttribute("scale_factor");
        if (scaleAttr != null) scale = scaleAttr.getNumericValue().doubleValue();
        else scale = 1.0;

        Attribute offAttr = var.findAttribute("add_offset");
        if (offAttr != null) offset = offAttr.getNumericValue().doubleValue();
        else offset = 0.0;

        Attribute fillAttr = var.findAttribute("_FillValue");
        if (fillAttr != null) fillValue = fillAttr.getNumericValue().doubleValue();
        else fillValue = Double.NaN;

        for (int t = 0; t < timeSize; t++) {
            for (int lat = 0; lat < latSize; lat++) {
                for (int lon = 0; lon < lonSize; lon++) {
                    double sum = 0.0;
                    int count = 0;
                    if (!hasStep) {
                        int[] pos = new int[dims.size()];
                        pos[timeIdx] = t;
                        pos[latIdx] = lat;
                        pos[lonIdx] = lon;
                        double raw = dataArray.getDouble(index.set(pos));
                        double val = raw * scale + offset;
                        if (!Double.isNaN(fillValue) && Math.abs(raw - fillValue) < 1e-6) val = Double.NaN;
                        if (!Double.isNaN(val)) {
                            sum = val;
                            count = 1;
                        }
                    } else {
                        for (int step = 0; step < stepSize; step++) {
                            int[] pos = new int[dims.size()];
                            pos[timeIdx] = t;
                            pos[stepIdx] = step;
                            pos[latIdx] = lat;
                            pos[lonIdx] = lon;
                            double raw = dataArray.getDouble(index.set(pos));
                            double val = raw * scale + offset;
                            if (!Double.isNaN(fillValue) && Math.abs(raw - fillValue) < 1e-6) val = Double.NaN;
                            if (!Double.isNaN(val)) {
                                sum += val;
                                count++;
                            }
                        }
                    }
                    dataGrid[t][lat][lon] = (count > 0) ? sum / count : Double.NaN;
                }
            }
        }
        if (hasStep) SimulationLogger.info("Reduced step dimension (averaged over %d steps)", stepSize);
    }

    private void reverseLatitudeData() {
        double[] revLat = new double[latSize];
        for (int i = 0; i < latSize; i++) revLat[i] = latValues[latSize - 1 - i];
        latValues = revLat;
        double[][][] revData = new double[timeSize][latSize][lonSize];
        for (int t = 0; t < timeSize; t++) {
            for (int lat = 0; lat < latSize; lat++) {
                System.arraycopy(dataGrid[t][latSize - 1 - lat], 0, revData[t][lat], 0, lonSize);
            }
        }
        dataGrid = revData;
    }


    private void updateCache(LocalDateTime currentTime) {
        dataLock.readLock().lock();
        try {
            if (timeReference == null) {
                SimulationLogger.warning("Time reference is null! Using simulation start time.");
                timeReference = timeManager.getStartDateTime();
                timeManager.setDataStartDateTime(timeReference);
            }

            // Get the frame index from TimeManager (already has the 41400 second offset applied)
            int frameIndex = timeManager.getCurrentFrameIndex();

            // Clamp to valid range
            int idx = Math.max(0, Math.min(frameIndex, timeSize - 1));
            int nextIdx = Math.min(idx + 1, timeSize - 1);

            // Get interpolation factor (fraction of hour into the current hour)
            double alpha = timeManager.getInterpolationFactor();

            // Allocate cache grid if needed
            if (cachedGrid == null) {
                cachedGrid = new double[latSize][lonSize];
            }

            // Interpolate between current and next time step
            for (int lat = 0; lat < latSize; lat++) {
                for (int lon = 0; lon < lonSize; lon++) {
                    if (idx == nextIdx || alpha == 0.0) {
                        // No interpolation needed - use single value
                        cachedGrid[lat][lon] = dataGrid[idx][lat][lon];
                    } else {
                        // Linear interpolation between two time steps
                        double vLow = dataGrid[idx][lat][lon];
                        double vHigh = dataGrid[nextIdx][lat][lon];
                        // Handle NaN values gracefully
                        if (Double.isNaN(vLow) && !Double.isNaN(vHigh)) {
                            cachedGrid[lat][lon] = vHigh;
                        } else if (!Double.isNaN(vLow) && Double.isNaN(vHigh)) {
                            cachedGrid[lat][lon] = vLow;
                        } else if (Double.isNaN(vLow) && Double.isNaN(vHigh)) {
                            cachedGrid[lat][lon] = Double.NaN;
                        } else {
                            cachedGrid[lat][lon] = vLow + alpha * (vHigh - vLow);
                        }
                    }
                }
            }

            // Update cache state
            lastCacheTime = currentTime;
            cacheValid = true;
            cacheMisses++;

        } catch (Exception e) {
            SimulationLogger.severe("Error in updateCache: " + e.getMessage());
            e.printStackTrace();
            cacheValid = false;
        } finally {
            dataLock.readLock().unlock();
        }
    }
    
    private double getCachedValue(double lon, double lat) {
        if (!cacheValid) {
            return Double.NaN;
        }
        int latIdx = findNearestIndex(latValues, lat);
        int lonIdx = findNearestIndex(lonValues, lon);
        if (latIdx >= 0 && latIdx < latSize && lonIdx >= 0 && lonIdx < lonSize) {
            cacheHits++;
            return cachedGrid[latIdx][lonIdx];
        }
        return Double.NaN;
    }
    

    private int findNearestIndex(double[] array, double value) {
        if (array == null || array.length == 0) return -1;
        int lo = 0, hi = array.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (array[mid] == value) return mid;
            else if (array[mid] < value) lo = mid + 1;
            else hi = mid - 1;
        }
        if (lo >= array.length) return array.length - 1;
        if (hi < 0) return 0;
        return (Math.abs(array[lo] - value) < Math.abs(array[hi] - value)) ? lo : hi;
    }

    // ------------------------------------------------------------------------
    // Utility methods for external use
    // ------------------------------------------------------------------------
    public boolean isDataLoaded() { return dataLoaded; }
    public int getTimeSize() { return timeSize; }
    public int getLatSize() { return latSize; }
    public int getLonSize() { return lonSize; }
    public double getMinLat() { return latValues != null ? latValues[0] : Double.NaN; }
    public double getMaxLat() { return latValues != null ? latValues[latValues.length-1] : Double.NaN; }
    public double getMinLon() { return lonValues != null ? lonValues[0] : Double.NaN; }
    public double getMaxLon() { return lonValues != null ? lonValues[lonValues.length-1] : Double.NaN; }

    public void printStatistics() {
        SimulationLogger.info("Climate layer %s: time steps=%d, grid=%dx%d, loaded=%b", 
            getName(), timeSize, latSize, lonSize, dataLoaded);
        double hitRatio = (cacheHits + cacheMisses) == 0 ? 0 : (double) cacheHits / (cacheHits + cacheMisses);
        SimulationLogger.info("Cache hits=%d, misses=%d, hit ratio=%.2f", cacheHits, cacheMisses, hitRatio);
        
        if (timeReference != null) {
            SimulationLogger.info("Time reference: %s", timeReference);
        }
        if (timeValues != null && timeValues.length > 0) {
            SimulationLogger.info("Time range: %.2f to %.2f seconds since reference", 
                timeValues[0], timeValues[timeValues.length - 1]);
        }
    }
}