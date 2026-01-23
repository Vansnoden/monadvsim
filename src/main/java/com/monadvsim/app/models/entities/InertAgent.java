package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;

public class InertAgent extends Agent {
    private double waterVolume; // percentage 0-100
    private int larvalCount; // number of larvae in the container
    private double capacity; // maximum number of larvae that a single container can support
    private int eggCount; // number of eggs in the container
    
    
    public InertAgent(double x, double y) {
        super(x, y);
    }
    
    public InertAgent(double x, double y, String name) {
        super(x, y, name);
    }
    
    @Override
    public void update(Project project, TimeManager tm) {
        // Updated via the Engine's parallel loop for performance
    }

    public double getWaterVolume() {
        return waterVolume;
    }

    public void setWaterVolume(double waterVolume) {
        this.waterVolume = waterVolume;
    }

    public int getLarvalCount() {
        return larvalCount;
    }

    public void setLarvalCount(int larvalCount) {
        this.larvalCount = larvalCount;
    }

    public double getCapacity() {
        return capacity;
    }

    public void setCapacity(double capacity) {
        this.capacity = capacity;
    }

    public int getEggCount() {
        return eggCount;
    }

    public void setEggCount(int eggGount) {
        this.eggCount = eggGount;
    }
    
    
    
}