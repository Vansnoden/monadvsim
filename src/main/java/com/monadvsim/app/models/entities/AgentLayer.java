package com.monadvsim.app.models.entities;


import com.monadvsim.app.models.engine.AgentLifeCycleManager;
import com.monadvsim.app.models.engine.RuleEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Agent Container & Rule Processor
 *
 * Contains and manages agents within a simulation layer
 *
 * Processes agent rules in parallel batches
 *
 * Integrates with RuleEngine for behavior evaluation
 *
 * Manages agent lifeCycle events (births, deaths)
 *
 * Provides statistics and performance monitoring
 * 
 * 
 * @author void
 */


public class AgentLayer extends Layer {
    
    private final AgentContainer agentContainer;
    private final List<RuleDefinition> rules;
    private final RuleEngine ruleEngine;
    private AgentLifeCycleManager lifecycleManager;
    private final ExecutorService ruleExecutor;
    private int batchSize;
    // Statistics
    private final AtomicLong rulesEvaluated = new AtomicLong(0);
    private final AtomicLong actionsExecuted = new AtomicLong(0);
    private final AtomicLong births = new AtomicLong(0);
    private final AtomicLong deaths = new AtomicLong(0);
    private final AtomicLong birthsThisTick = new AtomicLong(0);
    private final AtomicLong deathsThisTick = new AtomicLong(0);
    private final ThreadLocal<List<Agent>> immediateNewborns = ThreadLocal.withInitial(ArrayList::new);

    
    
