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

    private Map<String, Boolean> behavior = new HashMap<>();
    private double maxSpeed = 0.05; // Base speed factor in degrees/meters per tick
    private transient GeometryFactory geometryFactory = new GeometryFactory();

    public AgentRule() {
        // Default rules: survive on land, blocked by water or empty space
        behavior.put("land", true);
        behavior.put("water", false);
        behavior.put("empty", false); 
    }

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

    public boolean isAllowed(String terrain) {
        if (terrain == null) return behavior.getOrDefault("empty", false);
        return behavior.getOrDefault(terrain.toLowerCase(), false);
    }

    public void setBehavior(String terrainType, boolean allowed) {
        behavior.put(terrainType.toLowerCase(), allowed);
    }

    // --- Getters and Setters ---
    
    public double getMaxSpeed() { return maxSpeed; }
    public void setMaxSpeed(double maxSpeed) { this.maxSpeed = maxSpeed; }
    
    public Map<String, Boolean> getBehaviorMap() { return behavior; }
}
