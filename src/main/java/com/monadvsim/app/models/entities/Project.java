package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.SpatialRegistry;
import com.monadvsim.app.models.engine.TimeManager;
import java.util.ArrayList;
import java.util.List;
import java.io.Serializable;

public class Project implements Serializable {
    private String name;
    private String crsCode = "EPSG:4326"; // e.g., "EPSG:4326"
    private List<String> tokens;
    private List<String> layerNames;
    private double defaultAgentSearchRadius;
    private double defaultHatchingProbability;
    private double defaultAgentStep;
    private int defaultBirthRate; // number of eggs to be layeds
            
    // The Environment
    private List<Layer> layers;
    
    private transient SpatialRegistry spatialRegistry;
    
    public Project(String name) {
        this.name = name;
        this.layers = new ArrayList<>();
    }

    
    public void updateEnvironment(int timeFrameIndex) {
        for (Layer layer : layers) {
            if (layer instanceof RasterLayer rl) {
                rl.setActiveFrame(timeFrameIndex);
            }
        }
    }
    

    public Layer getLayerByName(String layerName){
        for(Layer l: layers){
            if (layerName.equals(l.getName())){
                return l;
            }
        }
        return null;
    }
    
    
    public List<AgentLayer> getAgentLayers() {
        List<AgentLayer> agentLayers = new ArrayList<>();
        for (Layer l : layers) {
            if (l instanceof AgentLayer al) {
                agentLayers.add(al);
            }
        }
        return agentLayers;
    }

    
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

    public List<String> getTokens() {
        return tokens;
    }

    public void setTokens(List<String> tokens) {
        this.tokens = tokens;
    }

    public List<String> getLayerNames() {
        return layerNames;
    }

    public void setLayerNames(List<String> layerNames) {
        this.layerNames = layerNames;
    }

    public double getDefaultAgentSearchRadius() {
        return defaultAgentSearchRadius;
    }

    public void setDefaultAgentSearchRadius(double defaultAgentSearchRadius) {
        this.defaultAgentSearchRadius = defaultAgentSearchRadius;
    }

    public double getDefaultHatchingProbability() {
        return defaultHatchingProbability;
    }

    public void setDefaultHatchingProbability(double defaultHatchingProbability) {
        this.defaultHatchingProbability = defaultHatchingProbability;
    }

    public double getDefaultAgentStep() {
        return defaultAgentStep;
    }

    public void setDefaultAgentStep(double defaultAgentStep) {
        this.defaultAgentStep = defaultAgentStep;
    }

    public int getDefaultBirthRate() {
        return defaultBirthRate;
    }

    public void setDefaultBirthRate(int defaultBirthRate) {
        this.defaultBirthRate = defaultBirthRate;
    }
    
    
    
    
    
}