package com.monadvsim.app.models.engine;


import java.util.concurrent.atomic.AtomicLong;


/**
 * Performance Metrics Tracker
 *
 * Records simulation performance statistics
 *
 * Tracks agents processed, rules evaluated, spatial queries, births/deaths
 *
 * Provides performance reporting functionality
 * 
 * 
 * @author void
 */


public class SimulationMetrics {
    private final AtomicLong agentsProcessed = new AtomicLong(0);
    private final AtomicLong rulesEvaluated = new AtomicLong(0);
    private final AtomicLong spatialQueries = new AtomicLong(0);
    private final AtomicLong births = new AtomicLong(0);
    private final AtomicLong deaths = new AtomicLong(0);
    private final long startTime;
    
    public SimulationMetrics() {
        this.startTime = System.currentTimeMillis();
    }
    
    public void recordAgentProcessed() {
        agentsProcessed.incrementAndGet();
    }
    
    public void recordRuleEvaluated() {
        rulesEvaluated.incrementAndGet();
    }
    
    public void recordSpatialQuery() {
        spatialQueries.incrementAndGet();
    }
    
    public void recordBirth() {
        births.incrementAndGet();
    }
    
    public void recordDeath() {
        deaths.incrementAndGet();
    }
    
    public void printReport() {
        long currentTime = System.currentTimeMillis();
        double elapsedSeconds = (currentTime - startTime) / 1000.0;
        
        System.out.println("\n=== SIMULATION METRICS ===");
        System.out.printf("Elapsed Time: %.2f seconds%n", elapsedSeconds);
        System.out.printf("Agents Processed: %,d (%.0f/sec)%n", 
            agentsProcessed.get(), agentsProcessed.get() / elapsedSeconds);
        System.out.printf("Rules Evaluated: %,d (%.0f/sec)%n", 
            rulesEvaluated.get(), rulesEvaluated.get() / elapsedSeconds);
        System.out.printf("Spatial Queries: %,d%n", spatialQueries.get());
        System.out.printf("Births: %,d | Deaths: %,d%n", births.get(), deaths.get());
        System.out.println("=========================\n");
    }
    
    public void reset() {
        agentsProcessed.set(0);
        rulesEvaluated.set(0);
        spatialQueries.set(0);
        births.set(0);
        deaths.set(0);
    }
}
