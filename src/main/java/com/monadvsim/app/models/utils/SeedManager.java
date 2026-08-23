// File: com/monadvsim/app/models/utils/SeedManager.java
package com.monadvsim.app.models.utils;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Centralized seed management for reproducible simulations.
 * Allows setting a global seed that affects all random number generation.
 */
public class SeedManager {
    private static long currentSeed = System.currentTimeMillis();
    private static boolean seedSet = false;
    private static Random globalRandom = new Random(currentSeed);
    
    /**
     * Set the global seed for the simulation.
     * This will reset the Random instance.
     */
    public static void setSeed(long seed) {
        currentSeed = seed;
        seedSet = true;
        globalRandom = new Random(seed);
        SimulationLogger.info("[SeedManager] Global seed set to: %d", seed);
    }
    
    /**
     * Get the current seed.
     */
    public static long getCurrentSeed() {
        return currentSeed;
    }
    
    /**
     * Check if a seed has been explicitly set.
     */
    public static boolean isSeedSet() {
        return seedSet;
    }
    
    /**
     * Get a seeded Random instance for deterministic randomness.
     */
    public static Random getRandom() {
        return globalRandom;
    }
    
    /**
     * Create a new Random instance with a seed derived from the global seed.
     * Useful for parallel processing.
     */
    public static Random createChildRandom() {
        return new Random(globalRandom.nextLong());
    }
    
    /**
     * Get a ThreadLocalRandom that is seeded deterministically.
     * Note: ThreadLocalRandom cannot be seeded directly, so this
     * returns a regular Random for deterministic behavior.
     */
    public static Random getThreadLocalRandom() {
        // ThreadLocalRandom doesn't support seeding, so return a seeded Random
        return globalRandom;
    }
}