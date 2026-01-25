package com.monadvsim.app.models.entities;


import com.monadvsim.app.models.engine.TimeManager;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


public class LivingAgent extends Agent {
    // Using atomic types for thread-safe state
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean gravid = new AtomicBoolean(false);
    private final AtomicInteger age = new AtomicInteger(0);
    private final AtomicReference<LifecycleStage> stage = 
        new AtomicReference<>(LifecycleStage.ADULT);
    private final AtomicReference<Double> energy = new AtomicReference<>(1.0);
    // For move operations, we need synchronization
    private final Object positionLock = new Object();
    private volatile double volatileX, volatileY;

    
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
    
    
    // Thread-safe getters and setters
    public boolean isAlive() {
        return alive.get();
    }
    
    
    public boolean setAlive(boolean newAlive) {
        return alive.compareAndSet(!newAlive, newAlive);
    }
    
    
    public boolean isGravid() {
        return gravid.get();
    }
    
    
    public void setGravid(boolean gravid) {
        this.gravid.set(gravid);
    }
    
    
    public int getAge() {
        return age.get();
    }
    
    
    public void incrementAge() {
        age.incrementAndGet();
    }
    
    
    public void setAge(int newAge) {
        age.set(newAge);
    }
    
    
    public LifecycleStage getStage() {
        return stage.get();
    }
    
    
    public void setStage(LifecycleStage newStage) {
        stage.set(newStage);
    }
    
    
    public double getEnergy() {
        return energy.get();
    }
    
    
    public void setEnergy(double newEnergy) {
        energy.set(Math.max(0.0, newEnergy));
    }
    
    
    // Thread-safe move operation
    public void move(double dx, double dy) {
        synchronized (positionLock) {
            this.volatileX += dx;
            this.volatileY += dy;
            super.setX(this.volatileX);
            super.setY(this.volatileY);
        }
    }
    
    
    // Thread-safe position getters
    @Override
    public double getX() {
        return volatileX; // volatile ensures visibility
    }
    
    
    @Override
    public double getY() {
        return volatileY;
    }
    
    
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
        
    }
}