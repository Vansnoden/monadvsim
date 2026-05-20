package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.GridCell;
import com.monadvsim.app.models.utils.SimulationLogger;

import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * Spatial Indexing System – Lazy cell creation.
 * No pre‑allocation of grid cells; cells are created on first use.
 */
public class SpatialRegistry {

    // Maps a GridCell (key) to a thread‑safe list of agents in that cell
    private final ConcurrentHashMap<GridCell, List<Agent>> spatialGrid = new ConcurrentHashMap<>();
    private final Rectangle2D worldBounds;
    private final double cellSize;
    private final int gridWidth;
    private final int gridHeight;

    // Agent tracking
    private final Map<String, AgentRecord> agentRecords = new ConcurrentHashMap<>();
    private final Map<String, GridCell> agentPositions = new ConcurrentHashMap<>();

    // Change tracking (optional, kept for compatibility)
    private final Queue<SpatialChange> pendingChanges = new ConcurrentLinkedQueue<>();
    private final AtomicInteger changeCount = new AtomicInteger(0);
    private final StampedLock updateLock = new StampedLock();

    // Statistics
    private final AtomicInteger totalInserts = new AtomicInteger(0);
    private final AtomicInteger totalRemoves = new AtomicInteger(0);
    private final AtomicInteger totalUpdates = new AtomicInteger(0);
    private final AtomicInteger queryCount = new AtomicInteger(0);

    // Result cache
    private final Map<GridCell, CellCacheEntry> cellCache = new ConcurrentHashMap<>();
    private final int maxCacheSize = 1000;

    public SpatialRegistry(Rectangle2D worldBounds, double cellSize) {
        this.worldBounds = worldBounds;
        this.cellSize = cellSize;
        this.gridWidth = (int) Math.ceil(worldBounds.getWidth() / cellSize);
        this.gridHeight = (int) Math.ceil(worldBounds.getHeight() / cellSize);
        // No pre‑initialization – cells are created lazily
        SimulationLogger.info("Initialized SpatialRegistry [%dx%d cells, %.6f° cell size] (lazy)",
                gridWidth, gridHeight, cellSize);
    }

    // ------------------------------------------------------------------------
    // Core operations
    // ------------------------------------------------------------------------
    private GridCell getCellForPosition(double x, double y) {
        if (!worldBounds.contains(x, y)) return null;
        int gridX = (int) ((x - worldBounds.getX()) / cellSize);
        int gridY = (int) ((y - worldBounds.getY()) / cellSize);
        gridX = Math.max(0, Math.min(gridX, gridWidth - 1));
        gridY = Math.max(0, Math.min(gridY, gridHeight - 1));
        return new GridCell(gridX, gridY);
    }

    private List<Agent> getOrCreateCellList(GridCell cell) {
        return spatialGrid.computeIfAbsent(cell, k -> Collections.synchronizedList(new ArrayList<>()));
    }

