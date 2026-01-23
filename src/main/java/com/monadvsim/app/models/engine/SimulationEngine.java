package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.*;
import java.util.*;
import java.util.stream.Collectors;

public class SimulationEngine implements Runnable {
    private final Project project;
    private final TimeManager timeManager;
    private final SpatialRegistry spatialRegistry;
    private boolean running = false;

    public SimulationEngine(Project project, TimeManager timeManager, SpatialRegistry spatialRegistry) {
        this.project = project;
        this.timeManager = timeManager;
        this.spatialRegistry = spatialRegistry;
        this.project.setSpatialRegistry(spatialRegistry);
    }

    @Override
    public void run() {
        running = true;
        System.out.println("🚀-> Simulation Engine Started...");

        while (running) {
            // Advance Time
            if (!timeManager.tick()) {
                running = false;
                break;
            }

            // Synchronize Environmental Context
            // Loads the correct Raster frame (Temp/Rain) for the current tick
            project.updateEnvironment(timeManager.getCurrentFrameIndex());
            
            // Update Spatial Registry (QuadTree)
            // Agents must be indexed BEFORE Layer updates so they can find each other
            List<Agent> allAgents = project.getAgentLayers().stream()
                .flatMap(l -> l.getAgents().stream())
                .collect(Collectors.toList());
            spatialRegistry.update(allAgents);

            // Process Agent Layers in ORDER
            // This triggers the RuleEngine evaluation for every agent in every layer
            for (AgentLayer layer : project.getAgentLayers()) {
                layer.update(project);
            }

            // Console Reporting
            if (timeManager.getTickCount() % 10 == 0) {
                reportProgress();
            }
        }
    }

    private void reportProgress() {
        AgentLayer mosquitoLayer = project.getAgentLayers().stream()
            .filter(l -> l.getName().equalsIgnoreCase("Mosquitoes"))
            .findFirst().orElse(null);

        long adults = (mosquitoLayer != null) ? mosquitoLayer.getAgents().size() : 0;
        
        // Optional: Sum up all larvae across all tanks (inert agents) for habitat monitoring
        long totalLarvae = project.getAgentLayers().stream()
            .flatMap(l -> l.getAgents().stream())
            .filter(a -> a instanceof InertAgent)
            .mapToLong(a -> ((InertAgent) a).getLarvalCount())
            .sum();

        System.out.println(String.format("Tick: %d | Adults: %d | Larvae: %d", 
            timeManager.getTickCount(), adults, totalLarvae));
    }

    public void stop() {
        this.running = false;
    }
}