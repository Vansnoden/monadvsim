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
 */
public class LayerFactory {
    
    private final TimeManager timeManager;
    private final ProjectPersistenceService persistenceService;
    
    public LayerFactory(TimeManager timeManager) {
        this.timeManager = timeManager;
        this.persistenceService = new ProjectPersistenceService();
    }
    
    public Layer createLayer(String filePath, String layerName) throws IOException {
        return createLayer(filePath, layerName, null);
    }
    
    public Layer createLayer(String filePath, String layerName, String variableName) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }
        
        String extension = getFileExtension(filePath).toLowerCase();
        SimulationLogger.info("Creating layer '%s' from %s (type: %s)", layerName, filePath, extension);
        
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
    
    public List<Layer> createLayersFromNetCDF(String filePath) throws IOException {
        List<Layer> layers = new ArrayList<>();
        
        try (NetcdfFile ncfile = NetcdfFiles.open(filePath)) {
            for (Variable var : ncfile.getVariables()) {
                String varName = var.getShortName();
                if (isCoordinateVariable(varName)) continue;
                
                String layerName = switch (varName) {
                    case "t2m" -> "Temperature";
                    case "tp" -> "Precipitation";
                    case "sp" -> "SurfacePressure";
                    case "u10" -> "WindU";
                    case "v10" -> "WindV";
                    default -> varName;
                };
                
                InterpolatedRasterLayer layer = new InterpolatedRasterLayer(layerName, timeManager);
                layer.loadFromNetCDF(filePath, varName);
                layers.add(layer);
            }
        }
        return layers;
    }
    
    public List<Layer> createLayersFromMultipleNetCDF(List<String> filePaths) throws IOException {
        List<Layer> allLayers = new ArrayList<>();
        for (String filePath : filePaths) {
            allLayers.addAll(createLayersFromNetCDF(filePath));
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
            try (NetcdfFile ncfile = NetcdfFiles.open(filePath)) {
                for (Variable var : ncfile.getVariables()) {
                    if (!isCoordinateVariable(var.getShortName())) {
                        variableName = var.getShortName();
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
    
    private Layer createVectorLayer(String filePath, String layerName) {
        return new VectorLayer(layerName);
    }
    
    private Layer createOccurrenceLayer(String filePath, String layerName) {
        return new VectorLayer(layerName);
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