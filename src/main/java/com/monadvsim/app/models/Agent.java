package com.monadvsim.app.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.locationtech.jts.geom.Envelope;
import java.awt.geom.Point2D;
import java.io.Serializable;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;


/**
 * Represents an autonomous agent moving through a GIS environment.
 * Uses a steering-based movement model with terrain validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Agent implements Serializable {
    private static final long serialVersionUID = 1L;

    private double x, y, vx, vy;
    
    // transient: history is re-initialized after loading to save disk space
    private transient List<Point2D.Double> history = new ArrayList<>();

    public Agent() {} 

    public Agent(double x, double y) {
        this.x = x; 
        this.y = y;
        // Start with a random direction
        double angle = Math.random() * 2 * Math.PI;
        this.vx = Math.cos(angle); 
        this.vy = Math.sin(angle);
    }

    /**
     * Executes one simulation step with environmental awareness.
     */
    public void step(Project project, AgentLayer layer, Envelope bounds) {
        // 1. Scale velocity based on CRS (Degrees vs Meters)
        double unitScale = (project.getCrsCode() != null && project.getCrsCode().contains("3857")) ? 1000.0 : 0.0001;
        double speed = layer.getRule().getMaxSpeed() * unitScale;
        // 2. BIOLOGICAL WANDER: Add a small random steering force (Brownian-ish motion)
        double wanderAngle = (Math.random() - 0.5) * 0.2; // Adjust for more/less zig-zag
        double currentAngle = Math.atan2(vy, vx) + wanderAngle;
        vx = Math.cos(currentAngle);
        vy = Math.sin(currentAngle);
        // 3. SENSING: Query environment for repulsion/attraction
        Map<String, Object> surroundings = layer.probeEnvironment(this.x, this.y, project);
        for (Object value : surroundings.values()) {
            if (value instanceof Double val && val > 100.0) {
                // If value is high (e.g. high elevation or toxicity), turn sharply
                currentAngle += Math.PI / 4; 
                vx = Math.cos(currentAngle);
                vy = Math.sin(currentAngle);
            }
        }
        // 4. PREDICTIVE MOVEMENT & TERRAIN VALIDATION
        double nextX = this.x + (this.vx * speed);
        double nextY = this.y + (this.vy * speed);
        // Check if the terrain at the next coordinate is allowed by the rules
        if (layer.validateMovement(nextX, nextY)) {
            this.x = nextX;
            this.y = nextY;
        } else {
            // Collision: Pick a new random direction if we hit a "wall" or "water"
            double newAngle = Math.random() * 2 * Math.PI;
            this.vx = Math.cos(newAngle);
            this.vy = Math.sin(newAngle);
        }
        // 5. BOUNDARIES: World containment
        handleBoundaries(bounds, layer.isWrapAround());
        // 6. TRACKING: Update history for breadcrumbs/trails
        updateHistory();
    }

    private void handleBoundaries(Envelope bounds, boolean wrap) {
        if (wrap) {
            if (x < bounds.getMinX()) x = bounds.getMaxX();
            else if (x > bounds.getMaxX()) x = bounds.getMinX();
            if (y < bounds.getMinY()) y = bounds.getMaxY();
            else if (y > bounds.getMaxY()) y = bounds.getMinY();
        } else {
            if (x < bounds.getMinX() || x > bounds.getMaxX()) {
                vx *= -1;
                x = Math.max(bounds.getMinX(), Math.min(x, bounds.getMaxX()));
            }
            if (y < bounds.getMinY() || y > bounds.getMaxY()) {
                vy *= -1;
                y = Math.max(bounds.getMinY(), Math.min(y, bounds.getMaxY()));
            }
        }
    }

    private void updateHistory() {
        if (history == null) history = new ArrayList<>();
        history.add(new Point2D.Double(x, y));
        // Keep trail length manageable for performance
        if (history.size() > 20) {
            history.remove(0);
        }
    }
    
    public Map<String, Object> probeEnvironment(double x,double y, Project project){
      return null;
    }


    // --- Getters & Setters ---
    public double getX() { return x; }
    public double getY() { return y; }
    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }
    
    public List<Point2D.Double> getHistory() {
        if (history == null) history = new ArrayList<>();
        return history;
    }
}
