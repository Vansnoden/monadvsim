package com.monadvsim.app.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.locationtech.jts.geom.Envelope;
import java.awt.geom.Point2D;
import java.io.Serializable;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
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
    private static final double MIN_MOVEMENT = 0.000001; // Minimum movement threshold

    @JsonIgnore
    private static final GeometryFactory gf = new GeometryFactory();

    @JsonIgnore
    private transient List<Point2D.Double> history = new ArrayList<>(MAX_HISTORY);

    // Memory optimization: reuse point objects to reduce GC pressure
    @JsonIgnore
    private transient Point cachedPoint = null;
    @JsonIgnore
    private transient Map<String, Object> lastEnvironment = null;
    @JsonIgnore
    private transient double lastProbeX = Double.NaN;
    @JsonIgnore
    private transient double lastProbeY = Double.NaN;
    @JsonIgnore
    private static final double ENVIRONMENT_CACHE_DISTANCE = 0.00001; // ~1 meter in degrees

    public Agent() {
    }

    public Agent(double x, double y) {
        this.x = x;
        this.y = y;
        double angle = Math.random() * 2 * Math.PI;
        this.vx = Math.cos(angle);
        this.vy = Math.sin(angle);
    }

    // Main step method with pre-probed environment
    public void optimizedStep(Project project, AgentLayer layer, Envelope bounds,
            Map<String, Object> environment) {
        if (!alive) {
            return;
        }
        double layerBaseSpeed = layer.getBaseSpeed();
        // 1. Use provided environment (already probed by AgentLayer)
        // 2. Determine movement based on environment and rules
        double[] movement = calculateMovementOptimized(environment, layer.getRules(), bounds,
                project, layerBaseSpeed);

        // Check if movement is negligible
        if (Math.abs(movement[0]) < MIN_MOVEMENT && Math.abs(movement[1]) < MIN_MOVEMENT) {
            // Very small movement, skip to avoid floating point issues
            return;
        }

        // 3. Apply movement
        double nextX = x + movement[0];
        double nextY = y + movement[1];

        // 4. Boundary handling
        nextX = handleBoundariesOptimized(nextX, bounds.getMinX(), bounds.getMaxX(),
                layer.isWrapAround(), movement[0]);
        nextY = handleBoundariesOptimized(nextY, bounds.getMinY(), bounds.getMaxY(),
                layer.isWrapAround(), movement[1]);

        // 5. Validate movement against terrain rules
        if (layer.validateMovement(nextX, nextY)) {
            this.x = nextX;
            this.y = nextY;

            if (layer.isTrailsEnabled()) {
                updateHistoryOptimized();
            }

            // Update velocity based on actual movement (only if significant)
            double moveMagnitude = Math.sqrt(movement[0] * movement[0] + movement[1] * movement[1]);
            if (moveMagnitude > MIN_MOVEMENT) {
                this.vx = movement[0] / moveMagnitude;
                this.vy = movement[1] / moveMagnitude;
            }
        } else {
            // If movement is not allowed, try alternative direction
            adjustDirectionOnCollisionOptimized();
        }

        // 6. Survival check based on environment
        if (!layer.checkSurvival(environment)) {
            this.alive = false;
        }
    }

    public void step(Project project, AgentLayer layer, Envelope bounds) {
        // Use optimized version with fresh environment probe
        Map<String, Object> environment = probeEnvironmentOptimized(x, y, project);
        optimizedStep(project, layer, bounds, environment);
        //System.out.println("Agent Steping : " + x + " ---- " + y);
    }

    private double[] calculateMovementOptimized(Map<String, Object> environment,
            List<AgentRule> rules,
            Envelope bounds,
            Project project,
            double layerBaseSpeed) {
        double baseSpeed = layerBaseSpeed; // Default base speed

        // Reuse direction array to reduce GC
        double[] direction = {vx, vy};

        if (!rules.isEmpty() && !environment.isEmpty()) {
            // Use the primary rule for movement
            AgentRule rule = rules.get(0);
            baseSpeed = rule.getMaxSpeed();

            // Apply environmental influences
            double envX = 0, envY = 0;
            double speedModifier = 1.0;
            int environmentalFactors = 0;

            for (Map.Entry<String, Object> entry : environment.entrySet()) {
                String layerName = entry.getKey();
                Object value = entry.getValue();

                // Check if this layer affects movement in rules
                if (value instanceof Double numericValue) {
                    // Raster layer - apply attraction/repulsion
                    double[] layerResponse = rule.getEnvironmentalResponse(layerName, numericValue, x, y);
                    if (layerResponse[0] != 0 || layerResponse[1] != 0) {
                        envX += layerResponse[0];
                        envY += layerResponse[1];
                        environmentalFactors++;
                    }
                } else if (value instanceof String terrainType) {
                    // Vector layer - terrain-based movement
                    speedModifier *= rule.getSpeedModifier(terrainType);
                }
            }

            // Blend environmental response with current direction
            if (environmentalFactors > 0) {
                // Average environmental response
                envX /= environmentalFactors;
                envY /= environmentalFactors;

                double envMagnitude = Math.sqrt(envX * envX + envY * envY);
                if (envMagnitude > 0) {
                    // Normalize environmental response
                    envX /= envMagnitude;
                    envY /= envMagnitude;
                }

                // Blend with current direction (adjustable blend ratio)
                double environmentWeight = 0.7;
                double inertiaWeight = 0.3;

                direction[0] = (envX * environmentWeight) + (direction[0] * inertiaWeight);
                direction[1] = (envY * environmentWeight) + (direction[1] * inertiaWeight);
            }

            // Apply speed modifier with bounds checking
            baseSpeed = Math.max(0.000001, Math.min(1.0, baseSpeed * speedModifier));

            // Add some random exploration (reduced frequency for performance)
            if (Math.random() < 0.05) { // 5% chance to explore randomly (was 10%)
                direction[0] += (Math.random() - 0.5) * 0.1; // Reduced randomness
                direction[1] += (Math.random() - 0.5) * 0.1;
            }
        }

        // Normalize direction vector (with epsilon check)
        double magnitude = Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1]);
        if (magnitude > 1e-10) { // Use epsilon to avoid division by very small numbers
            direction[0] /= magnitude;
            direction[1] /= magnitude;
        } else {
            // Random direction if magnitude is too small
            double angle = Math.random() * 2 * Math.PI;
            direction[0] = Math.cos(angle);
            direction[1] = Math.sin(angle);
        }

        return new double[]{direction[0] * baseSpeed, direction[1] * baseSpeed};
    }

    private double handleBoundariesOptimized(double coord, double min, double max,
            boolean wrap, double movement) {
        if (wrap) {
            if (coord < min) {
                return max - (min - coord);
            }
            if (coord > max) {
                return min + (coord - max);
            }
        } else {
            if (coord < min || coord > max) {
                // Only reverse if actually moving out of bounds
                if (Math.abs(movement) > MIN_MOVEMENT) {
                    reverseDirection();
                }
                // Keep coordinate within bounds with slight margin
                return Math.max(min + 0.000001, Math.min(coord, max - 0.000001));
            }
        }
        return coord;
    }

    private void adjustDirectionOnCollisionOptimized() {
        // Smarter collision avoidance with less randomness
        double angleChange = (Math.random() - 0.5) * Math.PI / 2; // ±45 degrees (reduced from ±90)
        double cos = Math.cos(angleChange);
        double sin = Math.sin(angleChange);

        double newVx = vx * cos - vy * sin;
        double newVy = vx * sin + vy * cos;

        // Normalize to ensure unit vector
        double mag = Math.sqrt(newVx * newVx + newVy * newVy);
        if (mag > 1e-10) {
            this.vx = newVx / mag;
            this.vy = newVy / mag;
        }
    }

    private void reverseDirection() {
        this.vx = -vx;
        this.vy = -vy;
    }

    private void updateHistoryOptimized() {
        if (history == null) {
            history = new ArrayList<>(MAX_HISTORY);
        }

        // Reuse Point2D objects when possible
        if (history.size() < MAX_HISTORY) {
            history.add(new Point2D.Double(x, y));
        } else {
            // Shift history instead of creating new objects
            for (int i = 0; i < MAX_HISTORY - 1; i++) {
                Point2D.Double pt = history.get(i + 1);
                history.set(i, pt);
            }
            // Update last position
            if (history.size() > 0) {
                Point2D.Double last = history.get(MAX_HISTORY - 1);
                if (last != null) {
                    last.setLocation(x, y);
                } else {
                    history.set(MAX_HISTORY - 1, new Point2D.Double(x, y));
                }
            }
        }
    }

    // Optimized environment probing with caching
    public Map<String, Object> probeEnvironmentOptimized(double x, double y, Project project) {
        // Check if we can reuse cached environment
        if (lastEnvironment != null
                && Math.abs(x - lastProbeX) < ENVIRONMENT_CACHE_DISTANCE
                && Math.abs(y - lastProbeY) < ENVIRONMENT_CACHE_DISTANCE) {
            return new HashMap<>(lastEnvironment); // Return copy
        }

        Map<String, Object> readings = new HashMap<>();

        // Cache point geometry to reduce object creation
        if (cachedPoint == null) {
            cachedPoint = gf.createPoint(new Coordinate(x, y));
        } else {
            cachedPoint.getCoordinate().setX(x);
            cachedPoint.getCoordinate().setY(y);
        }

        // Only probe layers that are visible and relevant
        List<Layer> layers = project.getLayers();
        for (int i = 0; i < layers.size(); i++) {
            Layer l = layers.get(i);
            if (!l.isVisible()) {
                continue;
            }

            try {
                if (l instanceof RasterLayer rl) {
                    // Use optimized value retrieval
                    Double value = rl.getValueAt(x, y, project.getProjectFile());
                    if (value != null) {
                        readings.put(rl.getName(), value);
                    }
                } else if (l instanceof VectorLayer vl) {
                    // Only probe vector layers if we might need terrain info
                    SimpleFeature feature = vl.getFeatureAt(cachedPoint, project.getProjectFile());
                    if (feature != null) {
                        Object type = feature.getAttribute("type");
                        if (type == null) {
                            org.locationtech.jts.geom.Geometry geom
                                    = (org.locationtech.jts.geom.Geometry) feature.getDefaultGeometry();
                            type = geom.getGeometryType();
                        }
                        readings.put(vl.getName(), type.toString().toLowerCase());
                    } else {
                        readings.put(vl.getName(), "empty");
                    }
                }
            } catch (Exception e) {
                // Silent fail for performance - don't spam console
            }
        }

        // Cache the result
        lastEnvironment = new HashMap<>(readings);
        lastProbeX = x;
        lastProbeY = y;

        return readings;
    }

    // Original probeEnvironment method for backward compatibility
    public Map<String, Object> probeEnvironment(double x, double y, Project project) {
        return probeEnvironmentOptimized(x, y, project);
    }

    // Performance helper methods
    public void clearEnvironmentCache() {
        lastEnvironment = null;
        lastProbeX = Double.NaN;
        lastProbeY = Double.NaN;
    }

    public boolean isStuck(double threshold) {
        if (history == null || history.size() < 2) {
            return false;
        }

        Point2D.Double current = new Point2D.Double(x, y);
        Point2D.Double previous = history.get(history.size() - 1);

        return current.distance(previous) < threshold;
    }

    public void randomizeDirection() {
        double angle = Math.random() * 2 * Math.PI;
        this.vx = Math.cos(angle);
        this.vy = Math.sin(angle);
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

    public void setDirection(double vx, double vy) {
        double magnitude = Math.sqrt(vx * vx + vy * vy);
        if (magnitude > 1e-10) {
            this.vx = vx / magnitude;
            this.vy = vy / magnitude;
        }
    }

    @JsonIgnore
    public List<Point2D.Double> getHistory() {
        if (history == null) {
            history = new ArrayList<>(MAX_HISTORY);
        }
        return Collections.unmodifiableList(history);
    }

    @JsonIgnore
    public void setHistory(List<Point2D.Double> history) {
        if (history != null) {
            this.history = new ArrayList<>(Math.min(history.size(), MAX_HISTORY));
            for (int i = 0; i < Math.min(history.size(), MAX_HISTORY); i++) {
                Point2D.Double pt = history.get(i);
                this.history.add(new Point2D.Double(pt.x, pt.y));
            }
        }
    }

    @JsonIgnore
    public double getDistanceTraveled() {
        if (history == null || history.size() < 2) {
            return 0.0;
        }

        double total = 0.0;
        for (int i = 1; i < history.size(); i++) {
            Point2D.Double p1 = history.get(i - 1);
            Point2D.Double p2 = history.get(i);
            total += p1.distance(p2);
        }
        return total;
    }

    @JsonIgnore
    public String getStatus() {
        return String.format("Agent[alive=%s, x=%.6f, y=%.6f, vx=%.3f, vy=%.3f]",
                alive, x, y, vx, vy);
    }
}
