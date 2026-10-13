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
    private int stageAgeTicks = 0;
    // Stage-specific max age multipliers (as percentage of global max age)
    // These represent the maximum age an agent can reach in each stage
    // before dying of old age
    private double maxAgeMultiplierEgg = 0.05;
    private double maxAgeMultiplierLarva = 0.45;
    private double maxAgeMultiplierPupa = 0.15;
    private double maxAgeMultiplierAdult = 0.35;
   
    private double accumulatedDegreeDays = 0.0;
    private double stageAgeDays = 0.0;
    
    // The global max age (set by project defaults)
    private int globalMaxAge = 2880; // Default: 30 days at 15-min ticks
    
    /**
    * Id of the tank from which this larva was hatched. Populated when
    * AgentLayer spawns the larva from a tank's hatch event. Null for
    * initial-seeded larvae and for agents that are not in the LARVA stage.
    *
    * Transient: not part of the serialized snapshot, since the parent tank
    * will simply be re-resolved on the next tick after deserialization.
    */
    private transient volatile String parentTankId = null;

    public String getParentTankId() { return parentTankId; }
    public void   setParentTankId(String id) { this.parentTankId = id; }
    public void   clearParentTankId() { this.parentTankId = null; }

    public double getDevelopmentProgress() {
        return developmentProgress;
    }
    
    public void setDevelopmentProgress(double developmentProgress) {
        this.developmentProgress = developmentProgress;
    }
    
    public int getStageAgeTicks() { return stageAgeTicks; }
    public void setStageAgeTicks(int stageAgeTicks) { this.stageAgeTicks = stageAgeTicks; }
    public void incrementStageAgeTicks() { this.stageAgeTicks++; }
    
    // Getters and setters
    public double getAccumulatedDegreeDays() { return accumulatedDegreeDays; }
    public void setAccumulatedDegreeDays(double dd) { this.accumulatedDegreeDays = dd; }
    
    public double getStageAgeDays() { return stageAgeDays; }
    public void setStageAgeDays(double days) { this.stageAgeDays = days; }

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
    
    // ------------------------------------------------------------------
    // Persistent tank target for gravid females.
    // Not part of the serialized state: if the simulation is snapshotted
    // and reloaded, the female simply reacquires a target on the next tick.
    // ------------------------------------------------------------------
    private transient volatile String targetTankId = null;
    private transient final AtomicInteger targetTankAgeTicks = new AtomicInteger(0);

    public String getTargetTankId() { return targetTankId; }

    public void setTargetTankId(String id) {
        this.targetTankId = id;
        this.targetTankAgeTicks.set(0);
    }

    public void clearTargetTank() {
        this.targetTankId = null;
        this.targetTankAgeTicks.set(0);
    }

    public int getTargetTankAgeTicks() { return targetTankAgeTicks.get(); }
    public void incrementTargetTankAgeTicks() { targetTankAgeTicks.incrementAndGet(); }
    
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
            case EGG:   multiplier = maxAgeMultiplierEgg; break;
            case LARVA: multiplier = maxAgeMultiplierLarva; break;
            case PUPA:  multiplier = maxAgeMultiplierPupa; break;
            case ADULT: multiplier = maxAgeMultiplierAdult; break;
            default:    multiplier = 1.0; break;
        }
        return (int) Math.max(1, globalMaxAge * multiplier);
    }
    
    /**
     * Get the maximum age for a specific stage (static helper).
     */
    // Static version that takes multipliers as parameters
    public int getMaxAgeForStage(LifecycleStage stage, int globalMaxAge) {
        double multiplier;

        switch (stage) {
            case EGG:   multiplier = maxAgeMultiplierEgg; break;
            case LARVA: multiplier = maxAgeMultiplierLarva; break;
            case PUPA:  multiplier = maxAgeMultiplierPupa; break;
            case ADULT: multiplier = maxAgeMultiplierAdult; break;
            default:    multiplier = 1.0; break;
        }

        return (int) Math.max(1, globalMaxAge * multiplier);
    }
    
    public void setAgeMultipliers(double egg, double larva, double pupa, double adult) {
        this.maxAgeMultiplierEgg = egg;
        this.maxAgeMultiplierLarva = larva;
        this.maxAgeMultiplierPupa = pupa;
        this.maxAgeMultiplierAdult = adult;
    }
    
    /**
     * Get a description of the age limits for all stages.
     */
    public String getAgeLimitDescription(int globalMaxAge) {
        return String.format(
            "EGG: %d ticks (%.1f%%), LARVA: %d ticks (%.1f%%), " +
            "PUPA: %d ticks (%.1f%%), ADULT: %d ticks (%.1f%%)",
            getMaxAgeForStage(LifecycleStage.EGG, globalMaxAge),
            maxAgeMultiplierEgg * 100,
            getMaxAgeForStage(LifecycleStage.LARVA, globalMaxAge),
            maxAgeMultiplierLarva * 100,
            getMaxAgeForStage(LifecycleStage.PUPA, globalMaxAge),
            maxAgeMultiplierPupa * 100,
            getMaxAgeForStage(LifecycleStage.ADULT, globalMaxAge),
            maxAgeMultiplierAdult * 100
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