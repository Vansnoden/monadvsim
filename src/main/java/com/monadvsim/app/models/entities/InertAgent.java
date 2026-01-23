package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;

public class InertAgent extends Agent {
    private double waterVolume = 50.0; // percentage 0-100
    private int larvalCount = 0;
    private final double capacity = 2000.0;

    public InertAgent(double x, double y) {
        super(x, y, "WaterTank");
    }

    public int calculateHatching(Project project) {
        RasterLayer rain = project.getRasterByName("Rainfall");
        RasterLayer temp = project.getRasterByName("Temperature");

        // Logic: Rain fills the tank, evaporation empties it
        double currentRain = (rain != null) ? rain.getValueAt(x, y) : 0.0;
        double currentTempC = (temp != null) ? temp.getValueAt(x, y) - 273.15 : 25.0;

        waterVolume += (currentRain * 5000) - (currentTempC * 0.01);
        waterVolume = Math.max(0, Math.min(100, waterVolume));

        // Hatching only occurs if there's water and it's warm enough (>18C)
        if (waterVolume > 5.0 && currentTempC > 18.0 && larvalCount > 0) {
            int hatch = (int) (larvalCount * 0.02);
            larvalCount -= hatch;
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