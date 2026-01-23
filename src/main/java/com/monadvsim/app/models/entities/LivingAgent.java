package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;
import java.util.List;

public class LivingAgent extends Agent {
    private boolean alive = true;
    private boolean isGravid = false;
    private int age = 0;

    public LivingAgent(double x, double y) {
        super(x, y, "Adult");
    }

    @Override
    public void update(Project project, TimeManager tm) {
        if (!alive) return;
        age++;
        
        // 1. Environmental Data Ingestion
        RasterLayer tempLayer = project.getRasterByName("Temperature");
        double tempC = (tempLayer != null) ? tempLayer.getValueAt(x, y) - 273.15 : 25.0;
        
        RasterLayer elevLayer = project.getRasterByName("Elevation");
        double elevation = (elevLayer != null) ? elevLayer.getValueAt(x, y) : 2300;

        // 2. ADJUSTED MORTALITY LOGIC
        // We need a higher baseline death rate to prevent millions of agents.
        // Probability 0.008 per tick results in ~50% survival over 4 days.
        double deathProb = 0.008; 

        // High elevation stress
        if (elevation > 2500 && Math.random() < 0.02) {
            this.alive = false;
            return;
        }

        // Extreme temperature or old age (>21 days) kills the agent faster
        if (tempC > 38 || tempC < 12 || age > 2000) {
            deathProb = 0.15; 
        }

        if (Math.random() < deathProb) {
            this.alive = false;
            return;
        }

        // 3. BIOLOGICAL CYCLE
        if (isGravid) {
            seekAndLayEggs(project);
        } else {
            seekBloodMeal(project);
        }
    }

    private void seekBloodMeal(Project project) {
        RasterLayer pop = project.getRasterByName("Population");
        double density = (pop != null) ? pop.getValueAt(x, y) : 0.0;

        // Move toward humans (Biased Walk)
        this.x += (Math.random() - 0.5) * 0.001 + (density * 0.0001);
        this.y += (Math.random() - 0.5) * 0.001 + (density * 0.0001);

        // Success rate for blood meal (10% per tick if humans present)
        if (density > 0.1 && Math.random() < 0.1) {
            isGravid = true;
        }
    }

    private void seekAndLayEggs(Project project) {
        if (project.getSpatialRegistry() == null) return;
        
        // Search radius for breeding sites
        List<Agent> nearby = project.getSpatialRegistry().getNearbyAgents(x, y, 0.01);
        for (Agent a : nearby) {
            if (a instanceof InertAgent tank) {
                // REDUCED: Laying 10 eggs instead of 50 to keep growth manageable
                tank.addEggs(10); 
                isGravid = false;
                return;
            }
        }
        
        // Random search move if no tank found
        this.x += (Math.random() - 0.5) * 0.001;
        this.y += (Math.random() - 0.5) * 0.001;
    }

    @Override public boolean isAlive() { return alive; }
    @Override public String getLifecycleStage() { return isGravid ? "Gravid" : "Adult"; }
}