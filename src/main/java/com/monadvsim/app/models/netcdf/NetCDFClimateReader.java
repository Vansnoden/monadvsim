package com.monadvsim.app.models.netcdf;

import ucar.ma2.*;
import ucar.nc2.*;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.util.DiskCache2;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.dataset.NetcdfDatasets;

/**
 * Robust NetCDF reader with automatic variable detection and time handling
 */
public class NetCDFClimateReader implements AutoCloseable {
    private final String filePath;
    private NetcdfDataset dataset;
    private NetCDFMetadata metadata;
    private final Map<String, Variable> variableCache = new ConcurrentHashMap<>();
    private final Map<Integer, Array> timeSliceCache = new ConcurrentHashMap<>();
    private int cacheSize = 10; // Cache last 10 time slices
    
    // Configuration
    private boolean useDiskCache = true;
    private boolean lazyLoading = true;
    private int chunkSize = 100; // Time steps to read at once
    
    public NetCDFClimateReader(String filePath) throws IOException {
        this.filePath = filePath;
        
        // Configure NetCDF disk cache
        if (useDiskCache) {
            DiskCache2 defaultCache = DiskCache2.getDefault();
            defaultCache.setRootDirectory(System.getProperty("java.io.tmpdir") + "/netcdf_cache");
        }
        
        openDataset();
    }
    
    private void openDataset() throws IOException {
        // Open with enhancements for climate data
        dataset = NetcdfDatasets.openDataset(
            filePath, 
            true,  
            null
        );
        // Analyze metadata
        metadata = new NetCDFMetadata(filePath);
        
        System.out.println("✓ Loaded NetCDF: " + filePath);
        System.out.println("Dataset summary: " + metadata.getSummary());
    }
    
    /**
     * Find and validate climate variables
     */
    public ClimateVariables detectClimateVariables() {
        ClimateVariables vars = new ClimateVariables();
        
        // Find temperature variable
        Optional<NetCDFMetadata.VariableInfo> tempVar = metadata.findVariableByType("temperature");
        if (tempVar.isPresent()) {
            vars.temperature = dataset.findVariable(tempVar.get().getName());
            vars.temperatureName = tempVar.get().getName();
            System.out.println("Detected temperature variable: " + vars.temperatureName);
        }
        
        // Find precipitation variable
        Optional<NetCDFMetadata.VariableInfo> precipVar = metadata.findVariableByType("precipitation");
        if (precipVar.isPresent()) {
            vars.precipitation = dataset.findVariable(precipVar.get().getName());
            vars.precipitationName = precipVar.get().getName();
            System.out.println("Detected precipitation variable: " + vars.precipitationName);
        }
        
        // Find other variables
        detectAdditionalVariables(vars);
        
        // Validate variables have compatible dimensions
        validateVariableDimensions(vars);
        
        return vars;
    }
    
    private void detectAdditionalVariables(ClimateVariables vars) {
        // Detect humidity
        Optional<NetCDFMetadata.VariableInfo> humidityVar = metadata.findVariableByType("humidity");
        humidityVar.ifPresent(info -> {
            vars.humidity = dataset.findVariable(info.getName());
            vars.humidityName = info.getName();
            System.out.println("Detected humidity variable: " + vars.humidityName);
        });
        
        // Detect wind components
        Optional<NetCDFMetadata.VariableInfo> windUVar = metadata.findVariableByType("wind_u");
        windUVar.ifPresent(info -> {
            vars.windU = dataset.findVariable(info.getName());
            vars.windUName = info.getName();
        });
        
        Optional<NetCDFMetadata.VariableInfo> windVVar = metadata.findVariableByType("wind_v");
        windVVar.ifPresent(info -> {
            vars.windV = dataset.findVariable(info.getName());
            vars.windVName = info.getName();
        });
        
        if (vars.windU != null && vars.windV != null) {
            System.out.println("Detected wind components: " + vars.windUName + ", " + vars.windVName);
        }
    }
    
    private void validateVariableDimensions(ClimateVariables vars) {
        List<Dimension> referenceDims = null;
        
        // Use temperature dimensions as reference if available
        if (vars.temperature != null) {
            referenceDims = vars.temperature.getDimensions();
        } else if (vars.precipitation != null) {
            referenceDims = vars.precipitation.getDimensions();
        }
        
        if (referenceDims == null) {
            throw new IllegalStateException("No climate variables found in NetCDF file");
        }
        
        // Check all variables have same dimensions
        if (vars.temperature != null && !dimsEqual(referenceDims, vars.temperature.getDimensions())) {
            throw new IllegalArgumentException("Temperature variable has incompatible dimensions");
        }
        if (vars.precipitation != null && !dimsEqual(referenceDims, vars.precipitation.getDimensions())) {
            throw new IllegalArgumentException("Precipitation variable has incompatible dimensions");
        }
    }
    