    public void registerAgent(Agent agent) {
        if (agent == null) return;
        long stamp = updateLock.writeLock();
        try {
            GridCell cell = getCellForPosition(agent.getX(), agent.getY());
            if (cell == null) return;
            List<Agent> cellList = getOrCreateCellList(cell);
            cellList.add(agent);
            agentPositions.put(agent.getId(), cell);
            agentRecords.put(agent.getId(), new AgentRecord(agent, cell));
            pendingChanges.offer(new SpatialChange(SpatialChange.ChangeType.INSERT, agent.getId(), cell, null));
            changeCount.incrementAndGet();
            totalInserts.incrementAndGet();
            cellCache.remove(cell);
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }

    public void registerAgents(Collection<Agent> agents) {
        if (agents == null || agents.isEmpty()) return;
        long stamp = updateLock.writeLock();
        try {
            for (Agent agent : agents) {
                GridCell cell = getCellForPosition(agent.getX(), agent.getY());
                if (cell == null) continue;
                List<Agent> cellList = getOrCreateCellList(cell);
                cellList.add(agent);
                agentPositions.put(agent.getId(), cell);
                agentRecords.put(agent.getId(), new AgentRecord(agent, cell));
                pendingChanges.offer(new SpatialChange(SpatialChange.ChangeType.INSERT, agent.getId(), cell, null));
                changeCount.incrementAndGet();
                totalInserts.incrementAndGet();
                cellCache.remove(cell);
            }
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }

    public void unregisterAgent(String agentId) {
        if (agentId == null) return;
        long stamp = updateLock.writeLock();
        try {
            GridCell oldCell = agentPositions.remove(agentId);
            if (oldCell != null) {
                List<Agent> cellList = spatialGrid.get(oldCell);
                if (cellList != null) {
                    boolean removed = cellList.removeIf(a -> a.getId().equals(agentId));
                    if (removed) {
                        agentRecords.remove(agentId);
                        pendingChanges.offer(new SpatialChange(SpatialChange.ChangeType.REMOVE, agentId, null, oldCell));
                        changeCount.incrementAndGet();
                        totalRemoves.incrementAndGet();
                        cellCache.remove(oldCell);
                        // If cell becomes empty, we may leave it – optional cleanup
                    }
                }
            }
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }

    public void unregisterAgents(Collection<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return;
        long stamp = updateLock.writeLock();
        try {
            for (String agentId : agentIds) {
                GridCell oldCell = agentPositions.remove(agentId);
                if (oldCell != null) {
                    List<Agent> cellList = spatialGrid.get(oldCell);
                    if (cellList != null) {
                        cellList.removeIf(a -> a.getId().equals(agentId));
                        agentRecords.remove(agentId);
                        pendingChanges.offer(new SpatialChange(SpatialChange.ChangeType.REMOVE, agentId, null, oldCell));
                        changeCount.incrementAndGet();
                        totalRemoves.incrementAndGet();
                        cellCache.remove(oldCell);
                    }
                }
            }
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }

    public void updateAgentPosition(Agent agent) {
        if (agent == null) return;
        long stamp = updateLock.writeLock();
        try {
            String agentId = agent.getId();
            GridCell oldCell = agentPositions.get(agentId);
            GridCell newCell = getCellForPosition(agent.getX(), agent.getY());
            if (newCell == null) {
                if (oldCell != null) unregisterAgent(agentId);
                return;
            }
            if (oldCell == null) {
                registerAgent(agent);
            } else if (!oldCell.equals(newCell)) {
                List<Agent> oldList = spatialGrid.get(oldCell);
                if (oldList != null) oldList.removeIf(a -> a.getId().equals(agentId));
                List<Agent> newList = getOrCreateCellList(newCell);
                newList.add(agent);
                agentPositions.put(agentId, newCell);
                agentRecords.put(agentId, new AgentRecord(agent, newCell));
                pendingChanges.offer(new SpatialChange(SpatialChange.ChangeType.MOVE, agentId, newCell, oldCell));
                changeCount.incrementAndGet();
                totalUpdates.incrementAndGet();
                cellCache.remove(oldCell);
                cellCache.remove(newCell);
            }
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }

    // ------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------
    public List<Agent> getNearbyAgents(double x, double y, double radius) {
        queryCount.incrementAndGet();
        long stamp = updateLock.tryOptimisticRead();
        GridCell centerCell = getCellForPosition(x, y);
        if (centerCell == null) return Collections.emptyList();

        List<Agent> result = queryNearbyOptimistic(x, y, radius, centerCell);
        if (!updateLock.validate(stamp)) {
            stamp = updateLock.readLock();
            try {
                result = queryNearbyOptimistic(x, y, radius, centerCell);
            } finally {
                updateLock.unlockRead(stamp);
            }
        }
        return result;
    }

    private List<Agent> queryNearbyOptimistic(double x, double y, double radius, GridCell centerCell) {
        CellCacheKey cacheKey = new CellCacheKey(centerCell, radius);
        CellCacheEntry cached = cellCache.get(centerCell);
        if (cached != null && cached.isValidFor(cacheKey))
            return new ArrayList<>(cached.agents);

        List<Agent> result = new ArrayList<>();
        int cellsRadius = (int) Math.ceil(radius / cellSize);

        for (int dx = -cellsRadius; dx <= cellsRadius; dx++) {
            for (int dy = -cellsRadius; dy <= cellsRadius; dy++) {
                int checkX = centerCell.gridX + dx;
                int checkY = centerCell.gridY + dy;
                if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {
                    GridCell checkCell = new GridCell(checkX, checkY);
                    List<Agent> cellAgents = spatialGrid.get(checkCell);
                    if (cellAgents != null) {
                        for (Agent agent : cellAgents) {
                            double dist = Math.hypot(agent.getX() - x, agent.getY() - y);
                            if (dist <= radius) result.add(agent);
                        }
                    }
                }
            }
        }

        if (result.size() <= 1000)
            cacheQueryResult(centerCell, radius, result);
        return result;
    }

    private void cacheQueryResult(GridCell cell, double radius, List<Agent> agents) {
        if (cellCache.size() >= maxCacheSize) {
            GridCell oldest = cellCache.keySet().iterator().next();
            cellCache.remove(oldest);
        }
        cellCache.put(cell, new CellCacheEntry(new CellCacheKey(cell, radius), agents));
    }

    // ------------------------------------------------------------------------
    // Maintenance & statistics
    // ------------------------------------------------------------------------
    public void applyPendingChanges() {
        pendingChanges.clear();
        changeCount.set(0);
    }

    public void bulkUpdate(List<Agent> allAgents) {
        long stamp = updateLock.writeLock();
        try {
            spatialGrid.clear();
            agentPositions.clear();
            agentRecords.clear();
            cellCache.clear();
            for (Agent agent : allAgents) {
                GridCell cell = getCellForPosition(agent.getX(), agent.getY());
                if (cell != null) {
                    List<Agent> cellList = getOrCreateCellList(cell);
                    cellList.add(agent);
                    agentPositions.put(agent.getId(), cell);
                    agentRecords.put(agent.getId(), new AgentRecord(agent, cell));
                }
            }
            pendingChanges.clear();
            changeCount.set(0);
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }

    public List<Agent> getAllAgents() {
        List<Agent> all = new ArrayList<>();
        long stamp = updateLock.readLock();
        try {
            for (List<Agent> list : spatialGrid.values()) {
                all.addAll(list);
            }
        } finally {
            updateLock.unlockRead(stamp);
        }
        return all;
    }

    public List<Agent> getAgentsInCell(GridCell cell) {
        List<Agent> list = spatialGrid.get(cell);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    public GridCell getAgentCell(String agentId) { return agentPositions.get(agentId); }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAgents", agentRecords.size());
        stats.put("gridCells", gridWidth * gridHeight);
        stats.put("cellSizeDegrees", cellSize);
        stats.put("pendingChanges", changeCount.get());
        stats.put("queryCount", queryCount.get());
        stats.put("totalInserts", totalInserts.get());
        stats.put("totalRemoves", totalRemoves.get());
        stats.put("totalUpdates", totalUpdates.get());
        stats.put("cacheSize", cellCache.size());

        int maxAgents = 0, empty = 0, total = 0;
        for (List<Agent> list : spatialGrid.values()) {
            int size = list.size();
            total += size;
            maxAgents = Math.max(maxAgents, size);
            if (size == 0) empty++;
        }
        stats.put("maxAgentsPerCell", maxAgents);
        stats.put("emptyCells", empty);
        stats.put("avgAgentsPerCell", total / (double) (gridWidth * gridHeight));
        return stats;
    }

    public void clear() {
        long stamp = updateLock.writeLock();
        try {
            spatialGrid.clear();
            agentPositions.clear();
            agentRecords.clear();
            cellCache.clear();
            pendingChanges.clear();
            changeCount.set(0);
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }

    // ------------------------------------------------------------------------
    // Inner classes
    // ------------------------------------------------------------------------
    private static class AgentRecord {
        final Agent agent; final GridCell cell; final long timestamp;
        AgentRecord(Agent agent, GridCell cell) {
            this.agent = agent; this.cell = cell; this.timestamp = System.currentTimeMillis();
        }
    }

    private static class SpatialChange {
        enum ChangeType { INSERT, REMOVE, MOVE }
        final ChangeType type; final String agentId; final GridCell newCell, oldCell; final long timestamp;
        SpatialChange(ChangeType type, String agentId, GridCell newCell, GridCell oldCell) {
            this.type = type; this.agentId = agentId; this.newCell = newCell; this.oldCell = oldCell;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static class CellCacheKey {
        final GridCell cell; final double radius; final long timestamp;
        CellCacheKey(GridCell cell, double radius) {
            this.cell = cell; this.radius = radius; this.timestamp = System.currentTimeMillis();
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CellCacheKey)) return false;
            CellCacheKey that = (CellCacheKey) o;
            return Double.compare(that.radius, radius) == 0 && Objects.equals(cell, that.cell);
        }
        @Override public int hashCode() { return Objects.hash(cell, radius); }
    }

    private static class CellCacheEntry {
        final CellCacheKey key; final List<Agent> agents; final long created;
        CellCacheEntry(CellCacheKey key, List<Agent> agents) {
            this.key = key; this.agents = Collections.unmodifiableList(new ArrayList<>(agents));
            this.created = System.currentTimeMillis();
        }
        boolean isValidFor(CellCacheKey queryKey) {
            return key.equals(queryKey) && (System.currentTimeMillis() - created) < 1000;
        }
    }
}