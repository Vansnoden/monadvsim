package com.monadvsim.app.models.netcdf;

import ucar.ma2.DataType;
import ucar.nc2.*;
import ucar.nc2.constants.AxisType;
import ucar.nc2.dataset.*;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.time.CalendarDateRange;
import ucar.nc2.time.CalendarDateUnit; 
import java.io.IOException;
import java.util.*;

/**
 * Comprehensive NetCDF metadata inspection and validation
 */
public class NetCDFMetadata {
    private final NetcdfDataset dataset;
    private final Map<String, VariableInfo> variables = new LinkedHashMap<>();
    private final List<DimensionInfo> dimensions = new ArrayList<>();
    private final Map<String, Attribute> globalAttributes = new HashMap<>();
    private CoordinateSystemInfo coordinateSystem;
    
    // Common NetCDF variable name patterns for climate data
    private static final Map<String, List<String>> COMMON_VARIABLE_PATTERNS = Map.of(
        "temperature", Arrays.asList("2m_temperature", "t2m", "temp", "temperature", "air_temperature"),
        "precipitation", Arrays.asList("total_precipitation", "tp", "precip", "precipitation", "rain"),
        "humidity", Arrays.asList("relative_humidity", "rh", "humidity"),
        "wind_u", Arrays.asList("u_component_of_wind", "u10", "uwnd"),
        "wind_v", Arrays.asList("v_component_of_wind", "v10", "vwnd"),
        "pressure", Arrays.asList("surface_pressure", "sp", "pres")
    );
    
    public NetCDFMetadata(String filePath) throws IOException {
        this.dataset = NetcdfDataset.openDataset(filePath);
        analyzeDataset();
    }
    
    private void analyzeDataset() {
        // 1. Extract global attributes
        for (Attribute attr : dataset.getGlobalAttributes()) {
            globalAttributes.put(attr.getShortName(), attr);
        }
        
        // 2. Analyze dimensions
        for (Dimension dim : dataset.getDimensions()) {
            dimensions.add(new DimensionInfo(dim));
        }
        
        // 3. Analyze variables
        for (Variable var : dataset.getVariables()) {
            VariableInfo info = new VariableInfo(var);
            variables.put(var.getShortName(), info);
            
            // Try to detect variable type from common patterns
            detectVariableType(info);
        }
        
        // 4. Analyze coordinate systems
        analyzeCoordinateSystems();
        
        // 5. Validate dataset structure
        validateStructure();
    }
    
    private void detectVariableType(VariableInfo info) {
        String varName = info.getName().toLowerCase();
        
        for (Map.Entry<String, List<String>> entry : COMMON_VARIABLE_PATTERNS.entrySet()) {
            String type = entry.getKey();
            for (String pattern : entry.getValue()) {
                if (varName.contains(pattern.toLowerCase())) {
                    info.setDetectedType(type);
                    return;
                }
            }
        }
        
        // Check units attribute
        String units = info.getAttribute("units");
        if (units != null) {
            units = units.toLowerCase();
            if (units.contains("kelvin") || units.contains("k")) {
                info.setDetectedType("temperature");
            } else if (units.contains("meter") || units.contains("m s-1")) {
                info.setDetectedType("precipitation");
            }
        }
    }
    
    private void analyzeCoordinateSystems() {
        List<CoordinateSystem> coordSystems = dataset.getCoordinateSystems();
        if (!coordSystems.isEmpty()) {
            CoordinateSystem cs = coordSystems.get(0); // Use first coordinate system
            this.coordinateSystem = new CoordinateSystemInfo(cs);
        }
    }
    
    private void validateStructure() {
        // Check for required dimensions
        boolean hasTime = dimensions.stream().anyMatch(d -> d.isTimeDimension());
        boolean hasLat = dimensions.stream().anyMatch(d -> d.isLatitudeDimension());
        boolean hasLon = dimensions.stream().anyMatch(d -> d.isLongitudeDimension());
        
        if (!hasTime) {
            System.err.println("Warning: NetCDF file missing time dimension");
        }
        if (!hasLat || !hasLon) {
            throw new IllegalArgumentException("NetCDF file must have latitude and longitude dimensions");
        }
        
        // Check for required variables
        boolean hasTemperature = variables.values().stream()
            .anyMatch(v -> "temperature".equals(v.getDetectedType()));
        boolean hasPrecipitation = variables.values().stream()
            .anyMatch(v -> "precipitation".equals(v.getDetectedType()));
        
        if (!hasTemperature) {
            System.err.println("Warning: Temperature variable not detected in NetCDF");
        }
        if (!hasPrecipitation) {
            System.err.println("Warning: Precipitation variable not detected in NetCDF");
        }
    }
    
    /**
     * Find variable by type (temperature, precipitation, etc.)
     */
    public Optional<VariableInfo> findVariableByType(String type) {
        return variables.values().stream()
            .filter(v -> type.equals(v.getDetectedType()))
            .findFirst();
    }
    
    /**
     * Get time dimension information
     */
    public Optional<DimensionInfo> getTimeDimension() {
        return dimensions.stream()
            .filter(DimensionInfo::isTimeDimension)
            .findFirst();
    }
    
    /**
     * Get time values as CalendarDates
     */
    public List<CalendarDate> getTimeValues() throws IOException {
        Optional<VariableInfo> timeVar = variables.values().stream()
            .filter(v -> v.isTimeVariable())
            .findFirst();
        
        if (timeVar.isPresent()) {
            Variable variable = dataset.findVariable(timeVar.get().getName());
            if (variable != null) {
                return extractTimeValues(variable);
            }
        }
        
        return Collections.emptyList();
    }
    
