package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.SpatialRegistry;
import com.monadvsim.app.models.engine.TimeManager;
import java.util.ArrayList;
import java.util.List;
import java.io.Serializable;


public class Project implements Serializable {
    private String name;
    private String crsCode = "EPSG:4326";
    private List<Layer> layers; // The Environment
    private transient SpatialRegistry spatialRegistry; // all Agents State
    private double defaultAmbientTemp = 295.15; // 22 Celsius in Kelvin
    
    public Project(String name) {
        this.name = name;
        this.layers = new ArrayList<>();
    }

    /**
     * Updates all time-sensitive layers (like Rasters) 
     * based on the current simulation time index.
     */
    public void updateEnvironment(int timeFrameIndex) {
        for (Layer layer : layers) {
            if (layer instanceof RasterLayer rl) {
                rl.setActiveFrame(timeFrameIndex);
            }
        }
    }

    /**
     * Helper to retrieve only the Agent layers for the update loop.
     */
    public List<AgentLayer> getAgentLayers() {
        List<AgentLayer> agentLayers = new ArrayList<>();
        for (Layer l : layers) {
            if (l instanceof AgentLayer al) {
                agentLayers.add(al);
            }
        }
        return agentLayers;
    }

    /**
     * Helper to retrieve a specific Raster (e.g., "Population")
     */
    public RasterLayer getRasterByName(String name) {
        return layers.stream()
                .filter(l -> l instanceof RasterLayer && l.getName().equalsIgnoreCase(name))
                .map(l -> (RasterLayer) l)
                .findFirst()
                .orElse(null);
    }
    
    public void addRasterLayer(RasterLayer rasterLayer) {
        if (rasterLayer != null) {
            this.layers.add(rasterLayer);
        }
    }
    
    public void addAgentLayer(AgentLayer agentLayer) {
        if (agentLayer != null) {
            this.layers.add(agentLayer);
        }
    }
    
    public double getTemperatureAt(double x, double y) {
        RasterLayer tempLayer = getRasterByName("Temperature");
        if (tempLayer != null) {
            return tempLayer.getValueAt(x, y);
        }
        return defaultAmbientTemp;
    }

    // Standard Getters/Setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCrsCode() {
        return crsCode;
    }

    public void setCrsCode(String crsCode) {
        this.crsCode = crsCode;
    }

    public List<Layer> getLayers() {
        return layers;
    }

    public void setLayers(List<Layer> layers) {
        this.layers = layers;
    }

    public SpatialRegistry getSpatialRegistry() {
        return spatialRegistry;
    }

    public void setSpatialRegistry(SpatialRegistry spatialRegistry) {
        this.spatialRegistry = spatialRegistry;
    }
    
}