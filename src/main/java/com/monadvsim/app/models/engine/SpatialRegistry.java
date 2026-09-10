package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.GridCell;
import com.monadvsim.app.models.utils.SimulationLogger;

import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance Concurrent Spatial Indexing System.
 */
public class SpatialRegistry {

    private final ConcurrentHashMap<GridCell, List<Agent>> spatialGrid = new ConcurrentHashMap<>();
    private final Rectangle2D worldBounds;
    private final double cellSize;
    private final int gridWidth;
    private final int gridHeight;

    private final Map<String, GridCell> agentPositions = new ConcurrentHashMap<>();

    private final AtomicInteger totalInserts = new AtomicInteger(0);
    private final AtomicInteger totalRemoves = new AtomicInteger(0);
    private final AtomicInteger totalUpdates = new AtomicInteger(0);
    private final AtomicInteger queryCount = new AtomicInteger(0);

    public SpatialRegistry(Rectangle2D worldBounds, double cellSize) {
        this.worldBounds = worldBounds;
        this.cellSize = cellSize;
        this.gridWidth = (int) Math.ceil(worldBounds.getWidth() / cellSize);
        this.gridHeight = (int) Math.ceil(worldBounds.getHeight() / cellSize);
        SimulationLogger.info("Initialized High-Perf SpatialRegistry [%dx%d cells, %.6f° cell size]",
                gridWidth, gridHeight, cellSize);
    }

    private GridCell getCellForPosition(double x, double y) {
        if (!worldBounds.contains(x, y)) return null;
        int gridX = (int) ((x - worldBounds.getX()) / cellSize);
        int gridY = (int) ((y - worldBounds.getY()) / cellSize);
        gridX = Math.max(0, Math.min(gridX, gridWidth - 1));
        gridY = Math.max(0, Math.min(gridY, gridHeight - 1));
        return new GridCell(gridX, gridY);
    }

    private List<Agent> getOrCreateCellList(GridCell cell) {
        return spatialGrid.computeIfAbsent(cell, k -> new CopyOnWriteArrayList<>());
    }

    public void registerAgent(Agent agent) {
        if (agent == null) return;
        GridCell cell = getCellForPosition(agent.getX(), agent.getY());
        if (cell == null) return;

        List<Agent> cellList = getOrCreateCellList(cell);
        cellList.add(agent);
        agentPositions.put(agent.getId(), cell);
        totalInserts.incrementAndGet();
    }

    public void registerAgents(Collection<Agent> agents) {
        if (agents == null || agents.isEmpty()) return;
        for (Agent agent : agents) {
            registerAgent(agent);
        }
    }

    public void unregisterAgent(String agentId) {
        if (agentId == null) return;
        GridCell oldCell = agentPositions.remove(agentId);
        if (oldCell != null) {
            List<Agent> cellList = spatialGrid.get(oldCell);
            if (cellList != null) {
                cellList.removeIf(a -> a.getId().equals(agentId));
                totalRemoves.incrementAndGet();
            }
        }
    }

    public void unregisterAgents(Collection<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return;
        for (String agentId : agentIds) {
            unregisterAgent(agentId);
        }
    }

    public void updateAgentPosition(Agent agent) {
        if (agent == null) return;
        String agentId = agent.getId();
        GridCell newCell = getCellForPosition(agent.getX(), agent.getY());
        GridCell oldCell = agentPositions.get(agentId);

        if (newCell == null) {
            if (oldCell != null) unregisterAgent(agentId);
            return;
        }

        if (oldCell == null) {
            registerAgent(agent);
        } else if (!oldCell.equals(newCell)) {
            List<Agent> oldList = spatialGrid.get(oldCell);
            if (oldList != null) {
                oldList.removeIf(a -> a.getId().equals(agentId));
            }
            List<Agent> newList = getOrCreateCellList(newCell);
            newList.add(agent);
            agentPositions.put(agentId, newCell);
            totalUpdates.incrementAndGet();
        }
    }

    public List<Agent> getNearbyAgents(double x, double y, double radius) {
        queryCount.incrementAndGet();
        GridCell centerCell = getCellForPosition(x, y);
        if (centerCell == null) return Collections.emptyList();

        List<Agent> result = new ArrayList<>();
        int cellsRadius = (int) Math.ceil(radius / cellSize);

        for (int dx = -cellsRadius; dx <= cellsRadius; dx++) {
            for (int dy = -cellsRadius; dy <= cellsRadius; dy++) {
                int checkX = centerCell.gridX + dx;
                int checkY = centerCell.gridY + dy;

                if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {
                    GridCell checkCell = new GridCell(checkX, checkY);
                    List<Agent> cellAgents = spatialGrid.get(checkCell);
                    if (cellAgents != null && !cellAgents.isEmpty()) {
                        for (Agent agent : cellAgents) {
                            double dist = Math.hypot(agent.getX() - x, agent.getY() - y);
                            if (dist <= radius) {
                                result.add(agent);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    public void applyPendingChanges() {
        // Safe lock-free updates implemented directly
    }

    public void bulkUpdate(List<Agent> allAgents) {
        clear();
        registerAgents(allAgents);
    }

    public List<Agent> getAllAgents() {
        List<Agent> all = new ArrayList<>();
        for (List<Agent> list : spatialGrid.values()) {
            all.addAll(list);
        }
        return all;
    }

    public List<Agent> getAgentsInCell(GridCell cell) {
        List<Agent> list = spatialGrid.get(cell);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    public GridCell getAgentCell(String agentId) { 
        return agentPositions.get(agentId); 
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAgents", agentPositions.size());
        stats.put("gridCells", gridWidth * gridHeight);
        stats.put("cellSizeDegrees", cellSize);
        stats.put("queryCount", queryCount.get());
        stats.put("totalInserts", totalInserts.get());
        stats.put("totalRemoves", totalRemoves.get());
        stats.put("totalUpdates", totalUpdates.get());

        int maxAgents = 0, total = 0;
        for (List<Agent> list : spatialGrid.values()) {
            int size = list.size();
            total += size;
            maxAgents = Math.max(maxAgents, size);
        }
        stats.put("maxAgentsPerCell", maxAgents);
        stats.put("avgAgentsPerCell", gridWidth * gridHeight == 0 ? 0 : total / (double) (gridWidth * gridHeight));
        return stats;
    }

    public void clear() {
        spatialGrid.clear();
        agentPositions.clear();
    }
}