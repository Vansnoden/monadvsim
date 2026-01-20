package com.monadvsim.app.models;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.dataformat.xml.annotation.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentRule implements Serializable {

  private static final long serialVersionUID = 1L;
  private Map<String, Boolean> behavior = new HashMap<>();
  private Map<String, Boolean> survivalMap = new HashMap<>();
  private Map<String, Double> lethalMaxThresholds = new HashMap<>();
  private Map<String, Double> lethalMinThresholds = new HashMap<>();
  private Map<String, Double> attractantThresholds = new HashMap<>();
  private Map<String, Double> repellentThresholds = new HashMap<>();
  private Map<String, Double> speedModifiers = new HashMap<>();
  private double maxSpeed = 0.05;

  public AgentRule() {
    // Default Movement Rules
    behavior.put("land", true);
    behavior.put("water", false);
    behavior.put("empty", false);
    behavior.put("forest", true);
    behavior.put("urban", true);
    
    // Default Survival Rules
    survivalMap.put("land", true);
    survivalMap.put("water", false);
    survivalMap.put("empty", false);
    survivalMap.put("forest", true);
    survivalMap.put("urban", true);
    
    // Default Speed Modifiers
    speedModifiers.put("land", 1.0);
    speedModifiers.put("water", 0.0);
    speedModifiers.put("empty", 0.0);
    speedModifiers.put("forest", 0.5);
    speedModifiers.put("urban", 0.7);
  }

  public boolean checkSurvival(String layerName, Object value) {
    // 1. Check Vector Category Survival
    if (value instanceof String category) {
      return survivalMap.getOrDefault(category.toLowerCase(), true);
    }
    
    // 2. Check Raster Numeric Survival
    if (value instanceof Double val) {
      if (lethalMaxThresholds.containsKey(layerName) 
          && val > lethalMaxThresholds.get(layerName)) {
        return false;
      }
      if (lethalMinThresholds.containsKey(layerName) 
          && val < lethalMinThresholds.get(layerName)) {
        return false;
      }
    }
    return true;
  }

  public boolean isAllowed(String terrain) {
    return behavior.getOrDefault(terrain.toLowerCase(), false);
  }

  public double getSpeedModifier(String terrainType) {
    return speedModifiers.getOrDefault(terrainType.toLowerCase(), 1.0);
  }

  public boolean isAttracted(String layerName, double value) {
    Double threshold = attractantThresholds.get(layerName);
    return threshold != null && value > threshold;
  }

  public boolean isRepelled(String layerName, double value) {
    Double threshold = repellentThresholds.get(layerName);
    return threshold != null && value > threshold;
  }

  public double[] getEnvironmentalResponse(String layerName, double value, 
                                         double x, double y) {
    double[] response = {0, 0};
    
    if (isAttracted(layerName, value)) {
      // Move toward higher values
      response[0] = 1.0; // Default attraction direction
      response[1] = 0.0;
      return response;
    } else if (isRepelled(layerName, value)) {
      // Move away from high values
      response[0] = -1.0; // Default repulsion direction
      response[1] = 0.0;
      return response;
    }
    
    return response;
  }

  public void setBehavior(String terrainType, boolean allowed) {
    behavior.put(terrainType.toLowerCase(), allowed);
  }

  // --- Getters and Setters ---
  public double getMaxSpeed() { 
    return maxSpeed; 
  }

  public void setMaxSpeed(double maxSpeed) { 
    this.maxSpeed = maxSpeed; 
  }

  @JsonProperty("behavior")
  @JacksonXmlElementWrapper(localName = "behavior")
  @JacksonXmlProperty(localName = "behaviorEntry")
  public Map<String, Boolean> getBehaviorMap() { 
    return behavior; 
  }

  public void setBehaviorMap(Map<String, Boolean> map) { 
    this.behavior = map; 
  }

  @JsonProperty("lethalMaxThresholds")
  @JacksonXmlElementWrapper(localName = "lethalMaxThresholds")
  @JacksonXmlProperty(localName = "lethalMaxEntry")
  public Map<String, Double> getLethalMaxThresholds() { 
    return lethalMaxThresholds; 
  }

  public void setLethalMaxThresholds(Map<String, Double> map) { 
    this.lethalMaxThresholds = map; 
  }

  @JsonProperty("lethalMinThresholds")
  @JacksonXmlElementWrapper(localName = "lethalMinThresholds")
  @JacksonXmlProperty(localName = "lethalMinEntry")
  public Map<String, Double> getLethalMinThresholds() { 
    return lethalMinThresholds; 
  }

  public void setLethalMinThresholds(Map<String, Double> map) { 
    this.lethalMinThresholds = map; 
  }

  @JsonProperty("survivalMap")
  @JacksonXmlElementWrapper(localName = "survivalMap")
  @JacksonXmlProperty(localName = "survivalEntry")
  public Map<String, Boolean> getSurvivalMap() { 
    return survivalMap; 
  }

  public void setSurvivalMap(Map<String, Boolean> map) { 
    this.survivalMap = map; 
  }

  @JsonProperty("attractantThresholds")
  @JacksonXmlElementWrapper(localName = "attractantThresholds")
  @JacksonXmlProperty(localName = "attractantEntry")
  public Map<String, Double> getAttractantThresholds() { 
    return attractantThresholds; 
  }

  public void setAttractantThresholds(Map<String, Double> map) { 
    this.attractantThresholds = map; 
  }

  @JsonProperty("repellentThresholds")
  @JacksonXmlElementWrapper(localName = "repellentThresholds")
  @JacksonXmlProperty(localName = "repellentEntry")
  public Map<String, Double> getRepellentThresholds() { 
    return repellentThresholds; 
  }

  public void setRepellentThresholds(Map<String, Double> map) { 
    this.repellentThresholds = map; 
  }

  @JsonProperty("speedModifiers")
  @JacksonXmlElementWrapper(localName = "speedModifiers")
  @JacksonXmlProperty(localName = "speedModifierEntry")
  public Map<String, Double> getSpeedModifiers() { 
    return speedModifiers; 
  }

  public void setSpeedModifiers(Map<String, Double> map) { 
    this.speedModifiers = map; 
  }

  // Helper methods
  public void addTerrainType(String terrainType, boolean traversable, 
                           boolean survivable, double speedModifier) {
    setBehavior(terrainType, traversable);
    survivalMap.put(terrainType.toLowerCase(), survivable);
    speedModifiers.put(terrainType.toLowerCase(), speedModifier);
  }

  public void addRasterResponse(String layerName, double attractThreshold, 
                              double repelThreshold, double lethalMax, 
                              double lethalMin) {
    if (attractThreshold > 0) {
      attractantThresholds.put(layerName, attractThreshold);
    }
    if (repelThreshold > 0) {
      repellentThresholds.put(layerName, repelThreshold);
    }
    if (lethalMax < Double.MAX_VALUE) {
      lethalMaxThresholds.put(layerName, lethalMax);
    }
    if (lethalMin > Double.MIN_VALUE) {
      lethalMinThresholds.put(layerName, lethalMin);
    }
  }

  public boolean hasEnvironmentalResponses() {
    return !attractantThresholds.isEmpty() || !repellentThresholds.isEmpty();
  }

  public String getTerrainSummary() {
    StringBuilder sb = new StringBuilder();
    behavior.forEach((terrain, allowed) -> {
      if (allowed) {
        sb.append(terrain).append(" (speed: ")
          .append(String.format("%.1f", getSpeedModifier(terrain)))
          .append("), ");
      }
    });
    if (sb.length() > 2) {
      sb.setLength(sb.length() - 2);
    }
    return sb.toString();
  }
}
