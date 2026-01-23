package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.*;
import java.util.List;


public class SimulationEngine implements Runnable {
    private final Project project;
    private final TimeManager timeManager;
    private final SpatialRegistry spatialRegistry;
    
    private boolean running = false;
    private long tickLimit = Long.MAX_VALUE;

    public SimulationEngine(Project project, TimeManager timeManager, SpatialRegistry spatialRegistry) {
        this.project = project;
        this.timeManager = timeManager;
        this.spatialRegistry = spatialRegistry;
    }

    @Override
    public void run() {
        running = true;
        System.out.println("Starting Headless Engine: " + project.getName());

        while (running && timeManager.getTickCount() < tickLimit) {
            // 1. Advance Time
            if (!timeManager.tick()) {
                running = false;
                break;
            }

            // 2. Synchronize Environment (Update Raster frames)
            project.updateEnvironment(timeManager.getCurrentFrameIndex());

            // 3. Synchronize Spatial Registry (Rebuild QuadTree)
            // We only need to re-index the LivingAgents (mosquitoes)
            List<AgentLayer> agentLayers = project.getAgentLayers();
            spatialRegistry.update(agentLayers.stream()
                .flatMap(layer -> layer.getAgents().stream())
                .toList());

            // 4. Update Agents in Parallel (The "Massive" part)
            for (AgentLayer layer : agentLayers) {
                // parallelStream() utilizes all CPU cores for the biology math
                layer.getAgents().parallelStream().forEach(agent -> {
                    agent.update(project, timeManager);
                });
                
                // Cleanup dead agents after the parallel loop
                layer.update(timeManager.getTickCount(), 1.0);
            }

            // 5. Headless Feedback
            if (timeManager.getTickCount() % 100 == 0) {
                System.out.println("Tick: " + timeManager.getTickCount() + 
                                   " | Date: " + timeManager.getTimestampString() +
                                   " | Agents: " + getTotalAgentCount());
            }
        }
        System.out.println("Simulation Finished.");
    }

    private int getTotalAgentCount() {
        return project.getAgentLayers().stream()
                .mapToInt(l -> l.getAgents().size()).sum();
    }

    public void stop() { this.running = false; }
    public void setTickLimit(long limit) { this.tickLimit = limit; }
}
