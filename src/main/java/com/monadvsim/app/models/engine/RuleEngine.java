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


/**
 * JavaScript Rule Evaluator
 *
 * Evaluates agent behavior rules using GraalVM JavaScript engine
 *
 * Supports dynamic rule evaluation with environmental context access
 *
 * Implements thread-local contexts for concurrent rule evaluation
 *
 * Executes predefined actions (die, lay_eggs, hatch, move_random, etc.)
 *
 * Includes caching for rule conditions and results
 * 
 * 
 * @author void
 */


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

//    private void populateBindings(Value bindings, Agent agent, Project project) {
//        if (project.getTokens().size() != project.getLayerNames().size()) {
//            throw new IllegalArgumentException("Token/Layer mismatch");
//        }
//
//        bindings.putMember("agent", agent);
//
//        for (int i = 0; i < project.getLayerNames().size(); i++) {
//            String token = project.getTokens().get(i);
//            double value = getValueAt(project, 
//                    project.getLayerNames().get(i), agent.getX(), agent.getY());
//            bindings.putMember(token, value);
//        }
//    }
    
    private void populateBindings(Value bindings, Agent agent, Project project) {
        // Bind agent as 'agent' object
        bindings.putMember("agent", agent);

        // Also bind commonly used agent properties for easier access
        if (agent instanceof LivingAgent la) {
            bindings.putMember("stage", la.getStage().toString());
            bindings.putMember("age", la.getAge());
            bindings.putMember("energy", la.getEnergy());
            bindings.putMember("gravid", la.isGravid());
            bindings.putMember("alive", la.isAlive());
        } else if (agent instanceof InertAgent ia) {
            bindings.putMember("waterVolume", ia.getWaterVolume());
            bindings.putMember("eggCount", ia.getEggCount());
            bindings.putMember("larvalCount", ia.getLarvalCount());
            bindings.putMember("capacity", ia.getCapacity());
        }

        // Bind layer values
        for (int i = 0; i < project.getLayerNames().size(); i++) {
            String token = project.getTokens().get(i);
            double value = getValueAt(project, project.getLayerNames().get(i), 
                                     agent.getX(), agent.getY());
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
            case "pupate" -> executePupate(agent, layer);
            case "emerge" -> executeEmerge(agent, layer);
            case "feed" -> executeFeed(agent, project, layer);
            case "dry_out" -> executeDryOut(agent, layer);
            case "freeze" -> executeFreeze(agent, layer);
            case "evaporate" -> executeEvaporate(agent, project, layer);
            case "rest" -> executeRest(agent, layer);
            case "stop_resting" -> executeStopResting(agent, layer);
            case "die_exhaustion" -> executeDieExhaustion(agent, layer);
            case "rest_in_building" -> executeRestInBuilding(agent, project, layer);
        }
    }
    
    
    private void executeRest(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setResting(true);
            // While resting, energy recovers slowly
            la.setEnergy(Math.min(1.0, la.getEnergy() + 0.01));
            la.resetTimeWithoutRest();

            System.out.println("Mosquito " + agent.getId() + " is resting. Energy: " + la.getEnergy());
        }
    }

    private void executeStopResting(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setResting(false);
            System.out.println("Mosquito " + agent.getId() + " stopped resting");
        }
    }

    private void executeDieExhaustion(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            System.out.println("Mosquito " + agent.getId() + " died from exhaustion after " + 
                              la.getTimeWithoutRest() + " ticks without rest");
            la.setAlive(false);
            layer.killAgentImmediately(agent.getId());
        }
    }

    private void executeRestInBuilding(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            // Check building density at current location
            Layer buildingLayer = project.getLayerByName("Buildings");
            if (buildingLayer != null) {
                double buildingDensity = buildingLayer.getValueAt(agent.getX(), agent.getY());

                if (buildingDensity > 0.3) { // Good building density for resting
                    la.setResting(true);
                    // Better energy recovery when resting in buildings
                    la.setEnergy(Math.min(1.0, la.getEnergy() + 0.02));
                    la.resetTimeWithoutRest();

                    System.out.println("Mosquito " + agent.getId() + " resting in building. " +
                                     "Building density: " + buildingDensity + ", Energy: " + la.getEnergy());
                } else {
                    // Try to move toward buildings
                    executeFindBuildingToRest(agent, project, layer);
                }
            }
        }
    }

    private void executeFindBuildingToRest(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            // Use spatial registry to find nearby building-rich areas
            List<Agent> nearby = project.getSpatialRegistry()
                .getNearbyAgents(agent.getX(), agent.getY(), project.getDefaultAgentSearchRadius());

            // Look for areas with high building density by sampling nearby points
            Layer buildingLayer = project.getLayerByName("Buildings");
            if (buildingLayer != null) {
                // Sample 8 directions around current position
                double bestX = agent.getX();
                double bestY = agent.getY();
                double bestDensity = buildingLayer.getValueAt(agent.getX(), agent.getY());

                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    double sampleX = agent.getX() + Math.cos(angle) * 0.0001; // ~11m
                    double sampleY = agent.getY() + Math.sin(angle) * 0.0001;
                    double density = buildingLayer.getValueAt(sampleX, sampleY);

                    if (density > bestDensity) {
                        bestDensity = density;
                        bestX = sampleX;
                        bestY = sampleY;
                    }
                }

                // Move toward better resting spot if found
                if (bestDensity > buildingLayer.getValueAt(agent.getX(), agent.getY())) {
                    la.setX(bestX);
                    la.setY(bestY);
                    la.setResting(true);
                    la.setEnergy(Math.min(1.0, la.getEnergy() + 0.015));

                    System.out.println("Mosquito " + agent.getId() + " moved to better resting spot. " +
                                     "Building density: " + bestDensity);
                    layer.updateAgentPositionImmediately(la);
                }
            }
        }
    }
    
    
    private void executePupate(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.getStage() == LifecycleStage.LARVA) {
            la.setStage(LifecycleStage.PUPA);
            la.setEnergy(0.6); // Reset energy for pupa stage
            System.out.println("Larva " + agent.getId() + " pupated");
        }
    }

    private void executeEmerge(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.getStage() == LifecycleStage.PUPA) {
            la.setStage(LifecycleStage.ADULT);
            la.setEnergy(0.9); // New adult has energy
            System.out.println("Pupa " + agent.getId() + " emerged as adult");
        }
    }

    private void executeFeed(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.getStage() == LifecycleStage.ADULT) {
            // Look for nearby hosts (humans/population) to feed on
            List<Agent> nearby = project.getSpatialRegistry()
                .getNearbyAgents(agent.getX(), agent.getY(), 
                    project.getDefaultAgentSearchRadius());

            // Check if in populated area
            Layer populationLayer = project.getLayerByName("Population");
            if (populationLayer != null) {
                double popDensity = populationLayer.getValueAt(agent.getX(), agent.getY());
                if (popDensity > 1.0) { // Arbitrary threshold
                    la.setEnergy(Math.min(1.0, la.getEnergy() + 0.3));
                    System.out.println("Adult " + agent.getId() + " fed, energy: " + la.getEnergy());
                }
            }
        }
    }


