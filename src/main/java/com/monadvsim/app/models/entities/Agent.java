package com.monadvsim.app.models.entities;
import com.monadvsim.app.models.utils.SimulationLogger;


import com.monadvsim.app.models.engine.TimeManager;
import java.io.Serializable;
import java.util.UUID;


/**
 * Base Agent Class
 *
 * Abstract base for all agent types
 *
 * Defines position (x,y), ID, and basic properties
 *
 * Declares abstract state update method
 * 
 * 
 * @author void
 */


public abstract class Agent implements Serializable {
    private final String id;
    private String name;
    protected double x, y; 
    
    
    public Agent(double x, double y) {
        this.id = UUID.randomUUID().toString();
        this.x = x;
        this.y = y;
    }
    
    
    public Agent(double x, double y, String name) {
        this.id = UUID.randomUUID().toString();
        this.x = x;
        this.y = y;
        this.name = name;
    }
    
    
    public abstract void updateState(Project project, TimeManager timeManager);

    
    public String getId(){
        return this.id;
    }
    
    
    public String getName() {
        return name;
    }

    
    public void setName(String name) {
        this.name = name;
    }

    
    public double getX() {
        return x;
    }

    
    public void setX(double x) {
        this.x = x;
    }

    
    public double getY() {
        return y;
    }
    

    public void setY(double y) {
        this.y = y;
    }
    
}
