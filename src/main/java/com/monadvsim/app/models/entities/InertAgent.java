package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;

public class InertAgent extends Agent {
    private int larvalCount = 0;
    private final double capacity; // Max larvae this tank can support
    
    // Metabolic accumulation for maturation
    private double degreeDaySum = 0;
    private final double MATURATION_THRESHOLD = 100.0; // Cumulative temp needed to become adult

    public InertAgent(double x, double y, double capacity) {
        super(x, y, "WaterTank");
        this.capacity = capacity;
    }

    /**
     * The update logic for a static breeding site.
     */
    @Override
    public void update(Project project, TimeManager timeManager) {
        if (larvalCount <= 0) return;

        // 1. Get current temperature from the environment
        RasterLayer tempLayer = project.getRasterByName("Temperature");
        if (tempLayer != null) {
            double temp = tempLayer.getValueAt(x, y);
            
            // 2. Accumulate 'Degree-Days' (simplistic maturation rule)
            // If temp is above a base (e.g., 10°C), larvae grow.
            if (temp > 10.0) {
                degreeDaySum += (temp - 10.0) * (1.0 / (24 * 4)); // Adjusted for 15-min ticks
            }
        }

        // 3. Maturation: Spawn new LivingAgents if threshold reached
        if (degreeDaySum >= MATURATION_THRESHOLD) {
            spawnAdults(project);
            degreeDaySum = 0; // Reset cycle
        }
    }

    private void spawnAdults(Project project) {
        // Logic to add new LivingAgents to the AgentLayer
        // For MVS: convert 10% of larvae to adults per cycle
        int newAdults = (int) (larvalCount * 0.1);
        for (int i = 0; i < newAdults; i++) {
            // This would call back to the Project/AgentLayer to add a mosquito
        }
        larvalCount -= newAdults;
    }

    // Methods for a LivingAgent to interact with this tank
    public void addEggs(int count) {
        if (this.larvalCount + count <= capacity) {
            this.larvalCount += count;
        }
    }

    public int getLarvalCount() { return larvalCount; }
}