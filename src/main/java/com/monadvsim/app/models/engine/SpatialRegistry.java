package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.GridCell;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SpatialRegistry {
    private final Map<GridCell, List<Agent>> spatialGrid;
    private final Rectangle2D worldBounds;
    private final double cellSize;
    private final int gridWidth;
    private final int gridHeight;
    
    // Track agent positions for movement detection
    private final Map<String, GridCell> agentPositions;
    private final Map<String, double[]> previousPositions;
    
    public SpatialRegistry(Rectangle2D worldBounds, double cellSize) {
        this.worldBounds = worldBounds;
        this.cellSize = cellSize;
        
        this.gridWidth = (int) Math.ceil(worldBounds.getWidth() / cellSize);
        this.gridHeight = (int) Math.ceil(worldBounds.getHeight() / cellSize);
        
        this.spatialGrid = new ConcurrentHashMap<>(gridWidth * gridHeight);
        this.agentPositions = new ConcurrentHashMap<>();
        this.previousPositions = new ConcurrentHashMap<>();
        
        // Pre-initialize grid cells
        initializeGrid();
    }
    
    private void initializeGrid() {
        for (int x = 0; x < gridWidth; x++) {
            for (int y = 0; y < gridHeight; y++) {
                spatialGrid.put(new GridCell(x, y), new ArrayList<>());
            }
        }
    }
    
    private GridCell getCellForPosition(double x, double y) {
        if (!worldBounds.contains(x, y)) {
            return null;
        }
        
        int gridX = (int) ((x - worldBounds.getX()) / cellSize);
        int gridY = (int) ((y - worldBounds.getY()) / cellSize);
        
        // Clamp to grid bounds
        gridX = Math.max(0, Math.min(gridX, gridWidth - 1));
        gridY = Math.max(0, Math.min(gridY, gridHeight - 1));
        
        return new GridCell(gridX, gridY);
    }
    
    /**
     * Bulk update - more efficient than clearing and re-inserting
     */
    public void update(List<Agent> allAgents) {
        // Clear only cells that have agents (not all cells)
        for (List<Agent> cellAgents : spatialGrid.values()) {
            if (!cellAgents.isEmpty()) {
                cellAgents.clear();
            }
        }
        
        // Bulk insert all agents
        for (Agent agent : allAgents) {
            GridCell cell = getCellForPosition(agent.getX(), agent.getY());
            if (cell != null) {
                spatialGrid.get(cell).add(agent);
                agentPositions.put(agent.getId(), cell);
                previousPositions.put(agent.getId(), new double[]{agent.getX(), agent.getY()});
            }
        }
    }
    
    /**
     * Incremental update - only update agents that moved
     */
    public void updateIncremental(List<Agent> allAgents) {
        for (Agent agent : allAgents) {
            String agentId = agent.getId();
            double[] prevPos = previousPositions.get(agentId);
            
            if (prevPos == null) {
                // New agent - insert
                insert(agent);
            } else if (prevPos[0] != agent.getX() || prevPos[1] != agent.getY()) {
                // Agent moved - update
                remove(agentId);
                insert(agent);
            }
            // Else: agent didn't move - no update needed
        }
    }
    
    private void insert(Agent agent) {
        GridCell cell = getCellForPosition(agent.getX(), agent.getY());
        if (cell != null) {
            spatialGrid.get(cell).add(agent);
            agentPositions.put(agent.getId(), cell);
            previousPositions.put(agent.getId(), new double[]{agent.getX(), agent.getY()});
        }
    }
    
    private void remove(String agentId) {
        GridCell oldCell = agentPositions.get(agentId);
        if (oldCell != null) {
            List<Agent> cellAgents = spatialGrid.get(oldCell);
            if (cellAgents != null) {
                cellAgents.removeIf(a -> a.getId().equals(agentId));
            }
            agentPositions.remove(agentId);
            previousPositions.remove(agentId);
        }
    }
    
    /**
     * Fast radius query using grid cells
     */
    public List<Agent> getNearbyAgents(double x, double y, double radius) {
        List<Agent> result = new ArrayList<>();
        GridCell centerCell = getCellForPosition(x, y);
        
        if (centerCell == null) {
            return result;
        }
        
        // Calculate how many cells to check in each direction
        int cellsRadius = (int) Math.ceil(radius / cellSize);
        
        for (int dx = -cellsRadius; dx <= cellsRadius; dx++) {
            for (int dy = -cellsRadius; dy <= cellsRadius; dy++) {
                int checkX = centerCell.gridX + dx;
                int checkY = centerCell.gridY + dy;
                
                if (checkX >= 0 && checkX < gridWidth && 
                    checkY >= 0 && checkY < gridHeight) {
                    
                    GridCell checkCell = new GridCell(checkX, checkY);
                    List<Agent> cellAgents = spatialGrid.get(checkCell);
                    
                    if (cellAgents != null) {
                        // Filter by actual distance (not just cell membership)
                        for (Agent agent : cellAgents) {
                            double distance = Math.sqrt(
                                Math.pow(agent.getX() - x, 2) + 
                                Math.pow(agent.getY() - y, 2)
                            );
                            if (distance <= radius) {
                                result.add(agent);
                            }
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * Get all agents in a rectangular area
     */
    public List<Agent> getAgentsInArea(Rectangle2D area) {
        List<Agent> result = new ArrayList<>();
        
        GridCell minCell = getCellForPosition(area.getMinX(), area.getMinY());
        GridCell maxCell = getCellForPosition(area.getMaxX(), area.getMaxY());
        
        if (minCell == null || maxCell == null) {
            return result;
        }
        
        for (int x = minCell.gridX; x <= maxCell.gridX; x++) {
            for (int y = minCell.gridY; y <= maxCell.gridY; y++) {
                GridCell cell = new GridCell(x, y);
                List<Agent> cellAgents = spatialGrid.get(cell);
                
                if (cellAgents != null) {
                    for (Agent agent : cellAgents) {
                        if (area.contains(agent.getX(), agent.getY())) {
                            result.add(agent);
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * Get agents from specific cells (fastest query)
     */
    public List<Agent> getAgentsFromCells(Set<GridCell> cells) {
        List<Agent> result = new ArrayList<>();
        
        for (GridCell cell : cells) {
            List<Agent> cellAgents = spatialGrid.get(cell);
            if (cellAgents != null) {
                result.addAll(cellAgents);
            }
        }
        
        return result;
    }
    
    // Getters
    public int getGridWidth() { return gridWidth; }
    public int getGridHeight() { return gridHeight; }
    public double getCellSize() { return cellSize; }
    public Rectangle2D getWorldBounds() { return worldBounds; }
}