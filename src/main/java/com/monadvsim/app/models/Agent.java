package com.monadvsim.app.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.locationtech.jts.geom.Envelope;
import java.awt.geom.Point2D;
import java.io.Serializable;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.dataformat.xml.annotation.*;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.geotools.api.feature.simple.SimpleFeature; 


/**
 * Represents an autonomous agent moving through a GIS environment.
 * Uses a steering-based movement model with terrain validation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Agent implements Serializable {
  private static final long serialVersionUID = 1L;
  private boolean alive = true;
  private double x, y, vx, vy;
  private static final int MAX_HISTORY = 20;
  
  // We need a GeometryFactory to create Points for spatial queries
  @JsonIgnore
  private static final GeometryFactory gf = new GeometryFactory(); 

  @JsonIgnore
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
    if (!alive) return;
    // 1. Get speed from the primary rule
    double speed = layer.getPrimaryRule() != null ? layer.getPrimaryRule().getMaxSpeed() : 0.05;
    // 2. Calculate potential move (Brownian motion + velocity)
    double nextX = x + (vx * speed) + (Math.random() - 0.5) * (speed * 0.5);
    double nextY = y + (vy * speed) + (Math.random() - 0.5) * (speed * 0.5);
    // 3. Handle Map Wrap-around or Bounce
    if (layer.isWrapAround()) {
      if (nextX < bounds.getMinX()) nextX = bounds.getMaxX();
      if (nextX > bounds.getMaxX()) nextX = bounds.getMinX();
      if (nextY < bounds.getMinY()) nextY = bounds.getMaxY();
      if (nextY > bounds.getMaxY()) nextY = bounds.getMaxY();
    }
    // 4. SENSE & THINK: Validate Movement (Collision)
    if (layer.validateMovement(nextX, nextY)) {
      this.x = nextX;
      this.y = nextY;
      // Manage history for trails
      if (layer.isTrailsEnabled()) {
        history.add(new Point2D.Double(x, y));
        if (history.size() > MAX_HISTORY) history.remove(0);
      }
    } else {
      // Change direction on collision
      double angle = Math.random() * Math.PI * 2;
      this.vx = Math.cos(angle);
      this.vy = Math.sin(angle);
    }
    // 5. SURVIVAL: Check Environmental Risks
    Map<String, Object> env = layer.probeEnvironment(x, y, project);
    if (!layer.checkSurvival(env)) {
      this.alive = false;
    }
  }
  
  private void reverseDirection() {
    this.vx *= -1;
    this.vy *= -1;
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
    
  public Map<String, Object> probeEnvironment(double x, double y, Project project) {
    Map<String, Object> readings = new HashMap<>();
    // Creating a JTS Point to query Vector layers
    Point p = gf.createPoint(new Coordinate(x, y));
    for (Layer l : project.getLayers()) {
      if (!l.isVisible() || l instanceof AgentLayer) continue;

      if (l instanceof RasterLayer rl) {
        readings.put(l.getName(), rl.getValueAt(x, y, project.getProjectFile()));
      } 
      else if (l instanceof VectorLayer vl) {
        // Requires org.geotools.api.feature.simple.SimpleFeature
        SimpleFeature feature = vl.getFeatureAt(p, project.getProjectFile());
        if (feature != null) {
          readings.put(l.getName(), feature.getAttribute("type")); 
        }
      }
    }
    return readings;
  }


  // --- Getters & Setters ---
  public boolean isAlive() { return alive; }
  public void setAlive(boolean alive) { this.alive = alive; }
  public double getX() { return x; }
  public double getY() { return y; }
  public void setX(double x) { this.x = x; }
  public void setY(double y) { this.y = y; }
    
  @JsonIgnore
  public List<Point2D.Double> getHistory() {
    if (history == null) history = new ArrayList<>();
    return history;
  }
}
