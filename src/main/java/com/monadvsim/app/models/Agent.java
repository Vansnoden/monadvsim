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

@JsonIgnoreProperties(ignoreUnknown = true)
public class Agent implements Serializable {
  private static final long serialVersionUID = 1L;
  private boolean alive = true;
  private double x, y, vx, vy;
  private static final int MAX_HISTORY = 20;
  
  @JsonIgnore
  private static final GeometryFactory gf = new GeometryFactory(); 

  @JsonIgnore
  private transient List<Point2D.Double> history = new ArrayList<>();

  public Agent() {}

  public Agent(double x, double y) {
    this.x = x; 
    this.y = y;
    double angle = Math.random() * 2 * Math.PI;
    this.vx = Math.cos(angle); 
    this.vy = Math.sin(angle);
  }

  public void step(Project project, AgentLayer layer, Envelope bounds) {
    if (!alive) return;

    // 1. Probe current environment
    Map<String, Object> currentEnv = probeEnvironment(x, y, project);
    
    // 2. Determine movement based on environment and rules
    double[] movement = calculateMovement(currentEnv, layer.getRules(), bounds, project);
    
    // 3. Apply movement
    double nextX = x + movement[0];
    double nextY = y + movement[1];
    
    // 4. Boundary handling
    nextX = handleBoundaries(nextX, bounds.getMinX(), bounds.getMaxX(), layer.isWrapAround());
    nextY = handleBoundaries(nextY, bounds.getMinY(), bounds.getMaxY(), layer.isWrapAround());
    
    // 5. Validate movement against terrain rules
    if (layer.validateMovement(nextX, nextY)) {
      this.x = nextX;
      this.y = nextY;
      if (layer.isTrailsEnabled()) updateHistory();
      
      // Update velocity based on actual movement
      if (movement[0] != 0 || movement[1] != 0) {
        double magnitude = Math.sqrt(movement[0] * movement[0] + movement[1] * movement[1]);
        this.vx = movement[0] / magnitude;
        this.vy = movement[1] / magnitude;
      }
    } else {
      // If movement is not allowed, try alternative direction
      adjustDirectionOnCollision();
    }
    
    // 6. Survival check based on environment
    if (!layer.checkSurvival(currentEnv)) {
      this.alive = false;
    }
  }

