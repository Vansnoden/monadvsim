package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.*;
import java.util.*;
import java.util.stream.Collectors;

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
        this.project.setSpatialRegistry(spatialRegistry);
    }

    public void setTickLimit(long limit) { this.tickLimit = limit; }

    @Override
    public void run() {
        running = true;
        System.out.println("Starting Headless Engine: " + project.getName());

        while (running && timeManager.getTickCount() < tickLimit) {
            if (!timeManager.tick()) break;

            project.updateEnvironment(timeManager.getCurrentFrameIndex());
            
            // Rebuild spatial index
            List<Agent> allAgents = project.getAgentLayers().stream()
                .flatMap(l -> l.getAgents().stream()).collect(Collectors.toList());
            spatialRegistry.update(allAgents);

            // Find the dedicated layer for Adults
            AgentLayer mosquitoLayer = project.getAgentLayers().stream()
                .filter(l -> l.getName().equals("Mosquitoes")).findFirst().orElse(null);

            for (AgentLayer layer : project.getAgentLayers()) {
                List<Agent> newborns = Collections.synchronizedList(new ArrayList<>());

                layer.getAgents().parallelStream().forEach(agent -> {
                    if (agent instanceof InertAgent tank) {
                        int hatch = tank.calculateHatching(project);
                        for (int i = 0; i < hatch; i++) {
                            newborns.add(new LivingAgent(tank.getX(), tank.getY()));
                        }
                    } else {
                        agent.update(project, timeManager);
                    }
                });

                // Add newborns to the Adult layer (mosquitoLayer)
                if (mosquitoLayer != null) {
                    mosquitoLayer.getAgents().addAll(newborns);
                }
                
                // Remove dead agents
                layer.update(timeManager.getTickCount(), 1.0);
            }

            if (timeManager.getTickCount() % 100 == 0) {
                long totalAdults = (mosquitoLayer != null) ? mosquitoLayer.getAgents().size() : 0;
                System.out.println("Tick: " + timeManager.getTickCount() + 
                                   " | Time: " + timeManager.getCurrentDateTime() + 
                                   " | Adults: " + totalAdults);
            }
        }
        System.out.println("✅ Engine Loop Finished.");
    }
}