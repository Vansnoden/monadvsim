package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.TimeManager;

public class LivingAgent extends Agent {
    private boolean alive = true;
    private boolean gravid = false; // not carrying eggs
    private int age = 0;
    private LifeCycleStage stage;
    private double energy = 1.0;
    

    public LivingAgent(double x, double y) {
        super(x, y);
    }
    
    public LivingAgent(double x, double y, String name) {
        super(x, y, name);
    }

    @Override
    public void update(Project project, TimeManager tm) {
        
    }
    
    public void move(double dx, double dy){
        // live agents can move in the environment
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public boolean isGravid() {
        return gravid;
    }

    public void setGravid(boolean gravid) {
        this.gravid = gravid;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public LifeCycleStage getStage() {
        return stage;
    }

    public void setStage(LifeCycleStage stage) {
        this.stage = stage;
    }

    public double getEnergy() {
        return energy;
    }

    public void setEnergy(double energy) {
        this.energy = energy;
    }

}