//    private void executeDie(Agent agent, AgentLayer layer) {
//        if (agent instanceof LivingAgent la) {
//            la.setAlive(false);
//            layer.killAgentImmediately(agent.getId());
//        }
//    }
    private void executeDie(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setAlive(false);
            layer.killAgentImmediately(agent.getId());
        } else if (agent instanceof InertAgent) {
            // For InertAgent, also kill it (remove from simulation)
            layer.killAgentImmediately(agent.getId());
        }
    }
    
    private void executeDryOut(Agent agent, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            // When tank dries out, kill all eggs/larvae
            int eggsKilled = ia.getEggCount();
            int larvaeKilled = ia.getLarvalCount();

            ia.setEggCount(0);
            ia.setLarvalCount(0);
            ia.setWaterVolume(0);

            System.out.printf("Tank %s dried out! Killed %d eggs and %d larvae%n",
                agent.getId(), eggsKilled, larvaeKilled);

            // Optional: Mark tank for removal if completely dry for too long
            // For now, just update position
            layer.updateAgentPositionImmediately(agent);
        }
    }
    
    private void executeFreeze(Agent agent, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            // Kill all eggs and larvae when tank freezes
            int eggsKilled = ia.getEggCount();
            int larvaeKilled = ia.getLarvalCount();

            ia.setEggCount(0);
            ia.setLarvalCount(0);

            // Reduce water volume (ice expansion can damage tank)
            ia.setWaterVolume(Math.max(0, ia.getWaterVolume() - 10));

            System.out.printf("Tank %s frozen! Killed %d eggs and %d larvae. Water reduced to %.1f%%%n",
                agent.getId(), eggsKilled, larvaeKilled, ia.getWaterVolume());

            // Update position in spatial registry (if needed)
            layer.updateAgentPositionImmediately(agent);
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
    
    
    private void executeEvaporate(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            double temperature = project.getLayerByName("t2m") != null ? 
                project.getLayerByName("t2m").getValueAt(ia.getX(), ia.getY()) : 295.15;
            double precipitation = project.getLayerByName("tp") != null ? 
                project.getLayerByName("tp").getValueAt(ia.getX(), ia.getY()) : 0.0;

            // Evaporation rate increases with temperature, decreases with rain
            double evaporationRate = Math.max(0.01, (temperature - 293.15) / 20.0); // 0-1%
            evaporationRate *= (1.0 - Math.min(1.0, precipitation * 1000)); // Reduce with rain

            double currentWater = ia.getWaterVolume();
            double newWater = Math.max(0, currentWater - evaporationRate);
            ia.setWaterVolume(newWater);

            // Optional: Refill from precipitation
            if (precipitation > 0.001) { // More than 1mm of rain
                double refill = precipitation * 100; // Scale factor
                ia.setWaterVolume(Math.min(100, ia.getWaterVolume() + refill));
            }

            layer.updateAgentPositionImmediately(agent);
        }
    }


    private void executeHatch(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof InertAgent ia && ia.getEggCount() > 0) {
            double temperature = project.getLayerByName("t2m") != null ? 
                project.getLayerByName("t2m").getValueAt(ia.getX(), ia.getY()) : 295.15;

            // Temperature-based hatching probability
            double baseHatchRate = project.getDefaultHatchingProbability();

            // Optimal temperature: 25-30°C (298-303K)
            double tempFactor;
            if (temperature >= 298.15 && temperature <= 303.15) {
                tempFactor = 1.0; // Optimal
            } else if (temperature >= 293.15 && temperature <= 308.15) {
                tempFactor = 0.5; // Suboptimal
            } else {
                tempFactor = 0.1; // Poor conditions
            }

            // Water level factor
            double waterFactor = Math.min(1.0, ia.getWaterVolume() / 100.0);

            double hatchRate = baseHatchRate * tempFactor * waterFactor;
            int eggsToHatch = (int) (ia.getEggCount() * hatchRate);
            eggsToHatch = Math.max(1, Math.min(eggsToHatch, ia.getEggCount()));

            int hatched = ia.takeEggs(eggsToHatch);

            System.out.printf("Hatching %d/%d eggs at %.1f%% rate (Temp: %.1f°C, Water: %.1f%%)%n",
                hatched, ia.getEggCount() + hatched, hatchRate * 100,
                temperature - 273.15, ia.getWaterVolume());

            // Create larvae
            if (hatched > 0) {
                AgentLayer mosquitoLayer = project.getAgentLayers().stream()
                    .filter(l -> l.getName().equalsIgnoreCase("Mosquitoes"))
                    .findFirst().orElse(null);

                if (mosquitoLayer != null) {
                    for (int i = 0; i < hatched; i++) {
                        double x = ia.getX() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.0001;
                        double y = ia.getY() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.0001;

                        LivingAgent larva = (LivingAgent) mosquitoLayer.createAgentImmediately(LivingAgent.class, x, y);
                        larva.setStage(LifecycleStage.LARVA);
                        larva.setAge(0);
                        larva.setEnergy(0.8);

                        // Temperature affects development time
                        int daysToPupa;
                        if (temperature >= 298.15 && temperature <= 303.15) {
                            daysToPupa = 5; // Fast development in optimal temp
                        } else if (temperature >= 293.15 && temperature <= 308.15) {
                            daysToPupa = 7; // Slower
                        } else {
                            daysToPupa = 10; // Very slow
                        }
                        larva.setDaysToPupa(daysToPupa);
                    }
                }
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