package com.monadvsim.app.models;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.dataformat.xml.annotation.*;

/**
 * Defines the physical laws and constraints for agents in a specific layer.
 */
public class AgentRule implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // Movement: Is this terrain type traversable? 
    @JacksonXmlProperty(localName = "behaviorEntry")
    @JacksonXmlElementWrapper(localName = "behavior")
    private Map<String, Boolean> behavior = new HashMap<>();
    
    // Survival: Does this terrain type kill the agent? (Vector)
    @JacksonXmlProperty(localName = "survivalEntry")
    @JacksonXmlElementWrapper(localName = "survivalMap")
    private Map<String, Boolean> survivalMap = new HashMap<>();
    
    // Environmental Thresholds: Value ranges that are lethal (Raster)
    @JacksonXmlProperty(localName = "lethalMaxEntry")
    @JacksonXmlElementWrapper(localName = "lethalMaxThresholds")
    private Map<String, Double> lethalMaxThresholds = new HashMap<>();
    
    @JacksonXmlProperty(localName = "lethalMinEntry")
    @JacksonXmlElementWrapper(localName = "lethalMinThresholds")
    private Map<String, Double> lethalMinThresholds = new HashMap<>();

    private double maxSpeed = 0.05; 

    public AgentRule() {
        // Default Movement Rules
        behavior.put("land", true);
        behavior.put("water", false);
        behavior.put("empty", false);
        
        // Default Survival Rules
        survivalMap.put("land", true);
        survivalMap.put("water", false); 
    }
    
    public boolean checkSurvival(String layerName, Object value) {
        // 1. Check Vector Category Survival
        if (value instanceof String category) {
            return survivalMap.getOrDefault(category.toLowerCase(), true);
        }

        // 2. Check Raster Numeric Survival
        if (value instanceof Double val) {
            if (lethalMaxThresholds.containsKey(layerName) && val > lethalMaxThresholds.get(layerName)) {
                return false; 
            }
            if (lethalMinThresholds.containsKey(layerName) && val < lethalMinThresholds.get(layerName)) {
                return false;
            }
        }
        return true;
    }
    
    public boolean isAllowed(String terrain) {
        return behavior.getOrDefault(terrain.toLowerCase(), false);
    }
    
    public void setBehavior(String terrainType, boolean allowed) {
        behavior.put(terrainType.toLowerCase(), allowed);
    }

    // --- Getters and Setters ---
    public double getMaxSpeed() { return maxSpeed; }
    public void setMaxSpeed(double maxSpeed) { this.maxSpeed = maxSpeed; }
    
    public Map<String, Boolean> getBehaviorMap() { return behavior; }
    public Map<String, Double> getLethalMaxThresholds() { return lethalMaxThresholds; }
    public Map<String, Double> getLethalMinThresholds() { return lethalMinThresholds; }
    public Map<String, Boolean> getSurvivalMap() { return survivalMap; }
}
