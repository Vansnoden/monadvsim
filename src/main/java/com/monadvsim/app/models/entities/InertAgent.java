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
        if (currentLarvae <= params.max_larvae_capacity) {
            return 0;
        }
        int excess = currentLarvae - params.max_larvae_capacity;
        int toKill = (int) (excess * params.mortality_intensity);
        int maxKill = Math.max(0, currentLarvae - params.min_larvae_retain);
        toKill = Math.min(toKill, maxKill);

        if (toKill > 0) {
            // Create a final copy for the lambda expression
            final int killCount = toKill;

            // Update the larval count (thread-safe)
            int newCount = larvalCount.updateAndGet(current -> Math.max(0, current - killCount));

            // Log the mortality event (optional, for debugging)
            if (killCount > 10) {
                SimulationLogger.fine("[TANK] Density-dependent mortality: killed %d larvae (was: %d, now: %d), capacity=%.1f", 
                    killCount, currentLarvae, newCount, getCapacity());
            }
            return killCount;
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
//    public int hatchEggs(double temperature, LifecycleModel model) {
//        int currentEggs = eggCount.get();
//        if (currentEggs == 0) return 0;
//
//        // 1. Egg development probability per tick
//        double dE = model.eggDevelopmentRate(temperature);
//        double SE = model.eggSurvival(temperature);
//        double pHatch = model.transitionProb(dE) * SE;
//
//        // 2. Binomial sampling (efficient)
//        ThreadLocalRandom rng = ThreadLocalRandom.current();
//        int eggsHatched;
//        if (currentEggs > 100) {
//            double mean = currentEggs * pHatch;
//            double var = mean * (1 - pHatch);
//            int sample = (int) Math.round(mean + Math.sqrt(var) * rng.nextGaussian());
//            eggsHatched = Math.max(0, Math.min(currentEggs, sample));
//        } else {
//            eggsHatched = 0;
//            for (int i = 0; i < currentEggs; i++) {
//                if (rng.nextDouble() < pHatch) eggsHatched++;
//            }
//        }
//
//        if (eggsHatched == 0) return 0;
//
//        // 3. Atomically update counts
//        eggCount.addAndGet(-eggsHatched);
//        int newLarvae = larvalCount.addAndGet(eggsHatched);
//
//        // 4. Density-dependent mortality if capacity exceeded
//        double cap = capacity.get();
//        if (cap > 0 && newLarvae > cap) {
//            int excess = newLarvae - (int) cap;
//            int toKill = rng.nextInt(excess + 1);
//            if (toKill > 0) {
//                larvalCount.addAndGet(-toKill);
//                eggsHatched -= toKill;
//                SimulationLogger.info("[TANK] Density-dependent mortality: killed %d larvae, capacity=%.1f",
//                                      toKill, cap);
//            }
//        }
//
//        return eggsHatched;
//    }

//    public int hatchEggs(double temperature, LifecycleModel model) {
//        int current = eggCount.get();
//        if (current == 0) return 0;
//        int hatched = model.tryHatchEggs(current, temperature);
//        if (hatched > 0) {
//            eggCount.addAndGet(-hatched);
//            larvalCount.addAndGet(hatched);
//            updateCapacityFromVolume();
//        }
//        return hatched;
//    }
    
//    public int hatchEggs(double temperature, LifecycleModel model) {
//        int current = eggCount.get();
//        if (current == 0) return 0;
//        
//        // Accumulate egg development progress
//        double de = model.eggDevelopmentRate(temperature);
//        double newProgress = eggDevelopmentProgress.get() + de * 0.0104; // dtDays
//        eggDevelopmentProgress.set(newProgress);
//        
//        // Only hatch when development is complete
//        if (newProgress < 1.0) {
//            return 0;
//        }
//        
//        // Reset progress
//        eggDevelopmentProgress.set(0.0);
//        
//        // Check capacity
//        double cap = capacity.get();
//        int currentLarvae = larvalCount.get();
//        if (currentLarvae >= cap) {
//            return 0;
//        }
//        
//        int maxHatch = (int)(cap - currentLarvae);
//        // Hatch all eggs that are ready
//        int hatched = Math.min(current, maxHatch);
//        
//        if (hatched > 0) {
//            eggCount.addAndGet(-hatched);
//            larvalCount.addAndGet(hatched);
//        }
//        return hatched;
//    }
    
//    public int hatchEggs(double temperature, LifecycleModel model) {
//        int current = eggCount.get();
//        if (current == 0) return 0;
//
//        double de = model.eggDevelopmentRate(temperature);
//        double dtDays = 0.0104166667;
//        double newProgress = eggDevelopmentProgress.get() + de * dtDays;
//        eggDevelopmentProgress.set(newProgress);
//        
//        if (this.getId().hashCode() % 20 == 0 && current > 0) {
//            SimulationLogger.fine("[EGG] %s progress=%.4f, de=%.6f, temp=%.2fK, eggs=%d",
//                this.getId().substring(0, 8), newProgress, de, temperature, current);
//        }
//
//        if (newProgress < 1.0) return 0;
//
//        // STAGGERED HATCHING: Don't hatch all at once!
//        // Only hatch a percentage based on how far past 1.0 progress is
//        double excessProgress = newProgress - 1.0;
//        double hatchFraction = Math.min(1.0, 0.1 + excessProgress * 0.5); // 10-60% per tick
//
//        // Reset progress (some eggs may continue developing)
//        eggDevelopmentProgress.set(excessProgress);
//
//        double cap = capacity.get();
//        int currentLarvae = larvalCount.get();
//        if (currentLarvae >= cap) return 0;
//
//        int maxHatch = (int)(cap - currentLarvae);
//        int eggsToHatch = (int)Math.min(current, maxHatch * hatchFraction);
//        eggsToHatch = Math.max(1, eggsToHatch);
//        eggsToHatch = Math.min(eggsToHatch, current);
//
//        if (eggsToHatch > 0) {
//            eggCount.addAndGet(-eggsToHatch);
//            larvalCount.addAndGet(eggsToHatch);
//            SimulationLogger.fine("[TANK] Hatched %d eggs (%.1f%%), progress=%.3f", 
//                eggsToHatch, hatchFraction * 100, newProgress);
//        }
//        return eggsToHatch;
//    }
    
    public int hatchEggs(double temperature, LifecycleModel model) {
        int current = eggCount.get();
        if (current == 0) return 0;

        double de = model.eggDevelopmentRate(temperature);
        double dtDays = 0.0104166667;
        double newProgress = eggDevelopmentProgress.get() + de * dtDays;
        eggDevelopmentProgress.set(newProgress);

        if (newProgress < 1.0) return 0;

        // Get hatch fraction from config
        double hatchFractionMin = (params != null) ? params.hatch_fraction_min : 0.1;
        double hatchFractionMax = (params != null) ? params.hatch_fraction_max : 0.6;
        
        double excessProgress = newProgress - 1.0;
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
            SimulationLogger.fine("[TANK] Hatched %d eggs (%.1f%%), progress=%.3f", 
                eggsToHatch, hatchFraction * 100, newProgress);
        }
        return eggsToHatch;
    }
    
    /**
     * Update larval capacity based on current water volume.
     * Called after water volume changes (evaporation, rain, etc.).
     */
    public void updateCapacityFromVolume() {
        double vol = waterVolume.get();
        double maxCap = 5000.0;   // maximum larvae when full
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