package com.monadvsim.app.models;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Defines the physical laws and constraints for agents in a specific layer.
 * Integrated with the spatial index for real-time terrain evaluation.
 */
public class AgentRule implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // Movement: Is this terrain type traversable? (Vector/Baked Cache)
    private Map<String, Boolean> traversability = new HashMap<>();
    
    // Survival: Does this terrain type kill the agent? (Vector)
    private Map<String, Boolean> survivalMap = new HashMap<>();
    
    // Environmental Thresholds: Value ranges that are lethal (Raster)
    // Key = Layer Name, Value = Double threshold
    private Map<String, Double> lethalMaxThresholds = new HashMap<>();
    private Map<String, Double> lethalMinThresholds = new HashMap<>();

    private Map<String, Boolean> behavior = new HashMap<>();
    private double maxSpeed = 0.05; // Base speed factor in degrees/meters per tick
    private transient GeometryFactory geometryFactory = new GeometryFactory();

    public AgentRule() {
        // Default Movement Rules
        traversability.put("land", true);
        traversability.put("water", false);
        traversability.put("empty", false);
        // Default Survival Rules (everything is safe by default)
        survivalMap.put("land", true);
        survivalMap.put("water", false); // Drown in water
    }
    
    /**
     * Evaluates if the agent stays alive based on a sensed value from a layer.
     */
    public boolean checkSurvival(String layerName, Object value) {
        // 1. Check Vector Category Survival
        if (value instanceof String category) {
            return survivalMap.getOrDefault(category.toLowerCase(), true);
        }

        // 2. Check Raster Numeric Survival (e.g., Temperature, Elevation)
        if (value instanceof Double val) {
            if (lethalMaxThresholds.containsKey(layerName) && val > lethalMaxThresholds.get(layerName)) {
                return false; // Too high
            }
            if (lethalMinThresholds.containsKey(layerName) && val < lethalMinThresholds.get(layerName)) {
                return false; // Too low
            }
        }
        return true;
    }
    
    public boolean isAllowed(String terrain) {
        return traversability.getOrDefault(terrain.toLowerCase(), false);
    }
    
    public void setLethalMax(String layerName, double max) { lethalMaxThresholds.put(layerName, max); }
    public void setTraversable(String terrain, boolean allowed) { traversability.put(terrain, allowed); }
    public void setSurvivable(String terrain, boolean survivable) { survivalMap.put(terrain, survivable); }

    /**
     * Checks if a specific coordinate is traversable based on the baked terrain data.
     */
    public boolean isMovementAllowed(double x, double y, STRtree spatialIndex) {
        if (spatialIndex == null || spatialIndex.isEmpty()) {
            // If no terrain is loaded, we use the "empty" rule
            return behavior.getOrDefault("empty", false);
        }

        if (geometryFactory == null) geometryFactory = new GeometryFactory();
        Point p = geometryFactory.createPoint(new Coordinate(x, y));

        // Query the spatial index for the terrain feature at this point
        List<?> results = spatialIndex.query(p.getEnvelopeInternal());

        for (Object obj : results) {
            // This assumes your 'Baking' process stores 'CachedFeature' objects in the tree
            if (obj instanceof CachedFeature cf) {
                if (cf.getGeometry().contains(p)) {
                    // Check the specific behavior for this feature type (e.g., "forest", "water")
                    String terrainType = cf.getTerrainLabel().toLowerCase();
                    return behavior.getOrDefault(terrainType, false);
                }
            }
        }

        // If no feature was found under the agent, use the "empty" behavior
        return behavior.getOrDefault("empty", false);
    }

    public void setBehavior(String terrainType, boolean allowed) {
        behavior.put(terrainType.toLowerCase(), allowed);
    }

    // --- Getters and Setters ---
    
    public double getMaxSpeed() { return maxSpeed; }
    public void setMaxSpeed(double maxSpeed) { this.maxSpeed = maxSpeed; }
    
    public Map<String, Boolean> getBehaviorMap() { return behavior; }
}