    private boolean dimsEqual(List<Dimension> dims1, List<Dimension> dims2) {
        if (dims1.size() != dims2.size()) return false;
        for (int i = 0; i < dims1.size(); i++) {
            if (dims1.get(i).getLength() != dims2.get(i).getLength()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get time dimension values as LocalDateTime
     */
    public List<LocalDateTime> getTimeValues() throws IOException {
        List<CalendarDate> calendarDates = metadata.getTimeValues();
        List<LocalDateTime> localDates = new ArrayList<>();
        
        for (CalendarDate calendarDate : calendarDates) {
            localDates.add(LocalDateTime.ofInstant(
                calendarDate.toDate().toInstant(),
                ZoneId.systemDefault()
            ));
        }
        
        return localDates;
    }
    
    /**
     * Find time index for a given LocalDateTime
     */
    public int findTimeIndex(LocalDateTime dateTime) throws IOException {
        List<LocalDateTime> timeValues = getTimeValues();
        
        // Simple linear search (times should be sorted)
        for (int i = 0; i < timeValues.size(); i++) {
            if (!timeValues.get(i).isAfter(dateTime)) {
                // Check if this is the closest time
                if (i == timeValues.size() - 1 || 
                    timeValues.get(i + 1).isAfter(dateTime)) {
                    return i;
                }
            }
        }
        
        // If date is before first time, return 0
        if (!timeValues.isEmpty() && dateTime.isBefore(timeValues.get(0))) {
            return 0;
        }
        
        // If date is after last time, return last index
        if (!timeValues.isEmpty() && dateTime.isAfter(timeValues.get(timeValues.size() - 1))) {
            return timeValues.size() - 1;
        }
        
        throw new IllegalArgumentException("Cannot find time index for: " + dateTime);
    }
    
    /**
     * Read data for a specific time index (optimized)
     */
    public ClimateData readTimeSlice(int timeIndex, ClimateVariables variables) throws IOException, InvalidRangeException {
        ClimateData data = new ClimateData();
        
        // Read temperature if available
        if (variables.temperature != null) {
            data.temperature = readVariableSlice(variables.temperature, timeIndex);
        }
        
        // Read precipitation if available
        if (variables.precipitation != null) {
            data.precipitation = readVariableSlice(variables.precipitation, timeIndex);
        }
        
        // Read other variables
        if (variables.humidity != null) {
            data.humidity = readVariableSlice(variables.humidity, timeIndex);
        }
        
        // Cache this time slice
        cacheTimeSlice(timeIndex, data);
        
        return data;
    }
    
    /**
     * Optimized variable reading with chunking
     */
    private Array readVariableSlice(Variable variable, int timeIndex) throws IOException, InvalidRangeException {
        // Check cache first
        String cacheKey = variable.getShortName() + "_" + timeIndex;
        if (timeSliceCache.containsKey(timeIndex)) {
            // Simplified cache check - in reality would need variable-specific caching
        }
        
        // Determine shape and section
        int rank = variable.getRank();
        int[] origin = new int[rank];
        int[] shape = variable.getShape();
        
        // Set time index as first dimension (typical for climate data)
        origin[0] = timeIndex;
        shape[0] = 1; // Only one time step
        
        // Create section
        Section section = new Section(origin, shape);
        
        // Read data
        return variable.read(section);
    }
    
    /**
     * Bulk read multiple time slices (more efficient for sequential access)
     */
    public List<ClimateData> readTimeRange(int startIndex, int count, ClimateVariables variables) 
            throws IOException, InvalidRangeException {
        List<ClimateData> results = new ArrayList<>();
        
        // Read in chunks for efficiency
        for (int chunkStart = startIndex; chunkStart < startIndex + count; chunkStart += chunkSize) {
            int chunkEnd = Math.min(chunkStart + chunkSize, startIndex + count);
            readTimeChunk(chunkStart, chunkEnd, variables, results);
        }
        
        return results;
    }
    
    private void readTimeChunk(int start, int end, ClimateVariables variables, List<ClimateData> results) 
            throws IOException, InvalidRangeException {
        // Create sections for bulk reading
        if (variables.temperature != null) {
            Array tempChunk = readVariableChunk(variables.temperature, start, end - start);
            // Split into individual time slices
            splitChunkIntoTimeSlices(tempChunk, start, results, ClimateData::setTemperature);
        }
        
        if (variables.precipitation != null) {
            Array precipChunk = readVariableChunk(variables.precipitation, start, end - start);
            splitChunkIntoTimeSlices(precipChunk, start, results, ClimateData::setPrecipitation);
        }
    }
    
    private Array readVariableChunk(Variable variable, int startIndex, int count) throws IOException, InvalidRangeException {
        int rank = variable.getRank();
        int[] origin = new int[rank];
        int[] shape = variable.getShape();
        
        origin[0] = startIndex;
        shape[0] = count; // Multiple time steps
        
        Section section = new Section(origin, shape);
        return variable.read(section);
    }
    
    private void splitChunkIntoTimeSlices(Array chunk, int startIndex, 
                                         List<ClimateData> results,
                                         DataSetter setter) {
        // Implementation depends on array structure
        // This is simplified - actual implementation would extract 2D slices
    }
    
    private void cacheTimeSlice(int timeIndex, ClimateData data) {
        // Simple LRU cache implementation
        if (timeSliceCache.size() >= cacheSize) {
            // Remove oldest entry
            Integer oldestKey = timeSliceCache.keySet().iterator().next();
            timeSliceCache.remove(oldestKey);
        }
        
        // Cache temperature array (simplified)
        if (data.temperature != null) {
            timeSliceCache.put(timeIndex, data.temperature);
        }
    }
    
    /**
     * Get spatial subset of data (for regional simulations)
     */
    public ClimateData readRegion(int timeIndex, ClimateVariables variables,
                                 double minLon, double maxLon, 
                                 double minLat, double maxLat) throws IOException, InvalidRangeException {
        // Find latitude/longitude indices for the region
        int[] lonIndices = findLonIndices(minLon, maxLon);
        int[] latIndices = findLatIndices(minLat, maxLat);
        
        ClimateData data = new ClimateData();
        
        if (variables.temperature != null) {
            data.temperature = readVariableRegion(variables.temperature, timeIndex, 
                                                 lonIndices, latIndices);
        }
        
        if (variables.precipitation != null) {
            data.precipitation = readVariableRegion(variables.precipitation, timeIndex,
                                                   lonIndices, latIndices);
        }
        
        return data;
    }
    
    private int[] findLonIndices(double minLon, double maxLon) {
        // Implementation would read longitude coordinate variable
        // and find indices within range
        return new int[]{0, 100}; // Placeholder
    }
    
    private int[] findLatIndices(double minLat, double maxLat) {
        // Implementation would read latitude coordinate variable
        return new int[]{0, 100}; // Placeholder
    }
    
    private Array readVariableRegion(Variable variable, int timeIndex,
                                    int[] lonIndices, int[] latIndices) throws IOException, InvalidRangeException {
        int rank = variable.getRank();
        int[] origin = new int[rank];
        int[] shape = new int[rank];
        
        // Time dimension
        origin[0] = timeIndex;
        shape[0] = 1;
        
        // Latitude dimension (typically dimension 1)
        origin[1] = latIndices[0];
        shape[1] = latIndices[1] - latIndices[0];
        
        // Longitude dimension (typically dimension 2)
        origin[2] = lonIndices[0];
        shape[2] = lonIndices[1] - lonIndices[0];
        
        Section section = new Section(origin, shape);
        return variable.read(section);
    }
    
    @Override
    public void close() {
        // Clear caches
        variableCache.clear();
        timeSliceCache.clear();
        
        // Close metadata
        if (metadata != null) {
            metadata.close();
        }
        
        // Close dataset
        if (dataset != null) {
            try {
                dataset.close();
                System.out.println("✓ Closed NetCDF dataset: " + filePath);
            } catch (IOException e) {
                System.err.println("Error closing NetCDF dataset: " + e.getMessage());
            }
        }
    }
    
    // Configuration setters
    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }
    
    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }
    
    public void setLazyLoading(boolean lazyLoading) {
        this.lazyLoading = lazyLoading;
    }
    
    // Data classes
    public static class ClimateVariables {
        public Variable temperature;
        public Variable precipitation;
        public Variable humidity;
        public Variable windU;
        public Variable windV;
        
        public String temperatureName;
        public String precipitationName;
        public String humidityName;
        public String windUName;
        public String windVName;
    }
    
    public static class ClimateData {
        public Array temperature;
        public Array precipitation;
        public Array humidity;
        public Array windU;
        public Array windV;
        
        // Helper methods
        public double getTemperatureValue(int latIndex, int lonIndex) {
            if (temperature == null) return Double.NaN;
            return temperature.getDouble(latIndex * temperature.getShape()[1] + lonIndex);
        }
        
        public double getPrecipitationValue(int latIndex, int lonIndex) {
            if (precipitation == null) return Double.NaN;
            return precipitation.getDouble(latIndex * precipitation.getShape()[1] + lonIndex);
        }
        
        // Setters for chunk processing
        public void setTemperature(Array temp) { this.temperature = temp; }
        public void setPrecipitation(Array precip) { this.precipitation = precip; }
        public void setHumidity(Array humidity) { this.humidity = humidity; }
    }
    
    @FunctionalInterface
    private interface DataSetter {
        void set(ClimateData data, Array array);
    }
    
    public NetcdfDataset getDataset(){return dataset;}
}