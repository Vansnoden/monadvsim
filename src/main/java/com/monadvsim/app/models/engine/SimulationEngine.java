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
        while (running) {
            if (!timeManager.tick()) break;

            project.updateEnvironment(timeManager.getCurrentFrameIndex());
            
            List<Agent> allAgents = project.getAgentLayers().stream()
                .flatMap(l -> l.getAgents().stream()).collect(Collectors.toList());
            spatialRegistry.update(allAgents);

            AgentLayer mosquitoLayer = project.getAgentLayers().stream()
                .filter(l -> l.getName().equals("Mosquitoes")).findFirst().orElse(null);

            for (AgentLayer layer : project.getAgentLayers()) {
                // FIX: Use a Synchronized List for newborns
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

                if (mosquitoLayer != null && !newborns.isEmpty()) {
                    mosquitoLayer.getAgents().addAll(newborns);
                }
                layer.update(timeManager.getTickCount(), 1.0);
            }

            if (timeManager.getTickCount() % 10 == 0) {
                long adults = (mosquitoLayer != null) ? mosquitoLayer.getAgents().size() : 0;
                System.out.println("Tick: " + timeManager.getTickCount() + " | Adults: " + adults);
            }
        }
    }
}