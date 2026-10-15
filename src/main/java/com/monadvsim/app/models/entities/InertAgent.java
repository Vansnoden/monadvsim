package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.config.SimulationConfig.InertAgentParams;
import com.monadvsim.app.models.engine.LifecycleModel;
import com.monadvsim.app.models.engine.TimeManager;
import com.monadvsim.app.models.utils.SeedManager;
import com.monadvsim.app.models.utils.SimulationLogger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Static Habitat Agent
 *
 * Represents unconventional breeding sites (e.g., water tanks)
 *
 * Tracks water volume, egg count, larval count, capacity
 *
 * Thread-safe operations for population management
 *
 * Supports hatching, evaporation, and drying out
 */
public class InertAgent extends Agent {

    private final AtomicReference<Double> waterVolume = new AtomicReference<>(0.0); // percentage 0-100
    private final AtomicInteger larvalCount = new AtomicInteger(0);
    private final AtomicReference<Double> capacity = new AtomicReference<>(0.0);
    private final AtomicInteger eggCount = new AtomicInteger(0);
    // Add egg development progress tracking
    private final AtomicReference<Double> eggDevelopmentProgress = new AtomicReference<>(0.0);

    // For thread-safe batch operations (kept for potential future use)
    private final Object batchLock = new Object();
    private InertAgentParams params = new InertAgentParams();
    
    private static final int DEFAULT_MAX_LARVAE_CAPACITY = 500;
    private static final double DEFAULT_MORTALITY_INTENSITY = 0.5;
    private static final int DEFAULT_MIN_LARVAE_RETAIN = 50;
       

    public InertAgent(double x, double y, InertAgentParams params) {
        super(x, y);
        this.params = params;
    }
    
    
    // ================================================================
    // NEW: Density-Dependent Mortality Check
    // ================================================================
    /**
     * Applies density-dependent mortality to the larval population in this tank.
     * If the larval count exceeds MAX_LARVAE_CAPACITY, a percentage of the excess
     * is killed to simulate competition for resources.
     * 
     * @return The number of larvae killed
     */
    public int applyDensityDependentMortality() {
        int currentLarvae = larvalCount.get();
        if (currentLarvae <= params.max_larvae_capacity) return 0;

        int excess  = currentLarvae - params.max_larvae_capacity;
        int toKillRaw = (int) (excess * params.mortality_intensity);
        int maxKill = Math.max(0, currentLarvae - params.min_larvae_retain);
        final int toKill = Math.min(toKillRaw, maxKill);   // final, never reassigned

        if (toKill > 0) {
            int newCount = larvalCount.updateAndGet(c -> Math.max(0, c - toKill));
            if (toKill > 10) {
                SimulationLogger.fine("[TANK] Density mortality: killed %d (was %d, now %d)",
                    toKill, currentLarvae, newCount);
            }
            return toKill;
        }
        return 0;
    }

    public InertAgent(double x, double y) {
        super(x, y);
    }

    public InertAgent(double x, double y, String name) {
        super(x, y, name);
    }

    // ------------------------------------------------------------------------
    // Thread-safe getters and setters
    // ------------------------------------------------------------------------
    public double getWaterVolume() {
        return waterVolume.get();
    }
    
    public void setWaterVolume(double volume) {
        double clamped = Math.max(0.0, Math.min(100.0, volume));
        waterVolume.set(clamped);
        updateCapacityFromVolume();
    }

    public int getLarvalCount() {
        return larvalCount.get();
    }

    public void setLarvalCount(int count) {
        larvalCount.set(Math.max(0, count));
    }

    public int incrementLarvalCount(int delta) {
        return larvalCount.addAndGet(delta);
    }

    public int decrementLarvalCount(int delta) {
        return larvalCount.updateAndGet(current -> Math.max(0, current - delta));
    }

    public double getCapacity() {
        return capacity.get();
    }

    public void setCapacity(double cap) {
        capacity.set(Math.max(0.0, cap));
    }

    public int getEggCount() {
        return eggCount.get();
    }

    public void setEggCount(int count) {
        eggCount.set(Math.max(0, count));
    }

    // ------------------------------------------------------------------------
    // Egg operations
    // ------------------------------------------------------------------------
    /**
     * Add eggs to the tank.
     * @param eggsToAdd number of eggs to add
     * @return new total egg count
     */
    public int addEggs(int eggsToAdd) {
        return eggCount.addAndGet(eggsToAdd);
    }

    /**
     * Remove up to the specified number of eggs.
     * @param eggsToTake maximum number to remove
     * @return actual number of eggs removed
     */
    public int takeEggs(int eggsToTake) {
        int taken = 0;
        while (taken < eggsToTake) {
            int current = eggCount.get();
            if (current == 0) break;
            int toSubtract = Math.min(eggsToTake - taken, current);
            if (eggCount.compareAndSet(current, current - toSubtract)) {
                taken += toSubtract;
            }
        }
        return taken;
    }

    
    public int hatchEggs(double temperature, LifecycleModel model) {
        int current = eggCount.get();
        if (current == 0) return 0;

        // Use degree-day accumulation for eggs too
        double tempC = temperature - 273.15;
        double T_BASE_EGG = 10.0; // Base temperature for egg development
        double DEGREE_DAYS_EGG_TO_HATCH = 22.0; // Required degree-days for eggs

        if (tempC > T_BASE_EGG) {
            double dd = (tempC - T_BASE_EGG) * 0.0104166667;
            double newProgress = eggDevelopmentProgress.get() + dd;
            eggDevelopmentProgress.set(newProgress);
        }

        if (eggDevelopmentProgress.get() < DEGREE_DAYS_EGG_TO_HATCH) return 0;

        // Get hatch fraction from config
        double hatchFractionMin = (params != null) ? params.hatch_fraction_min : 0.1;
        double hatchFractionMax = (params != null) ? params.hatch_fraction_max : 0.6;

        double excessProgress = eggDevelopmentProgress.get() - DEGREE_DAYS_EGG_TO_HATCH;
        double hatchFraction = Math.min(hatchFractionMax, hatchFractionMin + excessProgress * 0.5);
        eggDevelopmentProgress.set(excessProgress);

        // Check capacity
        int maxCapacity = (params != null) ? params.max_larvae_capacity : DEFAULT_MAX_LARVAE_CAPACITY;
        int currentLarvae = larvalCount.get();
        if (currentLarvae >= maxCapacity) return 0;

        int maxHatch = maxCapacity - currentLarvae;
        int eggsToHatch = (int) Math.min(current, maxHatch * hatchFraction);
        eggsToHatch = Math.max(1, Math.min(eggsToHatch, current));

        if (eggsToHatch > 0) {
            eggCount.addAndGet(-eggsToHatch);
            larvalCount.addAndGet(eggsToHatch);
            SimulationLogger.fine("[TANK] Hatched %d eggs (%.1f%%), DD=%.2f", 
                eggsToHatch, hatchFraction * 100, eggDevelopmentProgress.get());
        }
        return eggsToHatch;
    }
    
    /**
     * Update larval capacity based on current water volume.
     * Called after water volume changes (evaporation, rain, etc.).
     */
    public void updateCapacityFromVolume() {
        double vol = waterVolume.get();
        double maxCap = (params != null) ? params.max_larvae_capacity : DEFAULT_MAX_LARVAE_CAPACITY;
        double newCap = maxCap * (vol / 100.0);
        // Allow capacity to become 0 when water is 0
        capacity.set(Math.max(0.0, newCap));
    }

    @Override
    public void updateState(Project project, TimeManager timeManager) {
        // Automatically update capacity when water volume changes
        updateCapacityFromVolume();
    }
}