package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.utils.SimulationLogger;
import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.LivingAgent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent LifeCycle Controller
 */
public class AgentLifeCycleManager {
    private final SpatialRegistry spatialRegistry;
    
    private final Queue<Agent> birthQueue = new ConcurrentLinkedQueue<>();
    private final Queue<String> deathQueue = new ConcurrentLinkedQueue<>();
    private final Queue<Agent> moveQueue = new ConcurrentLinkedQueue<>();
    
    private final Map<Class<?>, Queue<Agent>> agentPools = new ConcurrentHashMap<>();
    private final int maxPoolSize = 10000;
    
    private final AtomicInteger totalBirths = new AtomicInteger(0);
    private final AtomicInteger totalDeaths = new AtomicInteger(0);
    private final AtomicInteger totalMoves = new AtomicInteger(0);
    private final AtomicInteger poolHits = new AtomicInteger(0);
    private final AtomicInteger poolMisses = new AtomicInteger(0);
    
    private final int batchSize = 1000;
    private final ExecutorService lifecycleExecutor;
    
    public AgentLifeCycleManager(SpatialRegistry spatialRegistry) {
        this.spatialRegistry = spatialRegistry;
        int processors = Runtime.getRuntime().availableProcessors();
        this.lifecycleExecutor = Executors.newFixedThreadPool(
            Math.max(2, processors / 2),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Lifecycle-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
        initializeAgentPools();
    }
    
    private void initializeAgentPools() {
        agentPools.put(LivingAgent.class, new ConcurrentLinkedQueue<>());
    }
    
    public void scheduleBirth(Agent agent) {
        if (agent == null) return;
        birthQueue.offer(agent);
        totalBirths.incrementAndGet();
    }
    
    public void scheduleBirths(Collection<Agent> agents) {
        if (agents == null || agents.isEmpty()) return;
        birthQueue.addAll(agents);
        totalBirths.addAndGet(agents.size());
    }
    
    public void scheduleDeath(String agentId) {
        if (agentId == null) return;
        deathQueue.offer(agentId);
        totalDeaths.incrementAndGet();
    }
    
    public void scheduleMove(Agent agent) {
        if (agent == null) return;
        moveQueue.offer(agent);
        totalMoves.incrementAndGet();
    }
    
    public void processLifecycleEvents() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        if (!birthQueue.isEmpty()) futures.add(processBirthsBatch());
        if (!deathQueue.isEmpty()) futures.add(processDeathsBatch());
        if (!moveQueue.isEmpty()) futures.add(processMovesBatch());
        
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            SimulationLogger.severe("Error in lifecycle processing: " + e.getCause().getMessage());
        }
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
            List<String> batch = new ArrayList<>(batchSize * 10);
            String agentId;
            while ((agentId = deathQueue.poll()) != null && batch.size() < batchSize * 10) {
                batch.add(agentId);
            }
            if (!batch.isEmpty()) {
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
    
    public <T extends Agent> T createAgent(Class<T> agentClass, double x, double y) {
        Queue<Agent> pool = agentPools.get(agentClass);
        if (pool != null && !pool.isEmpty()) {
            T agent = agentClass.cast(pool.poll());
            if (agent != null) {
                resetAgent(agent, x, y);
                poolHits.incrementAndGet();
                return agent;
            }
        }
        poolMisses.incrementAndGet();
        return createNewAgent(agentClass, x, y);
    }
    
    @SuppressWarnings("unchecked")
    private <T extends Agent> T createNewAgent(Class<T> agentClass, double x, double y) {
        if (agentClass.equals(LivingAgent.class)) {
            return (T) new LivingAgent(x, y);
        }
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
    }
    
    public void returnToPool(Agent agent) {
        if (agent == null) return;
        Queue<Agent> pool = agentPools.get(agent.getClass());
        if (pool != null && pool.size() < maxPoolSize) {
            pool.offer(agent);
        }
    }
    
    public Agent immediateBirth(Class<? extends Agent> agentClass, double x, double y) {
        Agent agent = createAgent(agentClass, x, y);
        spatialRegistry.registerAgent(agent);
        return agent;
    }
    
    public void immediateDeath(String agentId) { spatialRegistry.unregisterAgent(agentId); }
    public void immediateMove(Agent agent) { spatialRegistry.updateAgentPosition(agent); }
    
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
        return stats;
    }
    
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