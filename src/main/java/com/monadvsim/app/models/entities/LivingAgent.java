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
        
        // 1. Basic Daily Mortality (e.g., ~10% chance to die per day)
        // Since a tick is 15 mins, there are 96 ticks in a day.
        // Probability of dying per tick ≈ 0.001
        if (Math.random() < 0.001) {
            this.alive = false;
            return;
        }

        RasterLayer tempLayer = project.getRasterByName("Temperature");
        double tempC = (tempLayer != null) ? tempLayer.getValueAt(x, y) - 273.15 : 25.0;
        
        RasterLayer elevLayer = project.getRasterByName("Elevation");
        double elevation = (elevLayer != null) ? elevLayer.getValueAt(x, y) : 2300;

        // High elevation increases mortality slightly (simulating thinner air/colder nights)
        if (elevation > 2500) {
            if (Math.random() < 0.01) this.alive = false;
        }

        // Tweak: Lower death probability so agents survive the first few days
        double deathProb = 0.001; 
        if (tempC > 40 || tempC < 10 || age > 2000) deathProb = 0.1;
        

        if (Math.random() < deathProb) {
            alive = false;
            return;
        }

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

        if (density > 0.5 && Math.random() < 0.1) isGravid = true;
    }

    private void seekAndLayEggs(Project project) {
        if (project.getSpatialRegistry() == null) return;
        
        List<Agent> nearby = project.getSpatialRegistry().getNearbyAgents(x, y, 0.01);
        for (Agent a : nearby) {
            if (a instanceof InertAgent tank) {
                tank.addEggs(50);
                isGravid = false;
                return;
            }
        }
        // Random search for tank
        this.x += (Math.random() - 0.5) * 0.001;
        this.y += (Math.random() - 0.5) * 0.001;
    }

    @Override public boolean isAlive() { return alive; }
}