    private List<CalendarDate> extractTimeValues(Variable timeVariable) throws IOException {
        List<CalendarDate> dates = new ArrayList<>();
        
        // Get time units and calendar
        Attribute unitsAttr = timeVariable.findAttribute("units");
        Attribute calendarAttr = timeVariable.findAttribute("calendar");
        
        if (unitsAttr != null) {
            String unitsString = unitsAttr.getStringValue();
            CalendarDateUnit dateUnit = CalendarDateUnit.of(
                calendarAttr != null ? calendarAttr.getStringValue() : "standard",
                unitsString
            );
            
            // Read time values
            ucar.ma2.Array timeData = timeVariable.read();
            for (int i = 0; i < timeData.getSize(); i++) {
                double timeValue = timeData.getDouble(i);
                CalendarDate date = dateUnit.makeCalendarDate(timeValue);
                dates.add(date);
            }
        }
        
        return dates;
    }
    
    /**
     * Get spatial resolution
     */
    public double[] getSpatialResolution() {
        Optional<DimensionInfo> latDim = dimensions.stream()
            .filter(DimensionInfo::isLatitudeDimension)
            .findFirst();
        Optional<DimensionInfo> lonDim = dimensions.stream()
            .filter(DimensionInfo::isLongitudeDimension)
            .findFirst();
        
        if (latDim.isPresent() && lonDim.isPresent()) {
            // For simple lat/lon grids, we can estimate resolution from dimension size
            // Actual resolution would require checking coordinate variables
            return new double[]{1.0, 1.0}; // Placeholder
        }
        
        return null;
    }
    
    /**
     * Get dataset summary
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        
        summary.put("filePath", dataset.getLocation());
        summary.put("format", dataset.getFileTypeDescription());
        
        // Dimensions
        Map<String, Object> dimSummary = new HashMap<>();
        for (DimensionInfo dim : dimensions) {
            dimSummary.put(dim.getName(), dim.getLength());
        }
        summary.put("dimensions", dimSummary);
        
        // Variables
        List<Map<String, Object>> varSummary = new ArrayList<>();
        for (VariableInfo var : variables.values()) {
            Map<String, Object> varInfo = new HashMap<>();
            varInfo.put("name", var.getName());
            varInfo.put("type", var.getDataType());
            varInfo.put("shape", var.getShape());
            varInfo.put("detectedType", var.getDetectedType());
            varInfo.put("units", var.getAttribute("units"));
            varSummary.add(varInfo);
        }
        summary.put("variables", varSummary);
        
        // Time information
        Optional<DimensionInfo> timeDim = getTimeDimension();
        timeDim.ifPresent(dim -> {
            summary.put("timeSteps", dim.getLength());
            try {
                List<CalendarDate> times = getTimeValues();
                if (!times.isEmpty()) {
                    summary.put("timeRange", times.get(0) + " to " + times.get(times.size() - 1));
                }
            } catch (IOException e) {
                // Ignore time extraction errors for summary
            }
        });
        
        // Coordinate system
        if (coordinateSystem != null) {
            summary.put("coordinateSystem", coordinateSystem.getName());
            summary.put("crs", coordinateSystem.getCRS());
        }
        
        return summary;
    }
    
    public void close() {
        if (dataset != null) {
            try {
                dataset.close();
            } catch (IOException e) {
                System.err.println("Error closing NetCDF dataset: " + e.getMessage());
            }
        }
    }
    
    // Inner classes
    public static class VariableInfo {
        private final Variable variable;
        private String detectedType;
        
        VariableInfo(Variable variable) {
            this.variable = variable;
        }
        
        public String getName() { return variable.getShortName(); }
        public DataType getDataType() { return variable.getDataType(); }
        public int[] getShape() { return variable.getShape(); }
        public String getDetectedType() { return detectedType; }
        public void setDetectedType(String type) { this.detectedType = type; }
        
        public boolean isTimeVariable() {
            String name = variable.getShortName().toLowerCase();
            return name.contains("time") || 
                   variable.findAttribute("axis") != null && 
                   variable.findAttribute("axis").getStringValue().equalsIgnoreCase("T");
        }
        
        public String getAttribute(String name) {
            Attribute attr = variable.findAttribute(name);
            return attr != null ? attr.getStringValue() : null;
        }
        
        public List<Attribute> getAttributes() {
            return variable.getAttributes();
        }
    }
    
    public static class DimensionInfo {
        private final Dimension dimension;
        
        DimensionInfo(Dimension dimension) {
            this.dimension = dimension;
        }
        
        public String getName() { return dimension.getShortName(); }
        public int getLength() { return dimension.getLength(); }
        
        public boolean isTimeDimension() {
            String name = dimension.getShortName().toLowerCase();
            return name.contains("time") || dimension.isUnlimited();
        }
        
        public boolean isLatitudeDimension() {
            String name = dimension.getShortName().toLowerCase();
            return name.contains("lat") || name.contains("y");
        }
        
        public boolean isLongitudeDimension() {
            String name = dimension.getShortName().toLowerCase();
            return name.contains("lon") || name.contains("x");
        }
    }
    
    public static class CoordinateSystemInfo {
        private final CoordinateSystem coordinateSystem;
        
        CoordinateSystemInfo(CoordinateSystem coordinateSystem) {
            this.coordinateSystem = coordinateSystem;
        }
        
        public String getName() { return coordinateSystem.getName(); }
        
        public String getCRS() {
            // Try to extract CRS information
            // This is simplified - real implementation would parse CF conventions
            return "WGS84"; // Default assumption for climate data
        }
        
        public List<Variable> getCoordinateAxes() {
            return coordinateSystem.getCoordinateAxes().stream()
                    .map(axis -> (Variable) axis)
                    .toList();
        }
    }
    
    public NetcdfDataset getDataset(){ return dataset;}
}