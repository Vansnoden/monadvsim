package com.monadvsim.app.models.entities;


import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AgentLayer extends Layer {
    private List<Agent> agents;

    public AgentLayer(String name) {
        super(name);
        this.agents = new ArrayList<>();
    }

    public List<Agent> getAgents() {
        return agents;
    }

    public void addAgent(Agent agent) {
        this.agents.add(agent);
    }

    /**
     * Headless update loop.
     * Filter out dead agents and then run logic for living/inert ones.
     */
    @Override
    public void update(long tick, double deltaT) {
        // 1. Remove agents that died in the last tick
        agents.removeIf(a -> !a.isAlive());

        // 2. Logic is typically handled by the SimulationEngine's 
        // parallel loop for performance, but we keep the structure here.
    }

    /**
     * Helper for synthetic surveillance: 
     * Get counts of agents in a specific lifecycle stage.
     */
    public long getCountByStage(String stage) {
        return agents.stream()
            .filter(a -> a.getLifecycleStage().equalsIgnoreCase(stage))
            .count();
    }
    
    // For MVS: clear agents if resetting simulation
    public void clear() {
        agents.clear();
    }
}