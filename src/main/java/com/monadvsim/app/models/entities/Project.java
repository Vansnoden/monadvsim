package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.SpatialRegistry;
import com.monadvsim.app.models.engine.TimeManager;
import java.util.ArrayList;
import java.util.List;
import java.io.Serializable;

public class Project implements Serializable {
    private String name;
    private String crsCode = "EPSG:4326"; // e.g., "EPSG:4326"
    
    // The Environment
    private List<Layer> layers;
    
    private transient SpatialRegistry spatialRegistry;
    
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

    // Standard Getters/Setters
    public List<Layer> getLayers() { return layers; }
    public void addLayer(Layer l) { this.layers.add(l); }
    public String getName() { return name; }
    public String getCrsCode() { return crsCode; }
    public void setName(String name) { this.name = name; }
    public void setCrsCode(String crsCode) { this.crsCode = crsCode; }
    public void setSpatialRegistry(SpatialRegistry spatialRegistry) {
        this.spatialRegistry = spatialRegistry;
    }

    public SpatialRegistry getSpatialRegistry() {
        return spatialRegistry;
    }
}