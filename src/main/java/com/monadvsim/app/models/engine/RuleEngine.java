package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RuleEngine {

    private final ConcurrentHashMap<String, Source> scriptCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> evaluationCache = new ConcurrentHashMap<>();

    private final ThreadLocal<Context> threadLocalContext = ThreadLocal.withInitial(() -> {
        try {
            return Context.newBuilder("js")
                    .allowHostAccess(HostAccess.ALL)
                    .allowHostClassLookup(s -> true)
                    .option("js.ecmascript-version", "2022")
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize GraalVM "
                    + "Context for thread: " + Thread.currentThread().getName(), e);
        }
    });

    public RuleEngine() {
        System.out.println("✅ RuleEngine initialized "
                + "with ThreadLocal GraalVM Contexts.");
    }

    public boolean evaluate(String condition, Agent agent, Project project) {
        try {
            String cacheKey = condition + "|" + agent.getId();
            Boolean cached = evaluationCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            Context context = threadLocalContext.get();
            Value bindings = context.getBindings("js");
            
            populateBindings(bindings, agent, project);

            Source source = scriptCache.computeIfAbsent(condition, c -> 
                Source.newBuilder("js", c, "rule-condition").buildLiteral()
            );

            Value result = context.eval(source);
            evaluationCache.put(cacheKey, result.isBoolean() && result.asBoolean());
            return result.isBoolean() && result.asBoolean();

        } catch (Exception e) {
            System.err.println("Rule Evaluation Error: " + condition + " "
                    + "-> " + e.getMessage());
            return false;
        }
    }

    private void populateBindings(Value bindings, Agent agent, Project project) {
        if (project.getTokens().size() != project.getLayerNames().size()) {
            throw new IllegalArgumentException("Token/Layer mismatch");
        }

        bindings.putMember("agent", agent);

        for (int i = 0; i < project.getLayerNames().size(); i++) {
            String token = project.getTokens().get(i);
            double value = getValueAt(project, 
                    project.getLayerNames().get(i), agent.getX(), agent.getY());
            bindings.putMember(token, value);
        }
    }

    public void execute(String action, Agent agent, 
            Project project, AgentLayer layer) {
        switch (action.toLowerCase()) {
            case "die" -> executeDie(agent, layer);
            case "get_gravid" -> executeGetGravid(agent);
            case "lay_eggs" -> executeLayEggs(agent, project, layer);
            case "hatch" -> executeHatch(agent, project, layer);
            case "move_random" -> executeMoveRandom(agent, project, layer);
            case "reproduce" -> executeReproduce(agent, project, layer);
        }
    }

    public void execute(String action, Agent agent, Project project) {
        switch (action.toLowerCase()) {
            case "die" -> { 
                if (agent instanceof LivingAgent la) {
                    la.setAlive(false);
                } 
            }
            case "get_gravid" -> {
                if (agent instanceof LivingAgent la){
                    la.setGravid(true);
                } 
            }
            case "lay_eggs" -> executeLayEggs(agent, project);
            case "move_random" -> {
                if (agent instanceof LivingAgent la) {
                    double step = project.getDefaultAgentStep();
                    la.move((ThreadLocalRandom.current().nextDouble() - 0.5) * step,
                            (ThreadLocalRandom.current().nextDouble() - 0.5) * step);
                }
            }
        }
    }

    private void executeDie(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setAlive(false);
            layer.killAgentImmediately(agent.getId());
        }
    }

    private void executeGetGravid(Agent agent) {
        if (agent instanceof LivingAgent la) la.setGravid(true);
    }

    private void executeLayEggs(Agent agent, Project project, AgentLayer layer) {
        List<Agent> nearby = project.getSpatialRegistry()
                .getNearbyAgents(agent.getX(), agent.getY(), 
                        project.getDefaultAgentSearchRadius());

        // Find water tanks to lay eggs in
        for (Agent n : nearby) {
            if (n instanceof InertAgent ia && ia.getCapacity() > 0) {
                // Lay eggs based on birth rate
                int eggsLaid = project.getDefaultBirthRate();
                ia.addEggs(eggsLaid);

                if (agent instanceof LivingAgent la) {
                    la.setGravid(false);
                    la.setEnergy(la.getEnergy() - 0.2); // Energy cost for laying eggs
                }

                // Update the tank's position in spatial registry
                layer.updateAgentPositionImmediately(n);

                System.out.println("Eggs laid: " + eggsLaid + " at tank " + ia.getId());
                break; // Lay eggs in one tank only
            }
        }
    }

    private void executeLayEggs(Agent agent, Project project) {
        List<Agent> nearby = project.getSpatialRegistry()
                .getNearbyAgents(agent.getX(), agent.getY(), 
                        project.getDefaultAgentSearchRadius());
        for (Agent n : nearby) {
            if (n instanceof InertAgent ia) {
                ia.addEggs(20);
                if (agent instanceof LivingAgent la) la.setGravid(false);
                break;
            }
        }
    }

    private void executeHatch(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof InertAgent ia && ia.getEggCount() > 0) {
            // Calculate hatching based on temperature
            double temperature = project.getLayerByName("t2m") != null ? 
                project.getLayerByName("t2m").getValueAt(ia.getX(), ia.getY()) : 295.15;

            // Higher temperature = higher hatching rate (optimal: 25-30°C = 298-303K)
            double tempFactor = Math.min(1.0, Math.max(0.0, 
                (temperature - 293.15) / 10.0)); // 20-30°C range

            double hatchRate = project.getDefaultHatchingProbability() * tempFactor;
            int eggsToHatch = (int) (ia.getEggCount() * hatchRate);
            eggsToHatch = Math.max(1, Math.min(eggsToHatch, ia.getEggCount()));

            int hatched = ia.takeEggs(eggsToHatch);

            System.out.println("Hatching " + hatched + " eggs at " + 
                project.getDefaultHatchingProbability() + " rate, temp: " + temperature);

            for (int i = 0; i < hatched; i++) {
                double x = ia.getX() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.0001;
                double y = ia.getY() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.0001;

                LivingAgent larva = (LivingAgent) layer.createAgentImmediately(LivingAgent.class, x, y);
                larva.setStage(LifecycleStage.LARVA);
                larva.setAge(0);
                larva.setEnergy(0.8);
            }
            layer.updateAgentPositionImmediately(ia);
        }
    }

    private void executeMoveRandom(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            double step = project.getDefaultAgentStep();
            la.move((ThreadLocalRandom.current().nextDouble() - 0.5) * step,
                    (ThreadLocalRandom.current().nextDouble() - 0.5) * step);
            layer.updateAgentPositionImmediately(la);
        }
    }

    private void executeReproduce(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.isGravid()) {
            List<Agent> nearby = project.getSpatialRegistry()
                    .getNearbyAgents(agent.getX(), agent.getY(), 
                            project.getDefaultAgentSearchRadius());
            for (Agent n : nearby) {
                if (n instanceof LivingAgent mate && mate != agent && !mate.isGravid() && mate.isAlive()) {
                    double x = (agent.getX() + mate.getX()) / 2;
                    double y = (agent.getY() + mate.getY()) / 2;
                    LivingAgent offspring = (LivingAgent) layer.createAgentImmediately(LivingAgent.class, x, y);
                    offspring.setStage(LifecycleStage.ADULT);
                    offspring.setAge(0);
                    la.setGravid(false);
                    break;
                }
            }
        }
    }

    private double getValueAt(Project p, String layerName, double x, double y) {
        Layer layer = p.getLayerByName(layerName);
        return (layer != null) ? layer.getValueAt(x, y) : 0.0;
    }

    public void clearCache() { scriptCache.clear(); }
}