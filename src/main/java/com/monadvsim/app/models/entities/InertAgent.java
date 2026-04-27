package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.LifecycleModel;
import com.monadvsim.app.models.engine.TimeManager;
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

    // For thread-safe batch operations (kept for potential future use)
    private final Object batchLock = new Object();

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
        waterVolume.set(Math.max(0.0, Math.min(100.0, volume)));
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

    // ------------------------------------------------------------------------
    // Hatching with temperature dependence and density-dependent mortality
    // ------------------------------------------------------------------------
    /**
     * Hatch eggs into larvae based on temperature‑dependent development and survival.
     * Applies density‑dependent mortality after hatching if larval count exceeds capacity.
     *
     * @param temperature Current temperature in Kelvin
     * @param model       LifecycleModel providing egg development rate and survival
     * @return Number of eggs successfully hatched (after accounting for mortality)
     */
    public int hatchEggs(double temperature, LifecycleModel model) {
        int currentEggs = eggCount.get();
        if (currentEggs == 0) return 0;

        // 1. Egg development probability per tick
        double dE = model.eggDevelopmentRate(temperature);
        double SE = model.eggSurvival(temperature);
        double pHatch = model.transitionProb(dE) * SE;

        // 2. Binomial sampling (efficient)
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int eggsHatched;
        if (currentEggs > 100) {
            double mean = currentEggs * pHatch;
            double var = mean * (1 - pHatch);
            int sample = (int) Math.round(mean + Math.sqrt(var) * rng.nextGaussian());
            eggsHatched = Math.max(0, Math.min(currentEggs, sample));
        } else {
            eggsHatched = 0;
            for (int i = 0; i < currentEggs; i++) {
                if (rng.nextDouble() < pHatch) eggsHatched++;
            }
        }

        if (eggsHatched == 0) return 0;

        // 3. Atomically update counts
        eggCount.addAndGet(-eggsHatched);
        int newLarvae = larvalCount.addAndGet(eggsHatched);

        // 4. Density-dependent mortality if capacity exceeded
        double cap = capacity.get();
        if (cap > 0 && newLarvae > cap) {
            int excess = newLarvae - (int) cap;
            int toKill = rng.nextInt(excess + 1);
            if (toKill > 0) {
                larvalCount.addAndGet(-toKill);
                eggsHatched -= toKill;
                SimulationLogger.info("[TANK] Density-dependent mortality: killed %d larvae, capacity=%.1f",
                                      toKill, cap);
            }
        }

        return eggsHatched;
    }

    /**
     * Update larval capacity based on current water volume.
     * Called after water volume changes (evaporation, rain, etc.).
     */
    public void updateCapacityFromVolume() {
        double vol = waterVolume.get();
        double maxCap = 500.0;   // maximum larvae when full
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