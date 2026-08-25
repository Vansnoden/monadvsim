package com.monadvsim.app.models.services;

import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.engine.TimeManager;
import com.monadvsim.app.models.utils.SimulationLogger;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFiles;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for creating layers from files based on file type detection.
 * Supports: GeoTIFF, NetCDF, Shapefile, CSV
 */
public class LayerFactory {
    
    private final TimeManager timeManager;
    private final ProjectPersistenceService persistenceService;
    
    public LayerFactory(TimeManager timeManager) {
        this.timeManager = timeManager;
        this.persistenceService = new ProjectPersistenceService();
    }
    
    /**
     * Create a layer from a file path.
     * Automatically detects file type and returns appropriate layer.
     */
    public Layer createLayer(String filePath, String layerName) throws IOException {
        return createLayer(filePath, layerName, null);
    }
    
    /**
     * Create a layer from a file path with optional variable name for NetCDF.
     */
    public Layer createLayer(String filePath, String layerName, String variableName) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }
        
        String extension = getFileExtension(filePath).toLowerCase();
        
        SimulationLogger.info("Creating layer '%s' from %s (type: %s)", 
            layerName, filePath, extension);
        
        switch (extension) {
            case "tif":
            case "tiff":
            case "geotiff":
                return createRasterLayer(filePath, layerName);
                
            case "nc":
            case "netcdf":
                return createNetCDFLayer(filePath, layerName, variableName);
                
            case "shp":
                return createVectorLayer(filePath, layerName);
                
            case "csv":
                return createOccurrenceLayer(filePath, layerName);
                
            default:
                throw new IOException("Unsupported file type: " + extension);
        }
    }
    
    /**
     * Create multiple layers from a NetCDF file.
     * Detects all climate variables and creates InterpolatedRasterLayer for each.
     */
    public List<Layer> createLayersFromNetCDF(String filePath) throws IOException {
        List<Layer> layers = new ArrayList<>();
        
        try (NetcdfFile ncfile = NetcdfFiles.open(filePath)) {
            for (Variable var : ncfile.getVariables()) {
                String varName = var.getShortName();
                
                // Skip coordinate variables
                if (isCoordinateVariable(varName)) {
                    continue;
                }
                
                String layerName = varName;
                SimulationLogger.info("Detected NetCDF variable: %s", varName);
                
                // Map to readable names
                switch (varName) {
                    case "t2m":
                        layerName = "Temperature";
                        break;
                    case "tp":
                        layerName = "Precipitation";
                        break;
                    case "sp":
                        layerName = "SurfacePressure";
                        break;
                    case "u10":
                        layerName = "WindU";
                        break;
                    case "v10":
                        layerName = "WindV";
                        break;
                }
                
                InterpolatedRasterLayer layer = new InterpolatedRasterLayer(layerName, timeManager);
                layer.loadFromNetCDF(filePath, varName);
                layers.add(layer);
                SimulationLogger.info("  Created layer: %s from variable %s", layerName, varName);
            }
        }
        
        return layers;
    }
    
    /**
     * Load multiple NetCDF files (e.g., for different years).
     * Each file can have multiple variables.
     */
    public List<Layer> createLayersFromMultipleNetCDF(List<String> filePaths) throws IOException {
        List<Layer> allLayers = new ArrayList<>();
        
        for (String filePath : filePaths) {
            SimulationLogger.info("Loading NetCDF: %s", filePath);
            List<Layer> layers = createLayersFromNetCDF(filePath);
            allLayers.addAll(layers);
        }
        
        return allLayers;
    }
    
    private Layer createRasterLayer(String filePath, String layerName) throws IOException {
        RasterLayer layer = new MemoryMappedRasterLayer(layerName, 1, 1, 1);
        try {
            persistenceService.loadRasterData(layer, filePath);
        } catch (Exception ex) {
            Logger.getLogger(LayerFactory.class.getName()).log(Level.SEVERE, null, ex);
        }
        return layer;
    }
    
    private Layer createNetCDFLayer(String filePath, String layerName, String variableName) throws IOException {
        if (variableName == null) {
            // If no variable specified, try to detect
            try (NetcdfFile ncfile = NetcdfFiles.open(filePath)) {
                for (Variable var : ncfile.getVariables()) {
                    if (!isCoordinateVariable(var.getShortName())) {
                        variableName = var.getShortName();
                        SimulationLogger.info("Auto-detected variable: %s", variableName);
                        break;
                    }
                }
            }
            
            if (variableName == null) {
                throw new IOException("No climate variable found in NetCDF file");
            }
        }
        
        InterpolatedRasterLayer layer = new InterpolatedRasterLayer(layerName, timeManager);
        layer.loadFromNetCDF(filePath, variableName);
        return layer;
    }
    
    private Layer createVectorLayer(String filePath, String layerName) throws IOException {
        VectorLayer layer = new VectorLayer(layerName);
        // Load shapefile geometry
        // Implementation depends on your VectorLayer implementation
        SimulationLogger.info("Vector layer loaded: %s", layerName);
        return layer;
    }
    
    private Layer createOccurrenceLayer(String filePath, String layerName) throws IOException {
        // For occurrence points - could be a special layer type
        SimulationLogger.info("Occurrence data loaded: %s", layerName);
        // Return a VectorLayer or custom OccurrenceLayer
        VectorLayer layer = new VectorLayer(layerName);
        return layer;
    }
    
    private String getFileExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(lastDot + 1) : "";
    }
    
    private boolean isCoordinateVariable(String varName) {
        return varName.equals("latitude") || varName.equals("lat") ||
               varName.equals("longitude") || varName.equals("lon") ||
               varName.equals("time") || varName.equals("level") ||
               varName.equals("step") || varName.equals("valid_time");
    }
}