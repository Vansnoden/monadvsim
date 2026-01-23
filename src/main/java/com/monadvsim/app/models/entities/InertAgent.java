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
        // Larval development logic: Temperature speeds up maturation
        RasterLayer tempLayer = project.getRasterByName("Temperature");
        double tempC = (tempLayer != null) ? (tempLayer.getValueAt(x, y) - 273.15) : 25.0;

        if (larvalCount > 0 && tempC > 20.0) {
            // Every hour, some larvae mature into adults
            int hatching = (int)(larvalCount * 0.05); 
            for (int i = 0; i < hatching; i++) {
                // Add new LivingAgent to the project's AgentLayer
                project.getAgentLayers().get(0).addAgent(new LivingAgent(this.x, this.y));
            }
            larvalCount -= hatching;
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