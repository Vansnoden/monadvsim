package com.monadvsim.app.models.entities;
import com.monadvsim.app.models.utils.SimulationLogger;

import com.monadvsim.app.models.engine.AgentLifeCycleManager;
import com.monadvsim.app.models.engine.LifecycleModel;
import com.monadvsim.app.models.engine.RuleEngine;
import com.monadvsim.app.models.engine.TimeManager;
import com.monadvsim.app.models.utils.SeedManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
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
    private LifecycleModel lifecycleModel; // new
    private final ExecutorService ruleExecutor;
    private TimeManager timeManager;
    private int batchSize;
    // Statistics
    private final AtomicLong rulesEvaluated = new AtomicLong(0);
    private final AtomicLong actionsExecuted = new AtomicLong(0);
    private final AtomicLong births = new AtomicLong(0);
    private final AtomicLong deaths = new AtomicLong(0);
    private final AtomicLong birthsThisTick = new AtomicLong(0);
    private final AtomicLong deathsThisTick = new AtomicLong(0);
    private final ThreadLocal<List<Agent>> immediateNewborns = ThreadLocal.withInitial(ArrayList::new);
    
    private Project project;
    
    public static record RuleDefinition(String condition, String action, int priority) {}

    public AgentLayer(String name, RuleEngine ruleEngine, AgentLifeCycleManager lifecycleManager) {
        super(name);
        this.ruleEngine = ruleEngine;
        this.lifecycleManager = lifecycleManager;
        this.rules = new CopyOnWriteArrayList<>();
        
        this.timeManager = null;

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
        this.rules = new CopyOnWriteArrayList<>();

        this.timeManager = null;
        
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int partitionCount = Math.max(2, availableProcessors);
        this.batchSize = Math.max(50, 500 / partitionCount);

        this.agentContainer = new AgentContainer(partitionCount);

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
    
    public void setProject(Project project) {
        this.project = project;
    }

    public void setTimeManager(TimeManager timeManager) {
        this.timeManager = timeManager;
    }
    
    public void setLifecycleModel(LifecycleModel model) {
        this.lifecycleModel = model;
    }

    public void addRule(String condition, String action, int priority) {
        rules.add(new RuleDefinition(condition, action, priority));
        rules.sort((r1, r2) -> Integer.compare(r2.priority(), r1.priority()));
    }

    @Override
    public void update(Project project) {
        long startTime = System.nanoTime();
        long timeLimit = startTime + 100_000_000L; // 100ms

        try {
            birthsThisTick.set(0);
            deathsThisTick.set(0);
            rulesEvaluated.set(0);

            if (lifecycleModel == null) {
                lifecycleModel = project.getLifecycleModel();
            }

            agentContainer.applyPendingOperations();
            processAgentsWithRules(project, timeLimit);
            processImmediateNewborns();
            cleanupDeadAgents();

            if (lifecycleManager != null) {
                lifecycleManager.processLifecycleEvents();
            }

            // Clear cache AFTER all agents in this tick are processed
            // This ensures cache hits within the same tick
            ruleEngine.clearCache();

        } catch (Exception e) {
            SimulationLogger.severe("Critical error in AgentLayer.update() for layer " + getName() + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to update layer " + getName(), e);
        }

        logUpdatePerformance(startTime);
    }

    private void processAgentsWithRules(Project project, long timeLimit) throws ExecutionException {
        immediateNewborns.remove();

        agentContainer.processWithBatching(batch -> {
            if (System.nanoTime() > timeLimit) return;
            for (Agent agent : batch) {
                processAgentRules(agent, project);
                if (System.nanoTime() > timeLimit) break;
            }
        }, 100);
    }


//    private void processAgentRules(Agent agent, Project project) {
//        if (agent == null) return;
//
//        if (agent instanceof LivingAgent la && !la.isAlive()) {
//            return;
//        }
//
//        if (rulesEvaluated.get() > 1_000_000_000L) {
//            SimulationLogger.severe("WARNING: Rule evaluation limit reached, "
//                    + "skipping further evaluations");
//            return;
//        }
//
//        synchronized (agent) {
//            try {
//                // --------------------------------------------------------------------
//                // 1. INERT AGENT: Passive hatching (eggs → larvae)
//                // --------------------------------------------------------------------
//                if (agent instanceof InertAgent ia && lifecycleModel != null) {
//                    double temperature = getTemperatureAt(project, ia.getX(), ia.getY());
//                    int hatched = ia.hatchEggs(temperature, lifecycleModel);
//                    Random rng = SeedManager.getRandom();
//                    if (hatched > 0) {
//                        AgentLayer mosquitoLayer = findMosquitoLayer(project);
//                        if (mosquitoLayer != null) {
//                            for (int i = 0; i < hatched; i++) {
//                                double x = ia.getX() + (rng.nextDouble() - 0.5) * 0.0001;
//                                double y = ia.getY() + (rng.nextDouble() - 0.5) * 0.0001;
//                                LivingAgent larva = (LivingAgent) mosquitoLayer.createAgentImmediately(LivingAgent.class, x, y);
//                                larva.setStage(LifecycleStage.LARVA);
//                                larva.setAge(0);
//                                larva.setEnergy(0.8);
//                            }
//                        }
//                    }
//                }
//
//                // --------------------------------------------------------------------
//                // 2. RULE EVALUATION FOR ALL AGENT TYPES
//                //    This is the key change - rules now run for InertAgent too!
//                // --------------------------------------------------------------------
//                for (RuleDefinition rule : rules) {
//                    rulesEvaluated.incrementAndGet();
//                    boolean evalResult = ruleEngine.evaluate(rule.condition(), agent, project);
//                    if (evalResult) {
//                        ruleEngine.execute(rule.action(), agent, project, this);
//                        actionsExecuted.incrementAndGet();
//
//                        // If LivingAgent died during rule execution, stop processing
//                        if (agent instanceof LivingAgent la && !la.isAlive()) {
//                            lifecycleManager.scheduleDeath(agent.getId());
//                            deathsThisTick.incrementAndGet();
//                            break;
//                        }
//                        if (isTerminalAction(rule.action())) break;
//                    }
//                }
//
//                // --------------------------------------------------------------------
//                // 3. LIVING AGENT: Lifecycle updates (age, resting, development, etc.)
//                //    These should only run for LivingAgent
//                // --------------------------------------------------------------------
//                
//                if (agent instanceof LivingAgent la) {
//                    // Update resting state
//                    if (la.isResting()) {
//                        la.incrementRestingDuration();
//                        if (la.getRestingDuration() > la.getMaxRestingDuration()) {
//                            la.setResting(false);
//                        }
//                    } else {
//                        la.incrementTimeWithoutRest();
//                        if (la.getTimeWithoutRest() > 96) {
//                            la.setAlive(false);
//                            lifecycleManager.scheduleDeath(agent.getId());
//                            deathsThisTick.incrementAndGet();
//                            return;
//                        }
//                    }
//
//                    // Age increment
//                    la.incrementAge();
//                    if (la.getAge() > project.getDefaultMaxAgentAge()) {
//                        la.setAlive(false);
//                        lifecycleManager.scheduleDeath(agent.getId());
//                        deathsThisTick.incrementAndGet();
//                        return;
//                    }
//
//                    // Temperature‑dependent development
//                    if (la.isAlive()) {
//                        double temperature = getTemperatureAt(project, la.getX(), la.getY());
//                        LifecycleStage stage = la.getStage();
//                        switch (stage) {
//                            case LARVA:
//                                lifecycleModel.tryAdvanceFromLarva(la, temperature);
//                                break;
//                            case PUPA:
//                                lifecycleModel.tryAdvanceFromPupa(la, temperature);
//                                break;
//                            case ADULT:
//                                lifecycleModel.applyAdultMortality(la, temperature);
//                                break;
//                            default:
//                                break;
//                        }
//                        if (!la.isAlive()) {
//                            lifecycleManager.scheduleDeath(agent.getId());
//                            deathsThisTick.incrementAndGet();
//                        }
//                    }
//                }
//            } catch (Exception e) {
//                SimulationLogger.severe("Error processing agent " 
//                        + agent.getId() 
//                        + ": " + e.getMessage());
//                e.printStackTrace();
//            }
//        }
//    }
    
    
//    private void processAgentRules(Agent agent, Project project) {
//        if (agent == null) return;
//
//        if (agent instanceof LivingAgent la && !la.isAlive()) {
//            return;
//        }
//
//        if (rulesEvaluated.get() > 1_000_000_000L) {
//            SimulationLogger.severe("WARNING: Rule evaluation limit reached, skipping further evaluations");
//            return;
//        }
//
//        synchronized (agent) {
//            try {
//                // ================================================================
//                // 1. INERT AGENT: Passive hatching (eggs → larvae)
//                // ================================================================
//                if (agent instanceof InertAgent ia && lifecycleModel != null) {
//                    double temperature = getTemperatureAt(project, ia.getX(), ia.getY());
//                    int hatched = ia.hatchEggs(temperature, lifecycleModel);
//                    Random rng = SeedManager.getRandom();
//                    if (hatched > 0 || ia.getLarvalCount() > 0) {
//                        ia.applyDensityDependentMortality();
//                    }
//                    if (hatched > 0) {
//                        AgentLayer mosquitoLayer = findMosquitoLayer(project);
//                        if (mosquitoLayer != null) {
//                            for (int i = 0; i < hatched; i++) {
//                                double x = ia.getX() + (rng.nextDouble() - 0.5) * 0.0001;
//                                double y = ia.getY() + (rng.nextDouble() - 0.5) * 0.0001;
//                                LivingAgent larva = (LivingAgent) mosquitoLayer.createAgentImmediately(LivingAgent.class, x, y);
//                                larva.setStage(LifecycleStage.LARVA);
//                                larva.setAge(0);
//                                larva.setStageAgeTicks(0); // <-- NEW: Reset stage age
//                                larva.setEnergy(0.8);
//                                larva.setDevelopmentProgress(0.0);
//                                larva.setAccumulatedDegreeDays(0.0); // <-- NEW: Reset degree-days
//                            }
//                        }
//                    }
//                }
//
//                // ================================================================
//                // 2. LIVING AGENT: Temperature‑dependent development
//                // ================================================================
//                if (agent instanceof LivingAgent la) {
//                    double temperature = getTemperatureAt(project, la.getX(), la.getY());
//                    LifecycleStage stage = la.getStage();
//
//                    if (la.isAlive()) {
//                        // Debug logging for larvae
//                        if (stage == LifecycleStage.LARVA && timeManager != null) {
//                            if (timeManager.getTickCount() % 100 == 0 && la.getId().hashCode() % 10 == 0) {
//                                SimulationLogger.fine("[DEBUG] LARVA: id=%s, stageAge=%d, progress=%.3f, DD=%.2f, temp=%.2fK",
//                                    la.getId().substring(0, 8), 
//                                    la.getStageAgeTicks(), // <-- Use stage age
//                                    la.getDevelopmentProgress(), 
//                                    la.getAccumulatedDegreeDays(),
//                                    temperature);
//                            }
//                        }
//
//                        // ================================================================
//                        // IMPORTANT: Increment stage age FIRST before any transitions
//                        // This ensures that even newly transitioned agents have their age tracked
//                        // ================================================================
//                        la.incrementStageAgeTicks();
//
//                        switch (stage) {
//                            case LARVA:
//                                lifecycleModel.tryAdvanceFromLarva(la, temperature);
//                                break;
//                            case PUPA:
//                                lifecycleModel.tryAdvanceFromPupa(la, temperature);
//                                break;
//                            case ADULT:
//                                lifecycleModel.applyAdultMortality(la, temperature);
//                                break;
//                            default:
//                                break;
//                        }
//                    }
//
//                    if (!la.isAlive()) {
//                        lifecycleManager.scheduleDeath(agent.getId());
//                        deathsThisTick.incrementAndGet();
//                        return;
//                    }
//
//                    // ================================================================
//                    // 3. RULE EVALUATION
//                    // ================================================================
//                    for (RuleDefinition rule : rules) {
//                        rulesEvaluated.incrementAndGet();
//                        boolean evalResult = ruleEngine.evaluate(rule.condition(), agent, project);
//                        if (evalResult) {
//                            ruleEngine.execute(rule.action(), agent, project, this);
//                            actionsExecuted.incrementAndGet();
//
//                            if (!la.isAlive()) {
//                                lifecycleManager.scheduleDeath(agent.getId());
//                                deathsThisTick.incrementAndGet();
//                                break;
//                            }
//                            if (isTerminalAction(rule.action())) break;
//                        }
//                    }
//
//                    // ================================================================
//                    // 4. AGING AND RESTING
//                    // ================================================================
//                    if (la.isAlive()) {
//                        if (la.isResting()) {
//                            la.incrementRestingDuration();
//                            if (la.getRestingDuration() > la.getMaxRestingDuration()) {
//                                la.setResting(false);
//                            }
//                        } else {
//                            la.incrementTimeWithoutRest();
//                            if (la.getTimeWithoutRest() > 96) {
//                                la.setAlive(false);
//                                lifecycleManager.scheduleDeath(agent.getId());
//                                deathsThisTick.incrementAndGet();
//                                return;
//                            }
//                        }
//
//                        // ================================================================
//                        // CRITICAL FIX: Use stageAgeTicks for stage-specific max age check
//                        // NOT the total age (which was being reset on transition)
//                        // ================================================================
//                        if (la.getStageAgeTicks() > la.getMaxAgeForStage()) {
//                            SimulationLogger.info("[DEATH] %s died of old age as %s at stage age %d (max %d)",
//                                la.getId().substring(0, 8), 
//                                la.getStage(), 
//                                la.getStageAgeTicks(), 
//                                la.getMaxAgeForStage());
//                            la.setAlive(false);
//                            lifecycleManager.scheduleDeath(agent.getId());
//                            deathsThisTick.incrementAndGet();
//                            return;
//                        }
//
//                        // Optional: Track total age for statistics (no longer used for mortality)
//                        la.incrementAge();
//                    }
//                }
//            } catch (Exception e) {
//                SimulationLogger.severe("Error processing agent " + agent.getId() + ": " + e.getMessage());
//                e.printStackTrace();
//            }
//        }
//    }
//    
    
    private void processAgentRules(Agent agent, Project project) {
        if (agent == null) return;
        if (agent instanceof LivingAgent la && !la.isAlive()) return;
        if (rulesEvaluated.get() > 1_000_000_000L) return;

        synchronized (agent) {
            try {
                // ================================================================
                // 1. INERT AGENT: Passive hatching (eggs → larvae)
                // ================================================================
                if (agent instanceof InertAgent ia && lifecycleModel != null) {
                    double temperature = getTemperatureAt(project, ia.getX(), ia.getY());
                    int hatched = ia.hatchEggs(temperature, lifecycleModel);
                    Random rng = SeedManager.getRandom();
                    if (hatched > 0 || ia.getLarvalCount() > 0) {
                        ia.applyDensityDependentMortality();
                    }
                    if (hatched > 0) {
                        AgentLayer mosquitoLayer = findMosquitoLayer(project);
                        if (mosquitoLayer != null) {
                            for (int i = 0; i < hatched; i++) {
                                double x = ia.getX() + (rng.nextDouble() - 0.5) * 0.0001;
                                double y = ia.getY() + (rng.nextDouble() - 0.5) * 0.0001;
                                LivingAgent larva = (LivingAgent) mosquitoLayer.createAgentImmediately(LivingAgent.class, x, y);
                                larva.setStage(LifecycleStage.LARVA);
                                larva.setAge(0);
                                larva.setStageAgeTicks(0);
                                larva.setEnergy(0.8);
                                larva.setDevelopmentProgress(0.0);
                                larva.setAccumulatedDegreeDays(0.0);
                            }
                        }
                    }
                }

                // ================================================================
                // 2. LIVING AGENT: Lifecycle updates (age, resting, development)
                //    MOVED UP BEFORE RULE EVALUATION!
                // ================================================================
                if (agent instanceof LivingAgent la) {
                    double temperature = getTemperatureAt(project, la.getX(), la.getY());
                    LifecycleStage stage = la.getStage();

                    if (la.isAlive()) {
                        // Debug logging for larvae
                        if (stage == LifecycleStage.LARVA && timeManager != null) {
                            if (timeManager.getTickCount() % 100 == 0 && la.getId().hashCode() % 10 == 0) {
                                SimulationLogger.fine("[DEBUG] LARVA: id=%s, stageAge=%d, progress=%.3f, DD=%.2f, temp=%.2fK",
                                    la.getId().substring(0, 8), 
                                    la.getStageAgeTicks(),
                                    la.getDevelopmentProgress(), 
                                    la.getAccumulatedDegreeDays(),
                                    temperature);
                            }
                        }

                        // IMPORTANT: Increment stage age FIRST before any transitions
                        la.incrementStageAgeTicks();

                        switch (stage) {
                            case LARVA:
                                lifecycleModel.tryAdvanceFromLarva(la, temperature);
                                break;
                            case PUPA:
                                lifecycleModel.tryAdvanceFromPupa(la, temperature);
                                break;
                            case ADULT:
                                lifecycleModel.applyAdultMortality(la, temperature);
                                break;
                            default:
                                break;
                        }
                    }

                    if (!la.isAlive()) {
                        lifecycleManager.scheduleDeath(agent.getId());
                        deathsThisTick.incrementAndGet();
                        return;
                    }

                    // Resting and aging updates
                    if (la.isAlive()) {
                        if (la.isResting()) {
                            la.incrementRestingDuration();
                            if (la.getRestingDuration() > la.getMaxRestingDuration()) {
                                la.setResting(false);
                            }
                        } else {
                            la.incrementTimeWithoutRest();
                            if (la.getTimeWithoutRest() > 96) {
                                la.setAlive(false);
                                lifecycleManager.scheduleDeath(agent.getId());
                                deathsThisTick.incrementAndGet();
                                return;
                            }
                        }

                        // Stage-specific max age check using stageAgeTicks
                        if (la.getStageAgeTicks() > la.getMaxAgeForStage()) {
                            SimulationLogger.info("[DEATH] %s died of old age as %s at stage age %d (max %d)",
                                la.getId().substring(0, 8), 
                                la.getStage(), 
                                la.getStageAgeTicks(), 
                                la.getMaxAgeForStage());
                            la.setAlive(false);
                            lifecycleManager.scheduleDeath(agent.getId());
                            deathsThisTick.incrementAndGet();
                            return;
                        }

                        // Track total age for statistics only (no longer used for mortality)
                        la.incrementAge();
                    }
                }

                // ================================================================
                // 3. RULE EVALUATION FOR ALL AGENT TYPES
                //    MOVED AFTER LIFE-CYCLE UPDATES!
                // ================================================================
                for (RuleDefinition rule : rules) {
                    rulesEvaluated.incrementAndGet();
                    boolean evalResult = ruleEngine.evaluate(rule.condition(), agent, project);
                    if (evalResult) {
                        ruleEngine.execute(rule.action(), agent, project, this);
                        actionsExecuted.incrementAndGet();

                        // If LivingAgent died during rule execution, stop processing
                        if (agent instanceof LivingAgent la && !la.isAlive()) {
                            lifecycleManager.scheduleDeath(agent.getId());
                            deathsThisTick.incrementAndGet();
                            break;
                        }
                        if (isTerminalAction(rule.action())) break;
                    }
                }

            } catch (Exception e) {
                SimulationLogger.severe("Error processing agent " + agent.getId() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    // New method: apply temperature‑driven stage transitions
    private void applyLifecycleTransitions(LivingAgent agent, Project project) {
        if (lifecycleModel == null) return;
        double temperature = getTemperatureAt(project, agent.getX(), agent.getY());
        LifecycleStage stage = agent.getStage();
        switch (stage) {
            case LARVA:
                lifecycleModel.tryAdvanceFromLarva(agent, temperature);
                break;
            case PUPA:
                lifecycleModel.tryAdvanceFromPupa(agent, temperature);
                break;
            case ADULT:
                lifecycleModel.applyAdultMortality(agent, temperature);
                break;
            default: // EGG is handled in InertAgent
                break;
        }
    }

    private double getTemperatureAt(Project project, double x, double y) {
        Layer tempLayer = project.getLayerByName("t2m");
        if (tempLayer != null) {
            return tempLayer.getValueAt(x, y);
        }
        return 295.15; // fallback 22°C
    }

    private AgentLayer findMosquitoLayer(Project project) {
        for (AgentLayer layer : project.getAgentLayers()) {
            if (layer.getName().equalsIgnoreCase("Mosquitoes")) {
                return layer;
            }
        }
        return null;
    }

    private void processImmediateNewborns() {
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
            List<Agent> deadAgentsList = Collections.synchronizedList(new ArrayList<>());
            agentContainer.processWithBatching(batch -> {
                List<Agent> localDead = new ArrayList<>();
                for (Agent agent : batch) {
                    if (agent instanceof LivingAgent la && !la.isAlive()) {
                        localDead.add(agent);
                    }
                }
                if (!localDead.isEmpty()) {
                    synchronized (deadAgentsList) {
                        deadAgentsList.addAll(localDead);
                    }
                }
            }, 500);

            if (!deadAgentsList.isEmpty()) {
                List<Agent> deadAgentsCopy;
                synchronized (deadAgentsList) {
                    deadAgentsCopy = new ArrayList<>(deadAgentsList);
                }
                agentContainer.markMultipleForRemoval(deadAgentsCopy);
                if (lifecycleManager != null) {
                    for (Agent agent : deadAgentsCopy) {
                        lifecycleManager.scheduleDeath(agent.getId());
                    }
                }
                deathsThisTick.addAndGet(deadAgentsCopy.size());
            }
            agentContainer.applyPendingOperations();
        } catch (ExecutionException ex) {
            Logger.getLogger(AgentLayer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

//    public Agent createAgentImmediately(Class<? extends Agent> agentClass, double x, double y) {
//        Agent agent = lifecycleManager.createAgent(agentClass, x, y);
//        lifecycleManager.scheduleBirth(agent);
//        List<Agent> newborns = immediateNewborns.get();
//        synchronized (newborns) {
//            newborns.add(agent);
//        }
//        return agent;
//    }
    
    public Agent createAgentImmediately(Class<? extends Agent> agentClass, double x, double y) {
        Agent agent = lifecycleManager.createAgent(agentClass, x, y);
        if (agent instanceof LivingAgent la) {
            // Set the global max age from project defaults
            if (project != null) {
                la.setGlobalMaxAge(project.getDefaultMaxAgentAge());
            }
        }
        lifecycleManager.scheduleBirth(agent);
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
        return action.equalsIgnoreCase("die") || action.equalsIgnoreCase("lay_eggs");
    }
    
    private void logUpdatePerformance(long startTime) {
        long duration = System.nanoTime() - startTime;
        double durationMs = duration / 1_000_000.0;
        // Only log every 10 ticks or if > 500ms
        if (durationMs > 500 || (getStatistics().get("agentCount") != null && 
            (Integer)getStatistics().get("agentCount") > 0 && 
            deathsThisTick.get() > 0)) {
            SimulationLogger.info("[%s] Update: %.2f ms | Agents: %d | Births: %d | Deaths: %d | Rules: %d",
                getName(), durationMs, agentContainer.size(),
                birthsThisTick.get(), deathsThisTick.get(), rulesEvaluated.get());
        }
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("agentCount", agentContainer.size());
        stats.put("rulesCount", rules.size());
        stats.put("rulesEvaluated", rulesEvaluated.get());
        stats.put("actionsExecuted", actionsExecuted.get());
        stats.put("birthsThisTick", birthsThisTick.get());
        stats.put("deathsThisTick", deathsThisTick.get());
        stats.put("avgRulesPerAgent", agentContainer.size() > 0 ? (double)rulesEvaluated.get() / agentContainer.size() : 0);
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

    public void addAgent(Agent agent) {
        agentContainer.addAgent(agent);
    }

    public void addAgents(List<Agent> agents) {
        agentContainer.addAgents(agents);
    }

    public List<Agent> getAgents() {
        List<Agent> agents = agentContainer.getAllAgents();
        return agents == null ? Collections.emptyList() : agents;
    }

    public void resetStatistics() {
        rulesEvaluated.set(0);
        actionsExecuted.set(0);
        births.set(0);
        deaths.set(0);
    }

    public AgentLifeCycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    public void setLifecycleManager(AgentLifeCycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }

    @Override
    public double getValueAt(double x, double y) {
        return 0.0;
    }
}