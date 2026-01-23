package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.engine.RuleEngine;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;


public class AgentLayer extends Layer {
    // Using CopyOnWriteArrayList to prevent ConcurrentModificationException during hatching/death
    private List<Agent> agents = new CopyOnWriteArrayList<>();
    private List<RuleDefinition> rules = new ArrayList<>();
    private final RuleEngine ruleEngine;

    
    public AgentLayer(String name, RuleEngine ruleEngine) {
        super(name);
        this.ruleEngine = ruleEngine;
    }

    public static record RuleDefinition(String condition, String action) {}

    public void addRule(String condition, String action) {
        rules.add(new RuleDefinition(condition, action));
    }
    
    @Override
    public void update(Project project) {
        List<Agent> newborns = new ArrayList<>();

        agents.parallelStream().forEach(agent -> {
            if (agent instanceof LivingAgent la){
                if (!la.isAlive()) return;
                // Increment age at start of tick
                la.setAge(la.getAge() + 1);
            }

            // Evaluate all rules defined for this layer
            for (RuleDefinition rule : rules) {
                if (ruleEngine.evaluate(rule.condition(), agent, project)) {
                    ruleEngine.execute(rule.action(), agent, project);
                }
            }
            
            // Handle Hatching (Specific logic for spawning new agents)
            if (agent instanceof InertAgent ia && ia.getEggCount() > 0) {
                synchronized (newborns) {
                    for (int i = 0; i < ia.getEggCount(); i++) {
                        // Create a new LivingAgent at the tank's location
                        LivingAgent larva = new LivingAgent(ia.getX(), ia.getY());
                        larva.setStage(LifeCycleStage.LARVA);
                        newborns.add(larva);
                    }
                }
                ia.setEggCount(0); // Reset after spawning
            }
        });

        // Add newborns to the layer (if this is the Mosquito layer) 
        // Or transfer them to the appropriate layer in the project
        if (!newborns.isEmpty()) {
            this.addAgents(newborns);
        }

        // 4. Cleanup dead agents
        agents.removeIf(a -> a instanceof LivingAgent la && !la.isAlive());
    }

    public void addAgent(Agent agent) {
        this.agents.add(agent);
    }

    public void addAgents(List<Agent> newAgents) {
        this.agents.addAll(newAgents);
    }

    public List<Agent> getAgents() {
        return agents;
    }
}