package com.monadvsim.app.models.engine;


import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.LivingAgent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


public class AgentLifecycleManager {
    private final SpatialRegistry spatialRegistry;
    
    // Lifecycle queues
    private final Queue<Agent> birthQueue = new ConcurrentLinkedQueue<>();
    private final Queue<String> deathQueue = new ConcurrentLinkedQueue<>();
    private final Queue<Agent> moveQueue = new ConcurrentLinkedQueue<>();
    
    // Agent pools for object reuse (reduces GC)
    private final Map<Class<?>, Queue<Agent>> agentPools = new ConcurrentHashMap<>();
    private final int maxPoolSize = 10000;
    
    // Statistics
    private final AtomicInteger totalBirths = new AtomicInteger(0);
    private final AtomicInteger totalDeaths = new AtomicInteger(0);
    private final AtomicInteger totalMoves = new AtomicInteger(0);
    private final AtomicInteger poolHits = new AtomicInteger(0);
    private final AtomicInteger poolMisses = new AtomicInteger(0);
    
    // Batch processing
    private final int batchSize = 1000;
    private final ExecutorService lifecycleExecutor;
    
    public AgentLifecycleManager(SpatialRegistry spatialRegistry) {
        this.spatialRegistry = spatialRegistry;
        
        // Create executor for lifecycle processing
        int processors = Runtime.getRuntime().availableProcessors();
        this.lifecycleExecutor = Executors.newFixedThreadPool(
            Math.max(2, processors / 2),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Lifecycle-" + count.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY);
                    return t;
                }
            }
        );
        
        // Initialize agent pools
        initializeAgentPools();
    }
    
    private void initializeAgentPools() {
        // Initialize pools for common agent types
        agentPools.put(LivingAgent.class, new ConcurrentLinkedQueue<>());
        // Add more agent types as needed
    }
    
    /**
     * Schedule an agent for birth (creation)
     */
    public void scheduleBirth(Agent agent) {
        if (agent == null) return;
        
        birthQueue.offer(agent);
        totalBirths.incrementAndGet();
    }
    
    /**
     * Schedule multiple agents for birth
     */
    public void scheduleBirths(Collection<Agent> agents) {
        if (agents == null || agents.isEmpty()) return;
        
        birthQueue.addAll(agents);
        totalBirths.addAndGet(agents.size());
    }
    
    /**
     * Schedule an agent for death (removal)
     */
    public void scheduleDeath(String agentId) {
        if (agentId == null) return;
        
        deathQueue.offer(agentId);
        totalDeaths.incrementAndGet();
    }
    
    /**
     * Schedule an agent for position update
     */
    public void scheduleMove(Agent agent) {
        if (agent == null) return;
        
        moveQueue.offer(agent);
        totalMoves.incrementAndGet();
    }
    
    /**
     * Process all scheduled lifecycle events
     * Called at the end of each simulation tick
     */
    public void processLifecycleEvents() {
        // Process in parallel batches for performance
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // Process births
        if (!birthQueue.isEmpty()) {
            futures.add(processBirthsBatch());
        }
        
        // Process deaths
        if (!deathQueue.isEmpty()) {
            futures.add(processDeathsBatch());
        }
        
        // Process moves
        if (!moveQueue.isEmpty()) {
            futures.add(processMovesBatch());
        }
        
        // Wait for all processing to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Lifecycle processing interrupted");
        } catch (ExecutionException e) {
            System.err.println("Error in lifecycle processing: " + e.getCause().getMessage());
        }
        
        // Apply pending changes to spatial registry
        spatialRegistry.applyPendingChanges();
    }
    
    private CompletableFuture<Void> processBirthsBatch() {
        return CompletableFuture.runAsync(() -> {
            List<Agent> batch = new ArrayList<>(batchSize);
            Agent agent;
            
            while ((agent = birthQueue.poll()) != null && batch.size() < batchSize) {
                batch.add(agent);
            }
            
            if (!batch.isEmpty()) {
                spatialRegistry.registerAgents(batch);
                
                // Return agents to pool if they die immediately (edge case)
                for (Agent a : batch) {
                    if (a instanceof LivingAgent la && !la.isAlive()) {
                        returnToPool(a);
                    }
                }
            }
        }, lifecycleExecutor);
    }
    
    private CompletableFuture<Void> processDeathsBatch() {
        return CompletableFuture.runAsync(() -> {
            List<String> batch = new ArrayList<>(batchSize * 10); // Larger batch
            String agentId;

            while ((agentId = deathQueue.poll()) != null && batch.size() < batchSize * 10) {
                batch.add(agentId);
            }

            if (!batch.isEmpty()) {
                // Use bulk unregister instead of individual calls
                spatialRegistry.unregisterAgents(batch);
            }
        }, lifecycleExecutor);
    }
    
    
    private CompletableFuture<Void> processMovesBatch() {
        return CompletableFuture.runAsync(() -> {
            List<Agent> batch = new ArrayList<>(batchSize);
            Agent agent;
            
            while ((agent = moveQueue.poll()) != null && batch.size() < batchSize) {
                batch.add(agent);
            }
            
            for (Agent a : batch) {
                spatialRegistry.updateAgentPosition(a);
            }
        }, lifecycleExecutor);
    }
    
    /**
     * Create a new agent using object pool
     */
    public <T extends Agent> T createAgent(Class<T> agentClass, double x, double y) {
        Queue<Agent> pool = agentPools.get(agentClass);
        
        if (pool != null && !pool.isEmpty()) {
            // Reuse agent from pool
            T agent = agentClass.cast(pool.poll());
            if (agent != null) {
                // Reset agent state
                resetAgent(agent, x, y);
                poolHits.incrementAndGet();
                return agent;
            }
        }
        
        poolMisses.incrementAndGet();
        
        // Create new agent
        return createNewAgent(agentClass, x, y);
    }
    
    @SuppressWarnings("unchecked")
    private <T extends Agent> T createNewAgent(Class<T> agentClass, double x, double y) {
        // Create new instance based on class
        if (agentClass.equals(LivingAgent.class)) {
            return (T) new LivingAgent(x, y);
        }
        // Add more agent types as needed
        
        throw new IllegalArgumentException("Unsupported agent class: " + agentClass);
    }
    
    private void resetAgent(Agent agent, double x, double y) {
        agent.setX(x);
        agent.setY(y);
        
        if (agent instanceof LivingAgent la) {
            la.setAlive(true);
            la.setAge(0);
            la.setGravid(false);
            la.setEnergy(1.0);
        }
        // Reset other agent types as needed
    }
    
    /**
     * Return agent to pool for reuse
     */
    public void returnToPool(Agent agent) {
        if (agent == null) return;
        
        Class<?> agentClass = agent.getClass();
        Queue<Agent> pool = agentPools.get(agentClass);
        
        if (pool != null && pool.size() < maxPoolSize) {
            pool.offer(agent);
        }
    }
    
    /**
     * Immediate birth (for time-critical operations)
     */
    public Agent immediateBirth(Class<? extends Agent> agentClass, double x, double y) {
        Agent agent = createAgent(agentClass, x, y);
        spatialRegistry.registerAgent(agent);
        return agent;
    }
    
    /**
     * Immediate death
     */
    public void immediateDeath(String agentId) {
        spatialRegistry.unregisterAgent(agentId);
    }
    
    /**
     * Immediate move update
     */
    public void immediateMove(Agent agent) {
        spatialRegistry.updateAgentPosition(agent);
    }
    
    /**
     * Get statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("pendingBirths", birthQueue.size());
        stats.put("pendingDeaths", deathQueue.size());
        stats.put("pendingMoves", moveQueue.size());
        stats.put("totalBirths", totalBirths.get());
        stats.put("totalDeaths", totalDeaths.get());
        stats.put("totalMoves", totalMoves.get());
        stats.put("poolHits", poolHits.get());
        stats.put("poolMisses", poolMisses.get());
        
        // Pool statistics
        Map<String, Integer> poolStats = new HashMap<>();
        for (Map.Entry<Class<?>, Queue<Agent>> entry : agentPools.entrySet()) {
            poolStats.put(entry.getKey().getSimpleName(), entry.getValue().size());
        }
        stats.put("agentPools", poolStats);
        
        return stats;
    }
    
    /**
     * Clear all queues and pools
     */
    public void clear() {
        birthQueue.clear();
        deathQueue.clear();
        moveQueue.clear();
        
        agentPools.values().forEach(Queue::clear);
        
        totalBirths.set(0);
        totalDeaths.set(0);
        totalMoves.set(0);
        poolHits.set(0);
        poolMisses.set(0);
    }
    
    /**
     * Shutdown lifecycle executor
     */
    public void shutdown() {
        lifecycleExecutor.shutdown();
        try {
            if (!lifecycleExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                lifecycleExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            lifecycleExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}