package com.monadvsim.app.models.engine;
import com.monadvsim.app.models.utils.SimulationLogger;


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
        
        SimulationLogger.info("\n=== SIMULATION METRICS ===");
        SimulationLogger.info("Elapsed Time: %.2f seconds%n", elapsedSeconds);
        SimulationLogger.info("Agents Processed: %,d (%.0f/sec)%n", 
            agentsProcessed.get(), agentsProcessed.get() / elapsedSeconds);
        SimulationLogger.info("Rules Evaluated: %,d (%.0f/sec)%n", 
            rulesEvaluated.get(), rulesEvaluated.get() / elapsedSeconds);
        SimulationLogger.info("Spatial Queries: %,d%n", spatialQueries.get());
        SimulationLogger.info("Births: %,d | Deaths: %,d%n", births.get(), deaths.get());
        SimulationLogger.info("=========================\n");
    }
    
    public void reset() {
        agentsProcessed.set(0);
        rulesEvaluated.set(0);
        spatialQueries.set(0);
        births.set(0);
        deaths.set(0);
    }
}
