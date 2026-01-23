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

        // 1. Get Environmental Context
        RasterLayer tempLayer = project.getRasterByName("Temperature");
        double tempK = (tempLayer != null) ? tempLayer.getValueAt(x, y) : 293.15; // ERA5 is often in Kelvin
        double tempC = tempK - 273.15;

        // 2. Daily Mortality Rule (An. stephensi logic)
        // If it's too hot (>35°C) or too cold (<15°C), increase death probability
        double deathProb = 0.01; // Base probability per tick
        if (tempC > 35.0 || tempC < 15.0) deathProb = 0.05; 

        if (Math.random() < deathProb) {
            this.alive = false;
            return;
        }

        // 3. Reproduction Rule (The "Birth" Bridge)
        if (isGravid) {
            seekAndLayEggs(project);
        } else {
            seekBloodMeal(project);
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
    
    private void seekBloodMeal(Project project) {
        RasterLayer pop = project.getRasterByName("Population");
        double currentDensity = pop.getValueAt(x, y);

        // Simple Chemotaxis: Move toward higher human density
        // (In a 48-hr MVS, we use a simple random walk biased by the gradient)
        this.x += (Math.random() - 0.5) * FLY_SPEED + (currentDensity * 0.01);
        this.y += (Math.random() - 0.5) * FLY_SPEED + (currentDensity * 0.01);

        if (currentDensity > 0.8 && Math.random() < 0.2) {
            this.isGravid = true; // Blood meal successful
        }
    }
    
    private void seekAndLayEggs(Project project) {
        // 1. Use the SpatialRegistry to find nearby Water Tanks
        List<Agent> nearby = project.getSpatialRegistry().getNearbyAgents(x, y, SEARCH_RADIUS);

        InertAgent targetTank = null;
        for (Agent a : nearby) {
            if (a instanceof InertAgent tank) {
                targetTank = tank;
                break; 
            }
        }

        if (targetTank != null) {
            targetTank.addEggs(50); // Deposit eggs
            this.isGravid = false;  // Reset cycle
        } else {
            // Continue searching (Random Walk)
            this.x += (Math.random() - 0.5) * FLY_SPEED;
            this.y += (Math.random() - 0.5) * FLY_SPEED;
        }
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