    public static record RuleDefinition(String condition, String action, int priority) {}
    
    
    public AgentLayer(String name, RuleEngine ruleEngine, 
                     AgentLifeCycleManager lifecycleManager) {
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

        // Add a time limit for layer update (max 100ms)
        long timeLimit = startTime + 100_000_000L; // 100ms in nanoseconds

        try {
            // Reset per-tick counters
            birthsThisTick.set(0);
            deathsThisTick.set(0);
            rulesEvaluated.set(0); // Reset each tick

            // Apply pending agent operations from previous tick
            agentContainer.applyPendingOperations();

            // Process all agents with rules
            processAgentsWithRules(project, timeLimit);

            // Process immediate newborns created during rule execution
            processImmediateNewborns();

            // Clean up dead agents
            cleanupDeadAgents();

            // Process scheduled lifecycle events
            if (lifecycleManager != null) {
                lifecycleManager.processLifecycleEvents();
            }

        } catch (Exception e) {
            System.err.println("Critical error in AgentLayer.update() for "
                    + "layer " + getName() + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to update layer " + getName(), e);
        }

        // Log performance
        logUpdatePerformance(startTime);
    }
    
    
    private void processAgentsWithRules(Project project, long timeLimit) throws ExecutionException {
        // Clear thread-local newborns
        immediateNewborns.remove();

        // Process agents in batches with timeout check
        agentContainer.processWithBatching(batch -> {
            // Check if we've exceeded time limit
            if (System.nanoTime() > timeLimit) {
//                System.out.println("Time limit reached for " + getName() + ", skipping remaining agents");
                return;
            }

            for (Agent agent : batch) {
                processAgentRules(agent, project);

                // Check time limit after each agent
                if (System.nanoTime() > timeLimit) {
//                    System.out.println("Time limit reached for " + getName() + ", skipping remaining agents");
                    break;
                }
            }
        }, 100);
    }

    
    private void processAgentsWithRules(Project project) throws ExecutionException {
        // Clear thread-local newborns
        immediateNewborns.remove();

        // Process agents in batches
        agentContainer.processWithBatching(batch -> {
            for (Agent agent : batch) {
                processAgentRules(agent, project);
            }
        }, 100);
    }
    
    
    private void processAgentRules(Agent agent, Project project) {
        if (agent == null) return;
        
        // Check if agent is already dead
        if (agent instanceof LivingAgent la && !la.isAlive()) {
            return; // Skip processing, will be cleaned up later
        }

        // Add safety check - skip processing if too many rules have been evaluated
        if (rulesEvaluated.get() > 1_000_000_000L) { // 1 billion limit
            System.err.println("WARNING: Rule evaluation limit reached, skipping further evaluations");
            return;
        }

        synchronized (agent) {
            try {
                // Check if agent is alive (for LivingAgent)
                if (agent instanceof LivingAgent la && !la.isAlive()) {
                    // Schedule for death
                    lifecycleManager.scheduleDeath(agent.getId());
                    deathsThisTick.incrementAndGet();
                    return;
                }
                
                if (agent instanceof LivingAgent la) {
                    // Update resting state
                    if (la.isResting()) {
                        la.incrementRestingDuration();
                        // Auto-stop resting after maximum duration
                        if (la.getRestingDuration() > la.getMaxRestingDuration()) {
                            la.setResting(false);
                        }
                    } else {
                        la.incrementTimeWithoutRest();

                        // Exhaustion death if too long without rest
                        if (la.getTimeWithoutRest() > 96) { // 24 hours without rest
                            la.setAlive(false);
                            lifecycleManager.scheduleDeath(agent.getId());
                            return;
                        }
                    }
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

                    // Check lifecycle progression
                    if (la.shouldPupate()) {
                        la.setStage(LifecycleStage.PUPA);
                        la.setEnergy(0.6);
                    } else if (la.shouldEmerge()) {
                        la.setStage(LifecycleStage.ADULT);
                        la.setEnergy(0.9);
                    }
                }

                // Evaluate rules with a limit per agent
//                int maxRulesPerAgent = 100;
//                int rulesChecked = 0;

                for (RuleDefinition rule : rules) {
//                    if (rulesChecked++ >= maxRulesPerAgent) {
//                        break; // Prevent infinite rule evaluation
//                    }

                    rulesEvaluated.incrementAndGet();

                    if (ruleEngine.evaluate(rule.condition(), agent, project)) {
                        // Execute rule
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
        
        // When agent dies, just mark it, don't schedule immediately
        if (agent instanceof LivingAgent la2 && !la2.isAlive()) {
            // Just mark for cleanup, don't schedule death here
            // The death will be handled in cleanupDeadAgents()
            return;
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
        try {
            // Create a thread-safe copy of dead agents
            List<Agent> deadAgentsList = Collections.synchronizedList(new ArrayList<>());

            // Process agents to find dead ones - ensure thread safety
            agentContainer.processWithBatching(batch -> {
                List<Agent> localDeadAgents = new ArrayList<>();
                for (Agent agent : batch) {
                    if (agent instanceof LivingAgent la && !la.isAlive()) {
                        localDeadAgents.add(agent);
                    }
                }

                // Add to synchronized list
                if (!localDeadAgents.isEmpty()) {
                    synchronized (deadAgentsList) {
                        deadAgentsList.addAll(localDeadAgents);
                    }
                }
            }, 500);

            // Bulk mark for removal (thread-safe)
            if (!deadAgentsList.isEmpty()) {
                // Use synchronized block when accessing the list
                List<Agent> deadAgentsCopy;
                synchronized (deadAgentsList) {
                    deadAgentsCopy = new ArrayList<>(deadAgentsList);
                }

                agentContainer.markMultipleForRemoval(deadAgentsCopy);

                // Bulk schedule deaths in lifecycle manager
                if (lifecycleManager != null) {
                    for (Agent agent : deadAgentsCopy) {
                        lifecycleManager.scheduleDeath(agent.getId());
                    }
                }

                deathsThisTick.addAndGet(deadAgentsCopy.size());
            }

            // Apply pending operations
            agentContainer.applyPendingOperations();
        } catch (ExecutionException ex) {
            Logger.getLogger(AgentLayer.class.getName()).log(Level.SEVERE, null, ex);
        }
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
    
    
//    public Map<String, Object> getStatistics() {
//        Map<String, Object> stats = new HashMap<>();
//        stats.put("agentCount", agentContainer.size());
//        stats.put("rulesCount", rules.size());
//        stats.put("rulesEvaluated", rulesEvaluated.get());
//        stats.put("birthsThisTick", birthsThisTick.get());
//        stats.put("deathsThisTick", deathsThisTick.get());
//        stats.putAll(agentContainer.getStatistics());
//        
//        return stats;
//    }
    
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("agentCount", agentContainer.size());
        stats.put("rulesCount", rules.size());
        stats.put("rulesEvaluated", rulesEvaluated.get());
        stats.put("actionsExecuted", actionsExecuted.get());
        stats.put("birthsThisTick", birthsThisTick.get());
        stats.put("deathsThisTick", deathsThisTick.get());

        // Add performance metrics
        stats.put("avgRulesPerAgent", agentContainer.size() > 0 ? 
            (double)rulesEvaluated.get() / agentContainer.size() : 0);

        // Log if rules are being evaluated excessively
        if (rulesEvaluated.get() > 1000000) { // More than 1 million rules
            System.err.println("WARNING: Excessive rule evaluations in " + getName() + 
                              ": " + rulesEvaluated.get() + " evaluations for " + 
                              agentContainer.size() + " agents");
        }

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
                ruleEngine.execute(rule.action(), agent, project, this);
                actionsExecuted.incrementAndGet();
                
                // Stop after first matching rule if it's a terminal action
                if (isTerminalAction(rule.action())) {
                    break;
                }
            }
        }
        
        // Handle hatching for InertAgents
//        if (agent instanceof InertAgent ia && ia.getEggCount() > 0) {
//            handleHatching(ia, newborns);
//        }
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
        // Add safety check
        List<Agent> agents = agentContainer.getAllAgents();
        if (agents == null) {
            return Collections.emptyList();
        }
        return agents;
    }
    
    
    // Reset statistics
    public void resetStatistics() {
        rulesEvaluated.set(0);
        actionsExecuted.set(0);
        births.set(0);
        deaths.set(0);
    }
    
    
    public AgentLifeCycleManager getLifecycleManager(){
        return lifecycleManager;
    }
    

    @Override
    public double getValueAt(double x, double y) {
        return 0.0;
    }
    
    // Add this setter method to AgentLayer class:
    public void setLifecycleManager(AgentLifeCycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }
    
    
    // Progressive cleanup to avoid spikes
    private void progressiveCleanup() {
        int agentCount = agentContainer.size();

        // Only clean up a portion at a time to avoid spikes
        if (deathsThisTick.get() > agentCount * 0.1) { // More than 10% died
            System.out.println("Progressive cleanup: " + deathsThisTick.get() + 
                              " deaths out of " + agentCount + " agents");

            // Process in smaller batches
            int batchLimit = Math.max(1000, agentCount / 10);
            List<Agent> deadBatch = new ArrayList<>(batchLimit);

            try {
                agentContainer.processWithBatching(batch -> {
                    for (Agent agent : batch) {
                        if (agent instanceof LivingAgent la && !la.isAlive()) {
                            deadBatch.add(agent);
                            if (deadBatch.size() >= batchLimit) {
                                break;
                            }
                        }
                    }
                }, 200);
            } catch (ExecutionException ex) {
                Logger.getLogger(AgentLayer.class.getName()).log(Level.SEVERE, null, ex);
            }

            // Process this batch now, leave rest for next tick
            if (!deadBatch.isEmpty()) {
                agentContainer.markMultipleForRemoval(deadBatch);
                deathsThisTick.set(deadBatch.size());
            }
        }
    }
    
}