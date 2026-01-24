package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.ThreadSafeRuleEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AgentLayer extends Layer {
    private final ThreadSafeAgentContainer agentContainer;
    private final List<RuleDefinition> rules;
    private final ThreadSafeRuleEngine ruleEngine;
    private final ExecutorService ruleExecutor;
    private final int batchSize;
    
    // Statistics
    private final AtomicInteger rulesEvaluated = new AtomicInteger(0);
    private final AtomicInteger actionsExecuted = new AtomicInteger(0);
    private final AtomicInteger births = new AtomicInteger(0);
    private final AtomicInteger deaths = new AtomicInteger(0);
    
    public static record RuleDefinition(String condition, String action, int priority) {}
    
    public AgentLayer(String name, ThreadSafeRuleEngine ruleEngine) {
        super(name);
        this.ruleEngine = ruleEngine;
        this.rules = new CopyOnWriteArrayList<>(); // Thread-safe for rule modifications
        
        // Determine optimal partition count
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int partitionCount = Math.max(2, availableProcessors);
        this.batchSize = Math.max(100, 1000 / partitionCount);
        
        this.agentContainer = new ThreadSafeAgentContainer(partitionCount);
        
        // Create bounded thread pool for rule evaluation
        this.ruleExecutor = Executors.newFixedThreadPool(
            partitionCount,
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "RuleWorker-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            }
        );
    }
    
    public void addRule(String condition, String action, int priority) {
        rules.add(new RuleDefinition(condition, action, priority));
        // Sort rules by priority (highest first)
        rules.sort((r1, r2) -> Integer.compare(r2.priority(), r1.priority()));
    }
    
    @Override
    public void update(Project project) {
        long startTime = System.nanoTime();
        
        // 1. Apply pending agent operations (adds/removals)
        agentContainer.applyPendingOperations();
        
        // 2. Process agents with batching
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        agentContainer.processWithBatching(batch -> {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                processAgentBatch(batch, project);
            }, ruleExecutor);
            
            futures.add(future);
        }, batchSize);
        
        // 3. Wait for all batches to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Error processing agent batches: " + e.getMessage());
        }
        
        // 4. Log performance
        long duration = System.nanoTime() - startTime;
        logUpdatePerformance(duration);
    }
    
    /**
     * Process a batch of agents (thread-safe)
     */
    private void processAgentBatch(List<Agent> batch, Project project) {
        List<Agent> localNewborns = new ArrayList<>();
        
        for (Agent agent : batch) {
            try {
                processSingleAgent(agent, project, localNewborns);
            } catch (Exception e) {
                System.err.println("Error processing agent " + agent.getId() + ": " + e.getMessage());
                // Mark agent for removal if it's causing errors
                if (agent instanceof LivingAgent la) {
                    la.setAlive(false);
                }
            }
        }
        
        // Add newborns to container
        if (!localNewborns.isEmpty()) {
            agentContainer.addAgents(localNewborns);
            births.addAndGet(localNewborns.size());
        }
    }
    
    /**
     * Process a single agent (thread-safe)
     */
    private void processSingleAgent(Agent agent, Project project, List<Agent> newborns) {
        // Handle LivingAgent specific logic
        if (agent instanceof LivingAgent la) {
            // Check if agent is alive (thread-safe)
            if (!la.isAlive()) {
                agentContainer.markForRemoval(agent);
                deaths.incrementAndGet();
                return;
            }
            
            // Increment age (thread-safe)
            la.incrementAge();
        }
        
        // Evaluate rules in priority order
        for (RuleDefinition rule : rules) {
            rulesEvaluated.incrementAndGet();
            
            if (ruleEngine.evaluate(rule.condition(), agent, project)) {
                ruleEngine.execute(rule.action(), agent, project);
                actionsExecuted.incrementAndGet();
                
                // Stop after first matching rule if it's a terminal action
                if (isTerminalAction(rule.action())) {
                    break;
                }
            }
        }
        
        // Handle hatching for InertAgents
        if (agent instanceof InertAgent ia && ia.getEggCount() > 0) {
            handleHatching(ia, newborns);
        }
    }
    
    /**
     * Handle egg hatching (thread-safe)
     */
    private void handleHatching(InertAgent tank, List<Agent> newborns) {
        int eggsToHatch = (int) (tank.getEggCount() * 0.1); // 10% hatch rate
        
        if (eggsToHatch > 0) {
            int hatched = tank.takeEggs(eggsToHatch);
            
            for (int i = 0; i < hatched; i++) {
                LivingAgent larva = new LivingAgent(tank.getX(), tank.getY());
                larva.setStage(LifeCycleStage.LARVA);
                newborns.add(larva);
            }
        }
    }
    
    private boolean isTerminalAction(String action) {
        return action.equalsIgnoreCase("die") || 
               action.equalsIgnoreCase("lay_eggs");
    }
    
    private void logUpdatePerformance(long durationNanos) {
        double durationMs = durationNanos / 1_000_000.0;
        int agentCount = agentContainer.size();
        
        if (durationMs > 100) { // Log if update takes > 100ms
            System.out.printf("[%s] Update: %.2f ms for %d agents (%.2f µs/agent)%n",
                getName(), durationMs, agentCount, (durationNanos / agentCount) / 1000.0);
        }
    }
    
    /**
     * Thread-safe agent addition
     */
    public void addAgent(Agent agent) {
        agentContainer.addAgent(agent);
    }
    
    public void addAgents(List<Agent> agents) {
        agentContainer.addAgents(agents);
    }
    
    /**
     * Get agents for reporting (creates a copy)
     */
    public List<Agent> getAgents() {
        return agentContainer.getAllAgents();
    }
    
    /**
     * Get layer statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("agentCount", agentContainer.size());
        stats.put("rulesCount", rules.size());
        stats.put("rulesEvaluated", rulesEvaluated.get());
        stats.put("actionsExecuted", actionsExecuted.get());
        stats.put("births", births.get());
        stats.put("deaths", deaths.get());
        stats.putAll(agentContainer.getStatistics());
        
        return stats;
    }
    
    /**
     * Reset statistics
     */
    public void resetStatistics() {
        rulesEvaluated.set(0);
        actionsExecuted.set(0);
        births.set(0);
        deaths.set(0);
    }
    
    /**
     * Cleanup resources
     */
    public void shutdown() {
        ruleExecutor.shutdown();
        try {
            if (!ruleExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ruleExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ruleExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}