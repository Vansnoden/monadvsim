package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;
import java.util.List;
import java.util.Random;

public class LivingAgent extends Agent {
    private static final Random random = new Random();
    
    // Biological constants for MVS
    private final double FLY_SPEED = 0.001; // In coordinate units per tick
    private final double SEARCH_RADIUS = 0.005; 
    private boolean isGravid = false; // True if carrying eggs

    public LivingAgent(double x, double y) {
        super(x, y, "Adult");
    }

    @Override
    public void update(Project project, TimeManager timeManager) {
        if (!alive) return;

        // 1. Metabolic Cost: Age the mosquito
        ageInTicks++;
        if (ageInTicks > 2880) { // Approx 30 days at 15-min ticks
            this.alive = false;
            return;
        }

        // 2. Sensing: Read Population Density (Humans)
        RasterLayer popLayer = project.getRasterByName("Population");
        double humanDensity = (popLayer != null) ? popLayer.getValueAt(x, y) : 0;

        // 3. Movement Logic: Biased Random Walk
        if (!isGravid) {
            // Seek Humans: Move toward higher density or just wander
            moveWithBias(humanDensity);
            
            // Interaction: If human density is high, "feed" and become gravid
            if (humanDensity > 0.5 && random.nextDouble() < 0.1) {
                isGravid = true;
            }
        } else {
            // Seek Water: Look for an InertAgent (Water Tank) nearby
            findWaterAndLayEggs(project);
        }
    }

    private void moveWithBias(double currentSuitability) {
        // Random displacement
        double dx = (random.nextDouble() - 0.5) * FLY_SPEED;
        double dy = (random.nextDouble() - 0.5) * FLY_SPEED;
        
        // In a more complex version, we'd compare suitability of target vs current
        this.x += dx;
        this.y += dy;
    }

    private void findWaterAndLayEggs(Project project) {
        // Use the SpatialRegistry to find nearby Water Tanks
        // Theoretically: project.getSpatialRegistry().getNearbyAgents(x, y, SEARCH_RADIUS)
        // If found, call tank.addEggs() and set isGravid = false
        
        // Simple Wander for MVS until a tank is found
        this.x += (random.nextDouble() - 0.5) * FLY_SPEED;
        this.y += (random.nextDouble() - 0.5) * FLY_SPEED;
    }
}
