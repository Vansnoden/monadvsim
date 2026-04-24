package com.monadvsim.app.models.utils;
import com.monadvsim.app.models.utils.SimulationLogger;

import com.monadvsim.app.models.engine.TimeManager;
import com.monadvsim.app.models.entities.InterpolatedRasterLayer;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


/**
 * Climate Data Handler
 *
 * Manages multiple climate variables from NetCDF files
 *
 * Supports ERA5 climate data format (temperature, precipitation, wind, etc.)
 *
 * Provides value interpolation and unit conversions
 *
 * Integrates with TimeManager for temporal interpolation
 * 
 * 
 * @author void
 */


public class ClimateDatasetManager {
    private final TimeManager timeManager;
    private final Map<String, InterpolatedRasterLayer> layers = new HashMap<>();
    
    // Standard ERA5 variable names
    public static final String TEMPERATURE_2M = "t2m";
    public static final String TOTAL_PRECIPITATION = "tp";
    public static final String SURFACE_PRESSURE = "sp";
    public static final String U_WIND = "u10";
    public static final String V_WIND = "v10";
    public static final String RELATIVE_HUMIDITY = "r";
    public static final String DEW_POINT = "d2m";
    
    public ClimateDatasetManager(TimeManager timeManager) {
        this.timeManager = timeManager;
    }
    
    /**
     * Load a NetCDF file with multiple variables
     */
    public void loadNetCDFDataset(String filePath, String... variableNames) throws IOException {
        for (String varName : variableNames) {
            SimulationLogger.info("Loading variable: " + varName);
            InterpolatedRasterLayer layer = new InterpolatedRasterLayer(varName, timeManager);
            layer.loadFromNetCDF(filePath, varName);
            layers.put(varName, layer);
            SimulationLogger.info("Successfully loaded: " + varName);
        }
    }
    
    /**
     * Load standard ERA5 climate variables
     */
    public void loadERA5Dataset(String filePath) throws IOException {
        String[] standardVars = {
            TEMPERATURE_2M,      // 2m temperature (K)
            TOTAL_PRECIPITATION, // Total precipitation (m)
            SURFACE_PRESSURE,    // Surface pressure (Pa)
            U_WIND,              // 10m u-component of wind (m/s)
            V_WIND,              // 10m v-component of wind (m/s)
        };
        
        loadNetCDFDataset(filePath, standardVars);
    }
    
    // smater loader to laod available variables
    public void loadAvailableVariables(String filePath) throws IOException {
        try (ucar.nc2.NetcdfFile ncfile = ucar.nc2.NetcdfFiles.open(filePath)) {
            SimulationLogger.info("Available variables in file:");
            for (ucar.nc2.Variable var : ncfile.getVariables()) {
                String varName = var.getShortName();
                SimulationLogger.info("  - " + varName);
                
                // Load common climate variables
                if (varName.equals("t2m") || varName.equals("tp") || 
                    varName.equals("sp") || varName.equals("u10") || 
                    varName.equals("v10") || varName.equals("r") || 
                    varName.equals("d2m")) {
                    
                    SimulationLogger.info("    Loading " + varName + "...");
                    InterpolatedRasterLayer layer = new InterpolatedRasterLayer(varName, timeManager);
                    layer.loadFromNetCDF(filePath, varName);
                    layers.put(varName, layer);
                }
            }
        }
    }
    
    // get world bounds
    public Rectangle2D getBounds() {
        if (layers.isEmpty()) {
            return null;
        }

        InterpolatedRasterLayer firstLayer = layers.values().iterator().next();
        return new Rectangle2D.Double(
            firstLayer.getMinLon(),
            firstLayer.getMinLat(),
            firstLayer.getMaxLon() - firstLayer.getMinLon(),
            firstLayer.getMaxLat() - firstLayer.getMinLat()
        );
    }
    
    /**
     * Get value for a specific variable at location
     */
    public double getValue(String variableName, double lon, double lat) {
        InterpolatedRasterLayer layer = layers.get(variableName);
        if (layer == null) {
            throw new IllegalArgumentException("Variable not loaded: " + variableName);
        }
        return layer.getValueAt(lon, lat);
    }
    
    /**
     * Get temperature in Celsius (converts from Kelvin)
     */
    public double getTemperatureCelsius(double lon, double lat) {
        double tempK = getValue(TEMPERATURE_2M, lon, lat);
        return tempK - 273.15; // Convert Kelvin to Celsius
    }
    
    /**
     * Get precipitation in mm/hour (converts from m)
     */
    public double getPrecipitationMmHour(double lon, double lat) {
        double precipM = getValue(TOTAL_PRECIPITATION, lon, lat);
        return precipM * 1000; // Convert m to mm
    }
    
    /**
     * Get wind speed (m/s) from u and v components
     */
    public double getWindSpeed(double lon, double lat) {
        double u = getValue(U_WIND, lon, lat);
        double v = getValue(V_WIND, lon, lat);
        return Math.sqrt(u * u + v * v);
    }
    
    /**
     * Get wind direction (degrees from north)
     */
    public double getWindDirection(double lon, double lat) {
        double u = getValue(U_WIND, lon, lat);
        double v = getValue(V_WIND, lon, lat);
        
        double direction = Math.toDegrees(Math.atan2(u, v));
        if (direction < 0) direction += 360;
        return direction;
    }
    
    public InterpolatedRasterLayer getLayer(String variableName) {
        return layers.get(variableName);
    }
    
    public Map<String, InterpolatedRasterLayer> getLayers() {
        return new HashMap<>(layers);
    }
    
    public void printStatistics() {
        SimulationLogger.info("=== Climate Dataset Statistics ===");
        for (Map.Entry<String, InterpolatedRasterLayer> entry : layers.entrySet()) {
            SimulationLogger.info(entry.getKey() + ":");
            entry.getValue().printStatistics();
        }
    }
}
