package com.monadvsim.app.models.entities;
import com.monadvsim.app.models.utils.SimulationLogger;


import com.monadvsim.app.models.engine.LifecycleModel;
import com.monadvsim.app.models.engine.TimeManager;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Static Habitat Agent
 *
 * Represents unconventional breeding sites (e.g water tanks)
 *
 * Tracks water volume, egg count, larval count, capacity
 *
 * Thread-safe operations for population management
 *
 * Supports hatching, evaporation, and drying out
 * 
 * 
 * @author void
 */


public class InertAgent extends Agent {
    
    private final AtomicReference<Double> waterVolume = new AtomicReference<>(0.0); // percentage 0-100
    private final AtomicInteger larvalCount = new AtomicInteger(0); // number of larvae in the container
    private final AtomicReference<Double> capacity = new AtomicReference<>(0.0); // maximum number of larvae that a single container can support
    private final AtomicInteger eggCount = new AtomicInteger(0); // number of eggs in the container
    // For thread-safe batch operations
    private final Object batchLock = new Object();
    

    public InertAgent(double x, double y) {
        super(x, y);
    }
    
    
    public InertAgent(double x, double y, String name) {
        super(x, y, name);
    }
    
    
    // Thread-safe operations
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
    
    
    // Atomic operations for eggs
    public int addEggs(int eggsToAdd) {
        int newCount = eggCount.addAndGet(eggsToAdd);
        SimulationLogger.info("[TANK] %s received %d eggs, total now %d%n", 
            getId(), eggsToAdd, newCount);
        return eggCount.addAndGet(eggsToAdd);
    }
    
    
    public int takeEggs(int eggsToTake) {
        return eggCount.updateAndGet(current -> {
            int taken = Math.min(current, eggsToTake);
            return current - taken;
        });
    }
    
    
    // Batch operation for hatching
    public int hatchEggs(double hatchProbability) {
        synchronized (batchLock) {
            int currentEggs = eggCount.get();
            if (currentEggs == 0) return 0;
            
            int eggsToHatch = (int) (currentEggs * hatchProbability);
            eggsToHatch = Math.max(1, Math.min(eggsToHatch, currentEggs));
            
            // Atomically update both counts
            eggCount.addAndGet(-eggsToHatch);
            larvalCount.addAndGet(eggsToHatch);
            
            return eggsToHatch;
        }
    }
    
//    public int hatchEggs(double temperature, LifecycleModel model) {
//        double dE = model.eggDevelopmentRate(temperature);
//        double SE = model.eggSurvival(temperature);
//        double pHatch = model.transitionProb(dE) * SE; // probability per egg per tick
//        int currentEggs = eggCount.get();
//        if (currentEggs == 0) return 0;
//        // Binomial sampling
//        int eggsToHatch = (int) Math.round(currentEggs * pHatch);
//        eggsToHatch = Math.min(eggsToHatch, currentEggs);
//        if (eggsToHatch > 0) {
//            eggCount.addAndGet(-eggsToHatch);
//            larvalCount.addAndGet(eggsToHatch);
//        }
//        return eggsToHatch;
//    }
//    
    
//    public int hatchEggs(double temperature, LifecycleModel model) {
//        double dE = model.eggDevelopmentRate(temperature);
//        double SE = model.eggSurvival(temperature);
//        double pHatch = model.transitionProb(dE) * SE; // probability per egg per tick
//        int currentEggs = eggCount.get();
//        if (currentEggs == 0) return 0;
//
//        // Stochastic binomial sampling
//        int eggsToHatch = 0;
//        ThreadLocalRandom rng = ThreadLocalRandom.current();
//        for (int i = 0; i < currentEggs; i++) {
//            if (rng.nextDouble() < pHatch) {
//                eggsToHatch++;
//            }
//        }
//        if (eggsToHatch > 0) {
//            eggCount.addAndGet(-eggsToHatch);
//            larvalCount.addAndGet(eggsToHatch);
//        }
//        return eggsToHatch;
//    }
    
    public int hatchEggs(double temperature, LifecycleModel model) {
        double dE = model.eggDevelopmentRate(temperature);
        double SE = model.eggSurvival(temperature);
        double pHatch = model.transitionProb(dE) * SE; // probability per egg per tick
        int currentEggs = eggCount.get();
        if (currentEggs == 0) return 0;

        // Stochastic binomial sampling (loop over each egg)
        int eggsToHatch = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < currentEggs; i++) {
            if (rng.nextDouble() < pHatch) {
                eggsToHatch++;
            }
        }
        if (eggsToHatch > 0) {
            eggCount.addAndGet(-eggsToHatch);
            larvalCount.addAndGet(eggsToHatch);
        }
        return eggsToHatch;
    }

    @Override
    public void updateState(Project project, TimeManager timeManager) {
        
    }
}