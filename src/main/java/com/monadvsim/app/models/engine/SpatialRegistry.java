package com.monadvsim.app.models.engine;


import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.GridCell;
import com.monadvsim.app.models.entities.QuadTree;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;


// Spatial registry with incremental updates for newborn agents
public class SpatialRegistry {
    
    private final Map<GridCell, List<Agent>> spatialGrid;
    private final Rectangle2D worldBounds;
    private final double cellSize;
    private final int gridWidth;
    private final int gridHeight;
    // Agent tracking with versioning
    private final Map<String, AgentRecord> agentRecords = new ConcurrentHashMap<>();
    private final Map<String, GridCell> agentPositions = new ConcurrentHashMap<>();
    
    // Change tracking
    private final Queue<SpatialChange> pendingChanges = new ConcurrentLinkedQueue<>();
    private final AtomicInteger changeCount = new AtomicInteger(0);
    private final StampedLock updateLock = new StampedLock();
    
    // Statistics
    private final AtomicInteger totalInserts = new AtomicInteger(0);
    private final AtomicInteger totalRemoves = new AtomicInteger(0);
    private final AtomicInteger totalUpdates = new AtomicInteger(0);
    private final AtomicInteger queryCount = new AtomicInteger(0);
    
    // Optimization: cache for frequently accessed cells
    private final Map<GridCell, CellCacheEntry> cellCache = new ConcurrentHashMap<>();
    private final int maxCacheSize = 1000;
    
    
    public SpatialRegistry(Rectangle2D worldBounds, double cellSize) {
        this.worldBounds = worldBounds;
        this.cellSize = cellSize;
        
        this.gridWidth = (int) Math.ceil(worldBounds.getWidth() / cellSize);
        this.gridHeight = (int) Math.ceil(worldBounds.getHeight() / cellSize);
        
        this.spatialGrid = new ConcurrentHashMap<>(gridWidth * gridHeight);
        initializeGrid();
        
        System.out.printf("Initialized SpatialRegistry [%dx%d cells, %.6f° cell size]%n",
            gridWidth, gridHeight, cellSize);
    }
    
    
    private void initializeGrid() {
        for (int x = 0; x < gridWidth; x++) {
            for (int y = 0; y < gridHeight; y++) {
                spatialGrid.put(new GridCell(x, y),
                        Collections.synchronizedList(new ArrayList<>()));
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
    
    
    // Register a new agent (for newborn agents)
    public void registerAgent(Agent agent) {
        if (agent == null) return;
        
        long stamp = updateLock.writeLock();
        try {
            GridCell cell = getCellForPosition(agent.getX(), agent.getY());
            if (cell != null) {
                // Add to spatial grid
                spatialGrid.get(cell).add(agent);
                
                // Update tracking
                agentPositions.put(agent.getId(), cell);
                agentRecords.put(agent.getId(), new AgentRecord(agent, cell));
                
                // Record change
                pendingChanges.offer(new SpatialChange(
                    SpatialChange.ChangeType.INSERT, agent.getId(), cell, null));
                changeCount.incrementAndGet();
                
                totalInserts.incrementAndGet();
                
                // Invalidate cell cache
                cellCache.remove(cell);
            }
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }
    
    
    // Batch register multiple agents (efficient for newborns)
    public void registerAgents(Collection<Agent> agents) {
        if (agents == null || agents.isEmpty()) return;
        
        long stamp = updateLock.writeLock();
        try {
            Map<GridCell, List<Agent>> cellsToAdd = new HashMap<>();
            
            // Group agents by cell
            for (Agent agent : agents) {
                GridCell cell = getCellForPosition(agent.getX(), agent.getY());
                if (cell != null) {
                    cellsToAdd.computeIfAbsent(cell, k -> new ArrayList<>()).add(agent);
                }
            }
            
            // Bulk add to cells
            for (Map.Entry<GridCell, List<Agent>> entry : cellsToAdd.entrySet()) {
                GridCell cell = entry.getKey();
                List<Agent> cellAgents = spatialGrid.get(cell);
                
                if (cellAgents != null) {
                    cellAgents.addAll(entry.getValue());
                    
                    // Update tracking for each agent
                    for (Agent agent : entry.getValue()) {
                        agentPositions.put(agent.getId(), cell);
                        agentRecords.put(agent.getId(), new AgentRecord(agent, cell));
                        
                        pendingChanges.offer(new SpatialChange(
                            SpatialChange.ChangeType.INSERT, agent.getId(), cell, null));
                        changeCount.incrementAndGet();
                        
                        totalInserts.incrementAndGet();
                    }
                    
                    // Invalidate cell cache
                    cellCache.remove(cell);
                }
            }
            
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }
    
    
    // Unregister an agent (for dead agents)
    public void unregisterAgent(String agentId) {
        if (agentId == null) return;
        
        long stamp = updateLock.writeLock();
        try {
            GridCell oldCell = agentPositions.get(agentId);
            if (oldCell != null) {
                List<Agent> cellAgents = spatialGrid.get(oldCell);
                if (cellAgents != null) {
                    // Remove agent from cell
                    boolean removed = cellAgents.removeIf(a -> a.getId().equals(agentId));
                    
                    if (removed) {
                        // Update tracking
                        agentPositions.remove(agentId);
                        agentRecords.remove(agentId);
                        
                        // Record change
                        pendingChanges.offer(new SpatialChange(
                            SpatialChange.ChangeType.REMOVE, agentId, null, oldCell));
                        changeCount.incrementAndGet();
                        
                        totalRemoves.incrementAndGet();
                        
                        // Invalidate cell cache
                        cellCache.remove(oldCell);
                    }
                }
            }
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }
    
    
    // Update agent position (for moving agents)
    public void updateAgentPosition(Agent agent) {
        if (agent == null) return;
        
        long stamp = updateLock.writeLock();
        try {
            String agentId = agent.getId();
            GridCell oldCell = agentPositions.get(agentId);
            GridCell newCell = getCellForPosition(agent.getX(), agent.getY());
            
            if (newCell == null) {
                // Agent moved out of bounds - remove it
                if (oldCell != null) {
                    unregisterAgent(agentId);
                }
                return;
            }
            
            if (oldCell == null) {
                // Agent not previously registered (shouldn't happen)
                registerAgent(agent);
            } else if (!oldCell.equals(newCell)) {
                // Agent moved to different cell
                // Remove from old cell
                List<Agent> oldCellAgents = spatialGrid.get(oldCell);
                if (oldCellAgents != null) {
                    oldCellAgents.removeIf(a -> a.getId().equals(agentId));
                }
                
                // Add to new cell
                List<Agent> newCellAgents = spatialGrid.get(newCell);
                if (newCellAgents != null) {
                    newCellAgents.add(agent);
                }
                
                // Update tracking
                agentPositions.put(agentId, newCell);
                agentRecords.put(agentId, new AgentRecord(agent, newCell));
                
                // Record change
                pendingChanges.offer(new SpatialChange(
                    SpatialChange.ChangeType.MOVE, agentId, newCell, oldCell));
                changeCount.incrementAndGet();
                
                totalUpdates.incrementAndGet();
                
                // Invalidate both cell caches
                cellCache.remove(oldCell);
                cellCache.remove(newCell);
            }
            // If same cell, no update needed
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }
    
    
    // Get nearby agents (includes newly registered agents)
    public List<Agent> getNearbyAgents(double x, double y, double radius) {
        queryCount.incrementAndGet();
        
        // Use optimistic read lock for high concurrency
        long stamp = updateLock.tryOptimisticRead();
        GridCell centerCell = getCellForPosition(x, y);
        
        if (centerCell == null) {
            return Collections.emptyList();
        }
        
        List<Agent> result = queryNearbyOptimistic(x, y, radius, centerCell);
        
        if (!updateLock.validate(stamp)) {
            // Optimistic read failed, use full read lock
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
        // Check cache first
        CellCacheKey cacheKey = new CellCacheKey(centerCell, radius);
        CellCacheEntry cached = cellCache.get(centerCell);
        
        if (cached != null && cached.isValidFor(cacheKey)) {
            return new ArrayList<>(cached.agents);
        }
        
        List<Agent> result = new ArrayList<>();
        int cellsRadius = (int) Math.ceil(radius / cellSize);
        
        // Search cells within radius
        for (int dx = -cellsRadius; dx <= cellsRadius; dx++) {
            for (int dy = -cellsRadius; dy <= cellsRadius; dy++) {
                int checkX = centerCell.gridX + dx;
                int checkY = centerCell.gridY + dy;
                
                if (checkX >= 0 && checkX < gridWidth && 
                    checkY >= 0 && checkY < gridHeight) {
                    
                    GridCell checkCell = new GridCell(checkX, checkY);
                    List<Agent> cellAgents = spatialGrid.get(checkCell);
                    
                    if (cellAgents != null) {
                        // Filter by actual distance
                        for (Agent agent : cellAgents) {
                            double distance = calculateDistance(x, y, agent.getX(), agent.getY());
                            if (distance <= radius) {
                                result.add(agent);
                            }
                        }
                    }
                }
            }
        }
        
        // Cache result if within limits
        if (result.size() <= 1000) { // Don't cache huge results
            cacheQueryResult(centerCell, radius, result);
        }
        
        return result;
    }
    
    
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        // Simple Euclidean distance (for small areas)
        // For larger areas, use Haversine formula
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    
    private void cacheQueryResult(GridCell cell, double radius, List<Agent> agents) {
        if (cellCache.size() >= maxCacheSize) {
            // Remove oldest entry (simplified LRU)
            GridCell oldest = cellCache.keySet().iterator().next();
            cellCache.remove(oldest);
        }
        
        CellCacheKey key = new CellCacheKey(cell, radius);
        cellCache.put(cell, new CellCacheEntry(key, agents));
    }
    

    // Apply pending changes from current tick
    public void applyPendingChanges() {
        // This is called at the end of each tick to finalize changes
        // In our incremental approach, changes are applied immediately,
        // but we can use this to clear the change queue
        pendingChanges.clear();
        changeCount.set(0);
    }
    

    // Bulk update (for initial load or reset)
    public void bulkUpdate(List<Agent> allAgents) {
        long stamp = updateLock.writeLock();
        try {
            // Clear existing data
            spatialGrid.values().forEach(List::clear);
            agentPositions.clear();
            agentRecords.clear();
            cellCache.clear();
            
            // Bulk insert all agents
            for (Agent agent : allAgents) {
                GridCell cell = getCellForPosition(agent.getX(), agent.getY());
                if (cell != null) {
                    spatialGrid.get(cell).add(agent);
                    agentPositions.put(agent.getId(), cell);
                    agentRecords.put(agent.getId(), new AgentRecord(agent, cell));
                }
            }
            
            // Reset counters
            pendingChanges.clear();
            changeCount.set(0);
            
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }
    

    // Get all agents (for debugging or serialization)
    public List<Agent> getAllAgents() {
        List<Agent> allAgents = new ArrayList<>();
        long stamp = updateLock.readLock();
        try {
            for (List<Agent> cellAgents : spatialGrid.values()) {
                allAgents.addAll(cellAgents);
            }
        } finally {
            updateLock.unlockRead(stamp);
        }
        return allAgents;
    }
    
    
    // Get agents in a specific cell (for testing)
    public List<Agent> getAgentsInCell(GridCell cell) {
        List<Agent> cellAgents = spatialGrid.get(cell);
        return cellAgents != null ? new ArrayList<>(cellAgents) : Collections.emptyList();
    }
    

    // Get agent position cell
    public GridCell getAgentCell(String agentId) {
        return agentPositions.get(agentId);
    }
    
 
    // Get statistics
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
        
        // Distribution statistics
        int maxAgentsPerCell = 0;
        int emptyCells = 0;
        int totalAgentsInGrid = 0;
        
        for (List<Agent> cellAgents : spatialGrid.values()) {
            int count = cellAgents.size();
            totalAgentsInGrid += count;
            maxAgentsPerCell = Math.max(maxAgentsPerCell, count);
            if (count == 0) emptyCells++;
        }
        
        stats.put("maxAgentsPerCell", maxAgentsPerCell);
        stats.put("emptyCells", emptyCells);
        stats.put("avgAgentsPerCell", totalAgentsInGrid / (double)(gridWidth * gridHeight));
        
        return stats;
    }

    
    // Clear all data
    public void clear() {
        long stamp = updateLock.writeLock();
        try {
            spatialGrid.values().forEach(List::clear);
            agentPositions.clear();
            agentRecords.clear();
            cellCache.clear();
            pendingChanges.clear();
            changeCount.set(0);
        } finally {
            updateLock.unlockWrite(stamp);
        }
    }
    
    
    // Inner classes
    private static class AgentRecord {
        final Agent agent;
        final GridCell cell;
        final long timestamp;
        
        AgentRecord(Agent agent, GridCell cell) {
            this.agent = agent;
            this.cell = cell;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    
    private static class SpatialChange {
        enum ChangeType { INSERT, REMOVE, MOVE }
        
        final ChangeType type;
        final String agentId;
        final GridCell newCell;
        final GridCell oldCell;
        final long timestamp;
        
        SpatialChange(ChangeType type, String agentId, GridCell newCell, GridCell oldCell) {
            this.type = type;
            this.agentId = agentId;
            this.newCell = newCell;
            this.oldCell = oldCell;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return String.format("%s: %s [%s -> %s]", type, agentId, oldCell, newCell);
        }
    }
    
    
    private static class CellCacheKey {
        final GridCell cell;
        final double radius;
        final long timestamp;
        
        CellCacheKey(GridCell cell, double radius) {
            this.cell = cell;
            this.radius = radius;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CellCacheKey that = (CellCacheKey) o;
            return Double.compare(that.radius, radius) == 0 &&
                   Objects.equals(cell, that.cell);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(cell, radius);
        }
    }
    
    
    private static class CellCacheEntry {
        final CellCacheKey key;
        final List<Agent> agents;
        final long created;
        
        CellCacheEntry(CellCacheKey key, List<Agent> agents) {
            this.key = key;
            this.agents = Collections.unmodifiableList(new ArrayList<>(agents));
            this.created = System.currentTimeMillis();
        }
        
        boolean isValidFor(CellCacheKey queryKey) {
            // Cache valid for 1 second
            return key.equals(queryKey) && (System.currentTimeMillis() - created) < 1000;
        }
    }
    
}