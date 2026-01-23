package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;
import java.io.Serializable;
import java.util.UUID;

public abstract class Agent implements Serializable {
    private final String id;
    private String name;
    protected double x, y; // World coordinates (EPSG:4326)
    
    
    public Agent(double x, double y, String name) {
        this.id = UUID.randomUUID().toString();
        this.x = x;
        this.y = y;
        this.name = name;
    }
    
    public Agent(double x, double y) {
        this.id = UUID.randomUUID().toString();
        this.x = x;
        this.y = y;
    }

    /**
     * The core logic "hook" called by the SimulationEngine.
     * @param project Access to RasterLayers (Environment) and SpatialRegistry 
     * (Neighbors)
     * @param timeManager Access to current simulation time/date
     */
    public abstract void update(Project project, TimeManager timeManager);

    // Getters and Setters
    
    public String getId(){
        return this.id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }
    
}
