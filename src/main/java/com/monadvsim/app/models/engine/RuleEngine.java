package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.AgentLayer;
import com.monadvsim.app.models.entities.InertAgent;
import com.monadvsim.app.models.entities.Layer;
import com.monadvsim.app.models.entities.LifecycleStage;
import com.monadvsim.app.models.entities.LivingAgent;
import com.monadvsim.app.models.entities.Project;
import java.util.HashMap;
import javax.script.ScriptEngine;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

public class RuleEngine {
    
    private ScriptEngine engine;
    // Cache for compiled scripts (thread-safe)
    private final ConcurrentHashMap<String, CompiledScript> scriptCache = new ConcurrentHashMap<>();
    
    
    // Thread-local engine to avoid synchronization
    private static final ThreadLocal<ScriptEngine> threadLocalEngine = ThreadLocal.withInitial(() -> {
        System.setProperty("polyglot.js.nashorn-compat", "true");
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("graal.js");
        if (engine == null) {
            throw new RuntimeException("GraalVM JS Engine not found");
        }
        return engine;
    });
    
    
    public boolean evaluate(String condition, Agent agent, Project project) {
        ScriptEngine engine = threadLocalEngine.get();
        
        try {
            Bindings bindings = engine.createBindings();
            populateBindings(bindings, agent, project);
            
            // Try to use cached compiled script
            CompiledScript compiled = scriptCache.get(condition);
            if (compiled == null) {
                Compilable compilable = (Compilable) engine;
                compiled = compilable.compile(condition);
                scriptCache.putIfAbsent(condition, compiled);
            }
            
            Object result = compiled.eval(bindings);
            return result instanceof Boolean && (Boolean) result;
            
        } catch (ScriptException e) {
            System.err.println("Rule Syntax Error: " + condition + " -> " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("Unexpected error in rule evaluation: " + e.getMessage());
            return false;
        }
    }
    
    
    // Populate bindings in a thread-safe manner
    private void populateBindings(Bindings bindings, Agent agent, Project project) {
        
        if (project.getTokens().size() != project.getLayerNames().size()) {
            throw new IllegalArgumentException("Number of tokens and"
                    + " layers should be equal."
                    + "\n tokens size: " + project.getTokens().size()
                    + "\n layerNames: "+ project.getLayerNames().size());
        }

        // map agent environment values
        // tokens and layerNames should always have the same size 
        for(int i=0; i<project.getLayerNames().size(); i++){
            bindings.put(project.getTokens().get(i), getValueAt(project, 
                    project.getLayerNames().get(i), 
                    agent.getX(), 
                    agent.getY()));
        }
            
    }
    
    
    // Thread-safe action execution with proper synchronization
    public void execute(String action, Agent agent, Project project, AgentLayer layer) {
        switch (action.toLowerCase()) {
            case "die":
                executeDie(agent, layer);
                break;
                
            case "get_gravid":
                executeGetGravid(agent);
                break;
                
            case "lay_eggs":
                executeLayEggs(agent, project, layer);
                break;
                
            case "hatch":
                executeHatch(agent, project, layer);
                break;
                
            case "move_random":
                executeMoveRandom(agent, project, layer);
                break;
                
            case "reproduce":
                executeReproduce(agent, project, layer);
                break;
        }
    }
    
    
    private void executeDie(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setAlive(false);
            // Schedule immediate removal from spatial registry
            layer.killAgentImmediately(agent.getId());
        }
    }
    
    
    private void executeGetGravid(Agent agent) {
        if (agent instanceof LivingAgent la) {
            la.setGravid(true);
        }
    }
    
    
    private void executeLayEggs(Agent agent, Project project, AgentLayer layer) {
        // Get nearby tanks
        List<Agent> nearby = project.getSpatialRegistry()
            .getNearbyAgents(agent.getX(), agent.getY(), project.getDefaultAgentSearchRadius());
        
        // Find suitable tank
        for (Agent n : nearby) {
            if (n instanceof InertAgent ia) {
                // Add eggs to the tank
                ia.addEggs(project.getDefaultBirthRate());
                
                // Reset gravid status
                if (agent instanceof LivingAgent la) {
                    la.setGravid(false);
                }
                
                // Update tank position in spatial registry
                layer.updateAgentPositionImmediately(n);
                
                break; // Lay eggs in first suitable tank only
            }
        }
        
        // If no tank found, eggs are lost
        // Could optionally create a new tank here
    }
    
    
    private void executeHatch(Agent agent,  Project project, AgentLayer layer) {
        if (agent instanceof InertAgent ia && ia.getEggCount() > 0) {
            int eggsToHatch = (int) (ia.getEggCount() * project.getDefaultHatchingProbability());
            eggsToHatch = Math.max(1, Math.min(eggsToHatch, ia.getEggCount()));
    
            // Remove eggs from tank
            ia.takeEggs(eggsToHatch);
            
            // Create new mosquito agents immediately
            for (int i = 0; i < eggsToHatch; i++) {
                // Create at tank location with small random offset
                double x = ia.getX() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.0001;
                double y = ia.getY() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.0001;
                
                // Create agent immediately (added to spatial registry)
                LivingAgent larva;
                larva = (LivingAgent) layer.createAgentImmediately(LivingAgent.class, x, y);
                larva.setStage(LifecycleStage.LARVA);
                larva.setAge(0);
            }
            
            // Update tank in spatial registry
            layer.updateAgentPositionImmediately(ia);
        }
    }
    
    
    private void executeMoveRandom(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            double dx = (ThreadLocalRandom.current().nextDouble() - 0.5) * project.getDefaultAgentStep();
            double dy = (ThreadLocalRandom.current().nextDouble() - 0.5) * project.getDefaultAgentStep();
            
            la.move(dx, dy);
            
            // Update position in spatial registry immediately
            layer.updateAgentPositionImmediately(la);
        }
    }
    
    
    private void executeReproduce(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.isGravid()) {
            // Find mate nearby (includes newly created agents!)
            List<Agent> nearby = project.getSpatialRegistry()
                .getNearbyAgents(agent.getX(), agent.getY(), project.getDefaultAgentSearchRadius());
            
            for (Agent n : nearby) {
                if (n instanceof LivingAgent mate && 
                    mate != agent && 
                    !mate.isGravid() && 
                    mate.isAlive()) {
                    
                    // Create offspring
                    double x = (agent.getX() + mate.getX()) / 2;
                    double y = (agent.getY() + mate.getY()) / 2;
                    
                    LivingAgent offspring = (LivingAgent) layer.createAgentImmediately(
                        LivingAgent.class, x, y);
                    offspring.setStage(LifecycleStage.ADULT);
                    offspring.setAge(0);
                    
                    // Reset gravid status
                    la.setGravid(false);
                    
                    break;
                }
            }
        }
    }
    
    
    public void execute(String action, Agent agent, Project project) {
        switch (action.toLowerCase()) {
            case "die":
                if (agent instanceof LivingAgent la) {
                    la.setAlive(false);
                }
                break;
                
            case "get_gravid":
                if (agent instanceof LivingAgent la) {
                    la.setGravid(true);
                }
                break;
                
            case "lay_eggs":
                executeLayEggs(agent, project);
                break;
                
            case "hatch":
                if (agent instanceof InertAgent ia) {
                    // Use thread-safe hatching
                    int larvaeCount = ia.getLarvalCount();
                    int hatchCount = (int) (larvaeCount * project.getDefaultHatchingProbability());
                    if (hatchCount > 0) {
                        ia.decrementLarvalCount(hatchCount);
                        // Hatching logic would create new agents
                    }
                }
                break;
                
            case "move_random":
                if (agent instanceof LivingAgent la) {
                    double dx = (ThreadLocalRandom.current().nextDouble() - 0.5) * project.getDefaultAgentStep();
                    double dy = (ThreadLocalRandom.current().nextDouble() - 0.5) * project.getDefaultAgentStep();
                    la.move(dx, dy);
                }
                break;
        }
    }
    
   
    // Synchronized egg-laying to prevent race conditions
    private synchronized void executeLayEggs(Agent agent, Project project) {
        // Get nearby tanks
        List<Agent> nearby = project.getSpatialRegistry()
            .getNearbyAgents(agent.getX(), agent.getY(), project.getDefaultAgentSearchRadius());
        
        // Find first suitable tank
        for (Agent n : nearby) {
            if (n instanceof InertAgent ia) {
                // Add eggs to the tank (thread-safe)
                ia.addEggs(20);
                
                // Reset gravid status
                if (agent instanceof LivingAgent la) {
                    la.setGravid(false);
                }
                break; // Lay eggs in first suitable tank only
            }
        }
    }
    
    
    public void clearCache() {
        scriptCache.clear();
    }
    
    
    /**
     * Get cache statistics
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cachedScripts", scriptCache.size());
        stats.put("cacheMemory", "N/A"); // Could estimate memory usage
        return stats;
    }
    
    
    private double getValueAt(Project p, String layerName, double x, double y) {
        Layer layer = p.getLayerByName(layerName);
        return (layer != null) ? layer.getValueAt(x, y) : 0.0;
    }
}
