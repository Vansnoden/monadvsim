package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;

public class InertAgent extends Agent {
    private double waterVolume = 50.0; // 0 to 100%
    private int larvalCount = 0;
    private final double capacity = 2000.0;

    public InertAgent(double x, double y) {
        super(x, y, "WaterTank");
    }

    public int calculateHatching(Project project) {
        RasterLayer rain = project.getRasterByName("Rainfall");
        RasterLayer temp = project.getRasterByName("Temperature");

        double currentRain = (rain != null) ? rain.getValueAt(x, y) : 0.0;
        double currentTempC = (temp != null) ? temp.getValueAt(x, y) - 273.15 : 25.0;

        // 1. Hydrology: Rain adds water, heat evaporates it
        waterVolume += (currentRain * 5000) - (currentTempC * 0.01);
        waterVolume = Math.max(0, Math.min(100, waterVolume));

        // 2. Mortality: If tank is dry (<5%), larvae die
        if (waterVolume < 5.0) {
            larvalCount = 0;
            return 0;
        }

        // 3. Hatching: Dependent on temperature
        if (larvalCount > 0 && currentTempC > 18.0) {
            int hatch = (int) (larvalCount * 0.01); 
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
        // Handled by calculateHatching for thread safety
    }
}