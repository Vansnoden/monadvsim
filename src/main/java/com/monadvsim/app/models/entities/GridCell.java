package com.monadvsim.app.models.entities;


import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


/**
 * Spatial Partition Cell
 *
 * Represents a cell in the spatial registry grid
 *
 * Stores agents within a specific spatial region
 *
 * Provides thread-safe agent management
 *
 * Used for efficient spatial queries
 * 
 * 
 * @author void
 */


public class GridCell {
    public final int gridX;
    public final int gridY;
    private final ConcurrentLinkedQueue<Agent> agents;
    
    
    public GridCell(int gridX, int gridY) {
        this.gridX = gridX;
        this.gridY = gridY;
        this.agents = new ConcurrentLinkedQueue<>();
    }
    
    
    public void addAgent(Agent agent) {
        agents.add(agent);
    }
    
    
    public void removeAgent(Agent agent) {
        agents.remove(agent);
    }
    
    
    public List<Agent> getAgents() {
        return new ArrayList<>(agents);
    }
    
    
    public boolean isEmpty() {
        return agents.isEmpty();
    }
    
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GridCell gridCell = (GridCell) o;
        return gridX == gridCell.gridX && gridY == gridCell.gridY;
    }
    
    
    @Override
    public int hashCode() {
        return Objects.hash(gridX, gridY);
    }
    
}
