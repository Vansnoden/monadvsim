package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.utils.SimulationLogger;

import com.monadvsim.app.models.engine.LifecycleModel;
import com.monadvsim.app.models.engine.SpatialRegistry;
import java.util.ArrayList;
import java.util.List;
import java.io.Serializable;

public class Project implements Serializable {
    private String name;
    private String crsCode = "EPSG:4326";
    private List<String> tokens;
    private List<String> layerNames;
    private int defaultMaxAgentAge;
    private double defaultAgentSearchRadius;
    private double defaultHatchingProbability;
    private double defaultAgentStep;
    private int defaultBirthRate;
    private transient LifecycleModel lifecycleModel;

    // Stage-specific max-age multipliers (as fractions of defaultMaxAgentAge).
    private double maxAgeMultiplierEgg   = 0.05;
    private double maxAgeMultiplierLarva = 0.45;
    private double maxAgeMultiplierPupa  = 0.15;
    private double maxAgeMultiplierAdult = 0.35;

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

    public Layer getLayerByName(String layerName) {
        for (Layer l : layers) {
            if (layerName.equals(l.getName())) return l;
        }
        return null;
    }

    public List<AgentLayer> getAgentLayers() {
        List<AgentLayer> agentLayers = new ArrayList<>();
        for (Layer l : layers) {
            if (l instanceof AgentLayer al) agentLayers.add(al);
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

    public List<Layer> getLayers() { return layers; }
    public void addLayer(Layer l) { this.layers.add(l); }
    public String getName() { return name; }
    public String getCrsCode() { return crsCode; }
    public void setName(String name) { this.name = name; }
    public void setCrsCode(String crsCode) { this.crsCode = crsCode; }
    public void setSpatialRegistry(SpatialRegistry spatialRegistry) {
        this.spatialRegistry = spatialRegistry;
    }
    public SpatialRegistry getSpatialRegistry() { return spatialRegistry; }
    public List<String> getTokens() { return tokens; }
    public void setTokens(List<String> tokens) { this.tokens = tokens; }
    public List<String> getLayerNames() { return layerNames; }
    public void setLayerNames(List<String> layerNames) { this.layerNames = layerNames; }
    public double getDefaultAgentSearchRadius() { return defaultAgentSearchRadius; }
    public void setDefaultAgentSearchRadius(double v) { this.defaultAgentSearchRadius = v; }
    public double getDefaultHatchingProbability() { return defaultHatchingProbability; }
    public void setDefaultHatchingProbability(double v) { this.defaultHatchingProbability = v; }
    public double getDefaultAgentStep() { return defaultAgentStep; }
    public void setDefaultAgentStep(double v) { this.defaultAgentStep = v; }
    public int getDefaultBirthRate() { return defaultBirthRate; }
    public void setDefaultBirthRate(int v) { this.defaultBirthRate = v; }
    public int getDefaultMaxAgentAge() { return defaultMaxAgentAge; }
    public void setDefaultMaxAgentAge(int v) { this.defaultMaxAgentAge = v; }
    public void setLifecycleModel(LifecycleModel model) { this.lifecycleModel = model; }
    public LifecycleModel getLifecycleModel() { return lifecycleModel; }

    // ---- Max-age multipliers -------------------------------------------
    public double getMaxAgeMultiplierEgg()   { return maxAgeMultiplierEgg; }
    public double getMaxAgeMultiplierLarva() { return maxAgeMultiplierLarva; }
    public double getMaxAgeMultiplierPupa()  { return maxAgeMultiplierPupa; }
    public double getMaxAgeMultiplierAdult() { return maxAgeMultiplierAdult; }

    public void setMaxAgeMultipliers(double egg, double larva, double pupa, double adult) {
        this.maxAgeMultiplierEgg   = egg;
        this.maxAgeMultiplierLarva = larva;
        this.maxAgeMultiplierPupa  = pupa;
        this.maxAgeMultiplierAdult = adult;
    }
}