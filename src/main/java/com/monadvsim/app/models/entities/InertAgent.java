package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;

public class InertAgent extends Agent {
    private double waterVolume = 50.0; // percentage 0-100
    private int larvalCount = 0;
    private final double capacity = 500.0;

    public InertAgent(double x, double y) {
        super(x, y, "WaterTank");
    }
    
    public int calculateHatching(Project project) {
        RasterLayer rain = project.getRasterByName("Rainfall");
        RasterLayer temp = project.getRasterByName("Temperature");

        double r = (rain != null) ? rain.getValueAt(x, y) : 0.0;
        double tC = (temp != null) ? temp.getValueAt(x, y) - 273.15 : 25.0;

        // Hydrology logic
        waterVolume = Math.max(0, Math.min(100, waterVolume + (r * 1000) - 0.1));

        // BIOLOGICAL FIX: Lower hatch rate to 0.5% (0.005) instead of 5% (0.05)
        // Also ensure temperature is high enough for larvae to mature
        if (waterVolume > 10.0 && tC > 18.0 && larvalCount > 0) {
            int hatch = (int) (larvalCount * 0.005); 
            larvalCount -= hatch; // This actually depletes the eggs
            return hatch;
        }
        return 0;
    }

    public void addEggs(int count) {
        this.larvalCount = (int) Math.min(capacity, this.larvalCount + count);
    }

    @Override
    public void update(Project project, TimeManager tm) {
        // Updated via the Engine's parallel loop for performance
    }
}