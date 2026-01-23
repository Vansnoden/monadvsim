package com.monadvsim.app.models.entities;




import java.io.Serializable;
import java.util.UUID;

public abstract class Agent implements Serializable {
    private final String id;
    protected double x, y; // World coordinates (EPSG:4326)
    protected boolean alive = true;
    protected String lifecycleStage; // e.g., "Egg", "Larva", "Adult"
    
    // Physiological state
    protected double energy = 1.0;
    protected double ageInTicks = 0;

    public Agent(double x, double y, String stage) {
        this.id = UUID.randomUUID().toString();
        this.x = x;
        this.y = y;
        this.lifecycleStage = stage;
    }

    /**
     * The core logic "hook" called by the SimulationEngine.
     * @param project Access to RasterLayers (Environment) and SpatialRegistry (Neighbors)
     * @param timeManager Access to current simulation time/date
     */
    public abstract void update(Project project, com.monadvsim.app.models.engine.TimeManager timeManager);

    // Getters and Setters
    public double getX() { return x; }
    public double getY() { return y; }
    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    public String getLifecycleStage() { return lifecycleStage; }
    public String getId() { return id; }
    public double getEnergy() { return energy; }
    public void setEnergy(double energy) { this.energy = energy; }
}
