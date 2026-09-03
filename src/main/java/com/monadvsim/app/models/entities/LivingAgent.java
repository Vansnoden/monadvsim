package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mobile Biological Agent
 *
 * Represents mosquitoes with lifeCycle stages (egg, larva, pupa, adult)
 * Thread-safe state management using atomic variables
 */
public class LivingAgent extends Agent {
    // Using atomic types for thread-safe state
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean gravid = new AtomicBoolean(false);
    private final AtomicInteger age = new AtomicInteger(1);
    private final AtomicReference<LifecycleStage> stage = 
        new AtomicReference<>(LifecycleStage.ADULT);
    private final AtomicReference<Double> energy = new AtomicReference<>(1.0);
    // For move operations, we need synchronization
    private final Object positionLock = new Object();
    private volatile double volatileX, volatileY;
    private final AtomicBoolean resting = new AtomicBoolean(false);
    private final AtomicInteger restingDuration = new AtomicInteger(0);
    private final AtomicInteger timeWithoutRest = new AtomicInteger(0);
    private final AtomicInteger maxRestingDuration = new AtomicInteger(4); // 1 hour
    private double developmentProgress = 0.0;
    
    // Stage-specific max age multipliers (as percentage of global max age)
    // These represent the maximum age an agent can reach in each stage
    // before dying of old age
    public static final double MAX_AGE_MULTIPLIER_EGG = 0.05;    // 5% of global max
    public static final double MAX_AGE_MULTIPLIER_LARVA = 0.80;  // 45% of global max
    public static final double MAX_AGE_MULTIPLIER_PUPA = 0.15;   // 15% of global max
    public static final double MAX_AGE_MULTIPLIER_ADULT = 0.35;  // 35% of global max
    
    // The global max age (set by project defaults)
    private int globalMaxAge = 2880; // Default: 30 days at 15-min ticks

    public double getDevelopmentProgress() {
        return developmentProgress;
    }

    public void setDevelopmentProgress(double developmentProgress) {
        this.developmentProgress = developmentProgress;
    }

    public LivingAgent(double x, double y) {
        super(x, y);
        this.volatileX = x;
        this.volatileY = y;
    }

    public LivingAgent(double x, double y, String name) {
        super(x, y, name);
        this.volatileX = x;
        this.volatileY = y;
    }
    
    /**
     * Set the global max age for this agent.
     * Called from AgentLayer when the agent is created.
     */
    public void setGlobalMaxAge(int globalMaxAge) {
        this.globalMaxAge = globalMaxAge;
    }
    
    /**
     * Get the stage-specific max age for this agent.
     * Returns a percentage of the global max age based on the current stage.
     */
    public int getMaxAgeForStage() {
        LifecycleStage currentStage = stage.get();
        double multiplier;
        
        switch (currentStage) {
            case EGG:
                multiplier = MAX_AGE_MULTIPLIER_EGG;
                break;
            case LARVA:
                multiplier = MAX_AGE_MULTIPLIER_LARVA;
                break;
            case PUPA:
                multiplier = MAX_AGE_MULTIPLIER_PUPA;
                break;
            case ADULT:
                multiplier = MAX_AGE_MULTIPLIER_ADULT;
                break;
            default:
                multiplier = 1.0;
                break;
        }
        
        return (int) Math.max(1, globalMaxAge * multiplier);
    }
    
    /**
     * Get the maximum age for a specific stage (static helper).
     */
    public static int getMaxAgeForStage(LifecycleStage stage, int globalMaxAge) {
        double multiplier;
        
        switch (stage) {
            case EGG:
                multiplier = MAX_AGE_MULTIPLIER_EGG;
                break;
            case LARVA:
                multiplier = MAX_AGE_MULTIPLIER_LARVA;
                break;
            case PUPA:
                multiplier = MAX_AGE_MULTIPLIER_PUPA;
                break;
            case ADULT:
                multiplier = MAX_AGE_MULTIPLIER_ADULT;
                break;
            default:
                multiplier = 1.0;
                break;
        }
        
        return (int) Math.max(1, globalMaxAge * multiplier);
    }
    
    /**
     * Get a description of the age limits for all stages.
     */
    public static String getAgeLimitDescription(int globalMaxAge) {
        return String.format(
            "EGG: %d ticks (%.1f%%), LARVA: %d ticks (%.1f%%), " +
            "PUPA: %d ticks (%.1f%%), ADULT: %d ticks (%.1f%%)",
            getMaxAgeForStage(LifecycleStage.EGG, globalMaxAge),
            MAX_AGE_MULTIPLIER_EGG * 100,
            getMaxAgeForStage(LifecycleStage.LARVA, globalMaxAge),
            MAX_AGE_MULTIPLIER_LARVA * 100,
            getMaxAgeForStage(LifecycleStage.PUPA, globalMaxAge),
            MAX_AGE_MULTIPLIER_PUPA * 100,
            getMaxAgeForStage(LifecycleStage.ADULT, globalMaxAge),
            MAX_AGE_MULTIPLIER_ADULT * 100
        );
    }

    // Thread-safe getters and setters
    public boolean isAlive() { return alive.get(); }
    public boolean setAlive(boolean newAlive) { return alive.compareAndSet(!newAlive, newAlive); }
    public boolean isGravid() { return gravid.get(); }
    public void setGravid(boolean gravid) { this.gravid.set(gravid); }
    public int getAge() { return age.get(); }
    public void incrementAge() { age.incrementAndGet(); }
    public void setAge(int newAge) { age.set(newAge); }
    public LifecycleStage getStage() { return stage.get(); }
    public void setStage(LifecycleStage newStage) { stage.set(newStage); }
    public double getEnergy() { return energy.get(); }
    public void setEnergy(double newEnergy) { energy.set(Math.max(0.0, newEnergy)); }

    public void move(double dx, double dy) {
        synchronized (positionLock) {
            this.volatileX += dx;
            this.volatileY += dy;
            super.setX(this.volatileX);
            super.setY(this.volatileY);
        }
    }

    @Override
    public double getX() { return volatileX; }
    @Override
    public double getY() { return volatileY; }
    @Override
    public void setX(double x) {
        synchronized (positionLock) {
            this.volatileX = x;
            super.setX(x);
        }
    }
    @Override
    public void setY(double y) {
        synchronized (positionLock) {
            this.volatileY = y;
            super.setY(y);
        }
    }

    @Override
    public void updateState(Project project, TimeManager timeManager) {
        // Not used; lifecycle is handled by AgentLayer
    }

    public boolean isResting() { return resting.get(); }
    public void setResting(boolean resting) {
        this.resting.set(resting);
        if (resting) {
            restingDuration.set(0);
            timeWithoutRest.set(0);
        }
    }
    public int getRestingDuration() { return restingDuration.get(); }
    public void setRestingDuration(int duration) { restingDuration.set(duration); }
    public void incrementRestingDuration() { restingDuration.incrementAndGet(); }
    public int getTimeWithoutRest() { return timeWithoutRest.get(); }
    public void incrementTimeWithoutRest() { timeWithoutRest.incrementAndGet(); }
    public int getMaxRestingDuration() { return maxRestingDuration.get(); }
    public void setMaxRestingDuration(int duration) { maxRestingDuration.set(duration); }
    public void resetTimeWithoutRest() { timeWithoutRest.set(0); }
}