  private double[] calculateMovement(Map<String, Object> environment, 
                                    List<AgentRule> rules, 
                                    Envelope bounds,
                                    Project project) {
    double baseSpeed = 0.001; // Default base speed
    double[] direction = {vx, vy};
    
    if (!rules.isEmpty()) {
      // Use the primary rule for movement
      AgentRule rule = rules.get(0);
      baseSpeed = rule.getMaxSpeed();
      
      // Apply environmental influences
      double[] environmentalResponse = {0, 0};
      double speedModifier = 1.0;
      
      for (Map.Entry<String, Object> entry : environment.entrySet()) {
        String layerName = entry.getKey();
        Object value = entry.getValue();
        
        // Check if this layer affects movement in rules
        if (value instanceof Double numericValue) {
          // Raster layer - apply attraction/repulsion
          double[] layerResponse = rule.getEnvironmentalResponse(layerName, numericValue, x, y);
          environmentalResponse[0] += layerResponse[0];
          environmentalResponse[1] += layerResponse[1];
        } else if (value instanceof String terrainType) {
          // Vector layer - terrain-based movement
          speedModifier *= rule.getSpeedModifier(terrainType);
        }
      }
      
      // Blend environmental response with current direction
      if (environmentalResponse[0] != 0 || environmentalResponse[1] != 0) {
        double envMagnitude = Math.sqrt(
          environmentalResponse[0] * environmentalResponse[0] + 
          environmentalResponse[1] * environmentalResponse[1]
        );
        if (envMagnitude > 0) {
          // Normalize environmental response
          environmentalResponse[0] /= envMagnitude;
          environmentalResponse[1] /= envMagnitude;
        }
        
        // Blend with current direction (70% environment, 30% inertia)
        direction[0] = (environmentalResponse[0] * 0.7) + (direction[0] * 0.3);
        direction[1] = (environmentalResponse[1] * 0.7) + (direction[1] * 0.3);
      }
      
      // Apply speed modifier
      baseSpeed *= speedModifier;
      
      // Add some random exploration
      if (Math.random() < 0.1) { // 10% chance to explore randomly
        direction[0] += (Math.random() - 0.5) * 0.2;
        direction[1] += (Math.random() - 0.5) * 0.2;
      }
    }
    
    // Normalize direction vector
    double magnitude = Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1]);
    if (magnitude > 0) {
      direction[0] /= magnitude;
      direction[1] /= magnitude;
    }
    
    return new double[]{direction[0] * baseSpeed, direction[1] * baseSpeed};
  }

  private double handleBoundaries(double coord, double min, double max, boolean wrap) {
    if (wrap) {
      if (coord < min) return max - (min - coord);
      if (coord > max) return min + (coord - max);
    } else {
      if (coord < min || coord > max) {
        reverseDirection();
        // Keep coordinate within bounds
        return Math.max(min, Math.min(coord, max));
      }
    }
    return coord;
  }

  private void adjustDirectionOnCollision() {
    // Add randomness to direction when hitting an obstacle
    double angleChange = (Math.random() - 0.5) * Math.PI; // ±90 degrees
    double cos = Math.cos(angleChange);
    double sin = Math.sin(angleChange);
    
    double newVx = vx * cos - vy * sin;
    double newVy = vx * sin + vy * cos;
    
    this.vx = newVx;
    this.vy = newVy;
  }

  private void reverseDirection() {
    this.vx *= -1;
    this.vy *= -1;
  }

  private void updateHistory() {
    if (history == null) history = new ArrayList<>();
    history.add(new Point2D.Double(x, y));
    if (history.size() > MAX_HISTORY) {
      history.remove(0);
    }
  }

  public Map<String, Object> probeEnvironment(double x, double y, Project project) {
    Map<String, Object> readings = new HashMap<>();
    
    for (Layer l : project.getLayers()) {
      if (!l.isVisible()) continue;
      
      try {
        if (l instanceof RasterLayer rl) {
          Double value = rl.getValueAt(x, y, project.getProjectFile());
          if (value != null) {
            readings.put(rl.getName(), value);
          }
        } 
        else if (l instanceof VectorLayer vl) {
          Point p = gf.createPoint(new Coordinate(x, y));
          SimpleFeature feature = vl.getFeatureAt(p, project.getProjectFile());
          if (feature != null) {
            Object type = feature.getAttribute("type");
            if (type == null) {
              org.locationtech.jts.geom.Geometry geom = 
                (org.locationtech.jts.geom.Geometry) feature.getDefaultGeometry();
              type = geom.getGeometryType();
            }
            readings.put(vl.getName(), type.toString().toLowerCase());
          } else {
            readings.put(vl.getName(), "empty");
          }
        }
      } catch (Exception e) {
        System.err.println("Error probing layer " + l.getName() + ": " + e.getMessage());
      }
    }
    
    return readings;
  }

  // Getters and Setters
  public boolean isAlive() { 
    return alive; 
  }
  
  public void setAlive(boolean alive) { 
    this.alive = alive; 
  }
  
  public double getX() { 
    return x; 
  }
  
  public double getY() { 
    return y; 
  }
  
  public void setX(double x) { 
    this.x = x; 
  }
  
  public void setY(double y) { 
    this.y = y; 
  }
  
  public double getVx() {
    return vx;
  }
  
  public void setVx(double vx) {
    this.vx = vx;
  }
  
  public double getVy() {
    return vy;
  }
  
  public void setVy(double vy) {
    this.vy = vy;
  }
  
  public double[] getDirection() {
    return new double[]{vx, vy};
  }
  
  @JsonIgnore
  public List<Point2D.Double> getHistory() {
    if (history == null) history = new ArrayList<>();
    return history;
  }
  
  @JsonIgnore
  public void setHistory(List<Point2D.Double> history) {
    this.history = history;
  }
}
