package com.monadvsim.app.models.utils;

import com.monadvsim.app.models.engine.TimeManager;
import com.monadvsim.app.models.entities.InterpolatedRasterLayer;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFiles;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages multiple climate variables from a NetCDF file.
 * Provides convenient access to temperature, precipitation, wind, etc.
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

    public ClimateDatasetManager(TimeManager timeManager) {
        this.timeManager = timeManager;
    }

    /**
     * Load all available standard climate variables from a NetCDF file.
     * Detects which variables exist and loads them.
     */
    public void loadAvailableVariables(String filePath) throws IOException {
        try (NetcdfFile ncfile = NetcdfFiles.open(filePath)) {
            SimulationLogger.info("Scanning NetCDF: %s", filePath);
            for (ucar.nc2.Variable var : ncfile.getVariables()) {
                String varName = var.getShortName();
                // Load only climate‑relevant variables
                if (isClimateVariable(varName)) {
                    SimulationLogger.info("Loading variable: %s", varName);
                    InterpolatedRasterLayer layer = new InterpolatedRasterLayer(varName, timeManager);
                    layer.loadFromNetCDF(filePath, varName);
                    layers.put(varName, layer);
                }
            }
        }
    }

    private boolean isClimateVariable(String name) {
        return name.equals(TEMPERATURE_2M) || name.equals(TOTAL_PRECIPITATION) ||
               name.equals(SURFACE_PRESSURE) || name.equals(U_WIND) || name.equals(V_WIND);
    }

    /**
     * Load a specific set of variables (useful for non‑standard files).
     */
    public void loadNetCDFDataset(String filePath, String... variableNames) throws IOException {
        for (String varName : variableNames) {
            SimulationLogger.info("Loading variable: %s", varName);
            InterpolatedRasterLayer layer = new InterpolatedRasterLayer(varName, timeManager);
            layer.loadFromNetCDF(filePath, varName);
            layers.put(varName, layer);
        }
    }

    /**
     * Convenience: load the full ERA5 standard dataset.
     */
    public void loadERA5Dataset(String filePath) throws IOException {
        String[] vars = {TEMPERATURE_2M, TOTAL_PRECIPITATION, SURFACE_PRESSURE, U_WIND, V_WIND};
        loadNetCDFDataset(filePath, vars);
    }

    /**
     * Get the combined geographic bounds of the first loaded layer.
     */
    public Rectangle2D getBounds() {
        if (layers.isEmpty()) return null;
        InterpolatedRasterLayer first = layers.values().iterator().next();
        return new Rectangle2D.Double(
            first.getMinLon(), first.getMinLat(),
            first.getMaxLon() - first.getMinLon(),
            first.getMaxLat() - first.getMinLat()
        );
    }

    /**
     * Get raw value for a variable at given coordinates (interpolated in time).
     */
    public double getValue(String variableName, double lon, double lat) {
        InterpolatedRasterLayer layer = layers.get(variableName);
        if (layer == null) {
            SimulationLogger.warning("Variable not loaded: %s", variableName);
            return Double.NaN;
        }
        return layer.getValueAt(lon, lat);
    }

    /**
     * Get temperature in Celsius (converts from Kelvin).
     */
    public double getTemperatureCelsius(double lon, double lat) {
        double kelvin = getValue(TEMPERATURE_2M, lon, lat);
        return kelvin - 273.15;
    }

    /**
     * Get precipitation in mm per hour (converts from metres per time step).
     */
    public double getPrecipitationMmHour(double lon, double lat) {
        double precipM = getValue(TOTAL_PRECIPITATION, lon, lat);
        return precipM * 1000.0;
    }

    /**
     * Get wind speed (m/s) from u and v components.
     */
    public double getWindSpeed(double lon, double lat) {
        double u = getValue(U_WIND, lon, lat);
        double v = getValue(V_WIND, lon, lat);
        return Math.sqrt(u*u + v*v);
    }

    /**
     * Get wind direction in degrees from north.
     */
    public double getWindDirection(double lon, double lat) {
        double u = getValue(U_WIND, lon, lat);
        double v = getValue(V_WIND, lon, lat);
        double dir = Math.toDegrees(Math.atan2(u, v));
        if (dir < 0) dir += 360;
        return dir;
    }

    public InterpolatedRasterLayer getLayer(String variableName) {
        return layers.get(variableName);
    }

    public Map<String, InterpolatedRasterLayer> getLayers() {
        return new HashMap<>(layers);
    }

    public void printStatistics() {
        SimulationLogger.info("=== Climate Dataset Statistics ===");
        for (Map.Entry<String, InterpolatedRasterLayer> e : layers.entrySet()) {
            e.getValue().printStatistics();
        }
    }
}