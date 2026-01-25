package com.monadvsim.app.models.entities;


import com.monadvsim.app.models.engine.AgentLifecycleManager;
import com.monadvsim.app.models.engine.RuleEngine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


public class AgentLayer extends Layer {
    
    private final AgentContainer agentContainer;
    private final List<RuleDefinition> rules;
    private final RuleEngine ruleEngine;
    private AgentLifecycleManager lifecycleManager;
    private final ExecutorService ruleExecutor;
    private int batchSize;
    // Statistics
    private final AtomicInteger rulesEvaluated = new AtomicInteger(0);
    private final AtomicInteger actionsExecuted = new AtomicInteger(0);
    private final AtomicInteger births = new AtomicInteger(0);
    private final AtomicInteger deaths = new AtomicInteger(0);
    private final AtomicInteger birthsThisTick = new AtomicInteger(0);
    private final AtomicInteger deathsThisTick = new AtomicInteger(0);
    private final ThreadLocal<List<Agent>> immediateNewborns = ThreadLocal.withInitial(ArrayList::new);

    
    
    public static record RuleDefinition(String condition, String action, int priority) {}
    
    
    public AgentLayer(String name, RuleEngine ruleEngine, 
                     AgentLifecycleManager lifecycleManager) {
        super(name);
        this.ruleEngine = ruleEngine;
        this.lifecycleManager = lifecycleManager;
        this.rules = new CopyOnWriteArrayList<>();
        
        int partitionCount = Runtime.getRuntime().availableProcessors();
        this.agentContainer = new AgentContainer(partitionCount);
        
        this.ruleExecutor = Executors.newFixedThreadPool(
            partitionCount,
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "RuleWorker-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
    }
    
    
    public AgentLayer(String name, RuleEngine ruleEngine) {
        super(name);
        this.ruleEngine = ruleEngine;
        this.rules = new CopyOnWriteArrayList<>(); // Thread-safe for rule modifications
        
        // Determine optimal partition count
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int partitionCount = Math.max(2, availableProcessors);
        this.batchSize = Math.max(50, 500 / partitionCount); // before 100, 1000
        
        this.agentContainer = new AgentContainer(partitionCount);
        
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

        try {
            // Reset per-tick counters
            birthsThisTick.set(0);
            deathsThisTick.set(0);

            // Apply pending agent operations from previous tick
            agentContainer.applyPendingOperations();

            // Process all agents with rules
            processAgentsWithRules(project);

            // Process immediate newborns created during rule execution
            processImmediateNewborns();

            // Clean up dead agents
            cleanupDeadAgents();

            // Process scheduled lifecycle events
            lifecycleManager.processLifecycleEvents();

        } catch (Exception e) {
            System.err.println("Critical error in AgentLayer.update() for "
                    + "layer " + getName() + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to update layer " + getName(), e);
        }

        // Log performance
        logUpdatePerformance(startTime);
    }
    
    
    private void processAgentsWithRules(Project project) {
        // Clear thread-local newborns
        immediateNewborns.remove();

        // Use thread-safe collection for futures
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Process agents in parallel partitions
        agentContainer.processWithBatching(batch -> {
            // Create a copy of the batch to avoid concurrent modification
            List<Agent> batchCopy = new ArrayList<>(batch);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (Agent agent : batchCopy) {
                    processAgentRules(agent, project);
                }
            }, ruleExecutor);

            futures.add(future);
        }, 100); // Process in batches of 100

        // Wait for all batches to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Error processing agent rules: " + e.getMessage());
            e.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }
    
    
    private void processAgentRules(Agent agent, Project project) {
        try {
            // Check if agent is alive (for LivingAgent)
            if (agent instanceof LivingAgent la && !la.isAlive()) {
                // Schedule for death
                lifecycleManager.scheduleDeath(agent.getId());
                deathsThisTick.incrementAndGet();
                return;
            }

            // Age increment for LivingAgent
            if (agent instanceof LivingAgent la) {
                la.incrementAge();

                // Aging death (after 30 days at 15-min intervals: 30*24*4 = 2880 ticks)
                if (la.getAge() > project.getDefaultMaxAgentAge()) {
                    la.setAlive(false);
                    lifecycleManager.scheduleDeath(agent.getId());
                    deathsThisTick.incrementAndGet();
                    return;
                }
            }

            // Evaluate rules
            for (RuleDefinition rule : rules) {
                rulesEvaluated.incrementAndGet();

                if (ruleEngine.evaluate(rule.condition(), agent, project)) {
                    // Execute rule - this may create immediate newborns
                    ruleEngine.execute(rule.action(), agent, project, this);
                    actionsExecuted.incrementAndGet();

                    // Check if agent died during rule execution
                    if (agent instanceof LivingAgent la2 && !la2.isAlive()) {
                        lifecycleManager.scheduleDeath(agent.getId());
                        deathsThisTick.incrementAndGet();
                        break; // Stop processing rules for dead agent
                    }

                    // Stop after terminal actions
                    if (isTerminalAction(rule.action())) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing agent " + agent.getId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    private boolean agentHasMoved(Agent agent) {
        // Check if agent position changed since last update
        // This would require tracking previous positions
        return false; // Simplified for now
    }
    
    
    private void processImmediateNewborns() {
        // Get thread-local newborns and add to container
        List<Agent> newborns = immediateNewborns.get();
        if (!newborns.isEmpty()) {
            synchronized (newborns) {
                List<Agent> newbornsCopy = new ArrayList<>(newborns);
                agentContainer.addAgents(newbornsCopy);
                birthsThisTick.addAndGet(newbornsCopy.size());
                newborns.clear();
            }
        }
    }
    
    
    private void cleanupDeadAgents() {
        // Remove agents marked for death from container
        // This happens after lifecycle manager processes deaths
        agentContainer.applyPendingOperations();
    }
    
    
    public Agent createAgentImmediately(Class<? extends Agent> agentClass, double x, double y) {
        // Create agent using lifecycle manager
        Agent agent = lifecycleManager.createAgent(agentClass, x, y);

        // Add to spatial registry immediately
        lifecycleManager.scheduleBirth(agent);

        // Also add to container for this layer
        List<Agent> newborns = immediateNewborns.get();
        synchronized (newborns) {
            newborns.add(agent);
        }

        return agent;
    }
    
    
    public void killAgentImmediately(String agentId) {
        lifecycleManager.immediateDeath(agentId);
    }
    
    
    public void updateAgentPositionImmediately(Agent agent) {
        lifecycleManager.immediateMove(agent);
    }
    
    
    private boolean isTerminalAction(String action) {
        return action.equalsIgnoreCase("die") || 
               action.equalsIgnoreCase("lay_eggs");
    }
    
    private void logUpdatePerformance(long startTime) {
        long duration = System.nanoTime() - startTime;
        double durationMs = duration / 1_000_000.0;

        System.out.printf("[%s] Update: %.2f ms | Agents: %d | Births: %d | Deaths: %d | Rules: %d%n",
            getName(), durationMs, agentContainer.size(), 
            birthsThisTick.get(), deathsThisTick.get(), rulesEvaluated.get());
    }
    
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("agentCount", agentContainer.size());
        stats.put("rulesCount", rules.size());
        stats.put("rulesEvaluated", rulesEvaluated.get());
        stats.put("birthsThisTick", birthsThisTick.get());
        stats.put("deathsThisTick", deathsThisTick.get());
        stats.putAll(agentContainer.getStatistics());
        
        return stats;
    }
    
    
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
    
    
    // Process a batch of agents (thread-safe)
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
    
    
    // Process a single agent (thread-safe)
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
    
    
    // Handle egg hatching (thread-safe)
    private void handleHatching(InertAgent tank, List<Agent> newborns) {
        int eggsToHatch = (int) (tank.getEggCount() * 0.1); // 10% hatch rate
        
        if (eggsToHatch > 0) {
            int hatched = tank.takeEggs(eggsToHatch);
            
            for (int i = 0; i < hatched; i++) {
                LivingAgent larva = new LivingAgent(tank.getX(), tank.getY());
                larva.setStage(LifecycleStage.LARVA);
                newborns.add(larva);
            }
        }
    }
    
    
    public void addAgent(Agent agent) {
        agentContainer.addAgent(agent);
    }
    
    
    public void addAgents(List<Agent> agents) {
        agentContainer.addAgents(agents);
    }
    
    
    public List<Agent> getAgents() {
        return agentContainer.getAllAgents();
    }
    
    
    // Reset statistics
    public void resetStatistics() {
        rulesEvaluated.set(0);
        actionsExecuted.set(0);
        births.set(0);
        deaths.set(0);
    }
    
    
    public AgentLifecycleManager getLifecycleManager(){
        return lifecycleManager;
    }
    

    @Override
    public double getValueAt(double x, double y) {
        return 0.0;
    }
    
    // Add this setter method to AgentLayer class:
    public void setLifecycleManager(AgentLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }
    
}