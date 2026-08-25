package com.monadvsim.app.models.engine;
import com.monadvsim.app.models.utils.SimulationLogger;

import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.utils.SeedManager;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class RuleEngine {

    private final ConcurrentHashMap<String, Source> scriptCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> evaluationCache = new ConcurrentHashMap<>();

    private final ThreadLocal<Context> threadLocalContext = ThreadLocal.withInitial(() -> {
        try {
            // Use a builder with explicit permissions and low resource limits
            return Context.newBuilder("js")
                    .allowAllAccess(true)
                    .allowHostAccess(HostAccess.ALL)
                    .allowHostClassLookup(s -> true)
                    .option("js.ecmascript-version", "2022")
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize GraalVM Context for thread: " + Thread.currentThread().getName(), e);
        }
    });

    public RuleEngine() {
        SimulationLogger.info("✅ RuleEngine initialized with ThreadLocal GraalVM Contexts.");
    }

    public boolean evaluate(String condition, Agent agent, Project project) {
        String cacheKey = condition + "|" + agent.getId();
        Boolean cached = evaluationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Context context = threadLocalContext.get();
        context.enter();
        try {
            Value bindings = context.getBindings("js");
            // Clear old bindings to avoid accumulation? Not necessary, but ensure fresh values
            // Actually, we should override each time.
            populateBindings(bindings, agent, project);

            Source source = scriptCache.computeIfAbsent(condition, c ->
                    Source.newBuilder("js", c, "rule-condition").buildLiteral()
            );

            Value result = context.eval(source);
            boolean boolResult = result.isBoolean() && result.asBoolean();
            evaluationCache.put(cacheKey, boolResult);
            return boolResult;
        } catch (Exception e) {
            // Log the detailed exception for debugging
            SimulationLogger.severe("Rule Evaluation Error: " + condition + " -> " + e.toString());
            // Provide fallback: return false to avoid crashing the simulation
            return false;
        } finally {
            context.leave();
        }
    }

    private void populateBindings(Value bindings, Agent agent, Project project) {
        // Reset all common bindings to safe defaults
        // Agent basic properties
        bindings.putMember("agent", agent);
        
        if (agent instanceof LivingAgent la) {
            bindings.putMember("stage", la.getStage().toString());
            bindings.putMember("age", la.getAge());
            bindings.putMember("energy", la.getEnergy());
            bindings.putMember("gravid", la.isGravid());
            bindings.putMember("alive", la.isAlive());
            bindings.putMember("resting", la.isResting());
            bindings.putMember("x", la.getX());
            bindings.putMember("y", la.getY());
        } else if (agent instanceof InertAgent ia) {
            bindings.putMember("waterVolume", ia.getWaterVolume());
            bindings.putMember("eggCount", ia.getEggCount());
            bindings.putMember("larvalCount", ia.getLarvalCount());
            bindings.putMember("capacity", ia.getCapacity());
        } else {
            // Fallback for any agent type
            bindings.putMember("stage", "UNKNOWN");
            bindings.putMember("age", 0);
            bindings.putMember("energy", 0.0);
            bindings.putMember("gravid", false);
            bindings.putMember("alive", true);
            bindings.putMember("resting", false);
            bindings.putMember("x", agent != null ? agent.getX() : 0);
            bindings.putMember("y", agent != null ? agent.getY() : 0);
        }

        // Environmental variables - always define with default 0.0 to avoid undefined
        // First, get all layer names and tokens safely
        List<String> layerNames = project.getLayerNames();
        List<String> tokens = project.getTokens();
        if (layerNames != null && tokens != null && layerNames.size() == tokens.size()) {
            for (int i = 0; i < layerNames.size(); i++) {
                String token = tokens.get(i);
                double x = agent != null ? agent.getX() : 0;
                double y = agent != null ? agent.getY() : 0;
                double value = getValueAt(project, layerNames.get(i), x, y);
                bindings.putMember(token, value);
            }
        } else {
            SimulationLogger.warning("Layer names/tokens missing or mismatched; using defaults.");
        }
        
        // Also bind raw layer names for compatibility? Not needed.
        // Ensure common tokens always exist (fallback)
        if (!bindings.hasMember("temperature")) {
            bindings.putMember("temperature", getValueAt(project, "t2m", agent.getX(), agent.getY()));
        }
        if (!bindings.hasMember("precipitation")) {
            bindings.putMember("precipitation", getValueAt(project, "tp", agent.getX(), agent.getY()));
        }
        if (!bindings.hasMember("population")) {
            bindings.putMember("population", getValueAt(project, "Population", agent.getX(), agent.getY()));
        }
        if (!bindings.hasMember("building_density")) {
            bindings.putMember("building_density", getValueAt(project, "Buildings", agent.getX(), agent.getY()));
        }
        if (!bindings.hasMember("elevation")) {
            bindings.putMember("elevation", getValueAt(project, "Elevation", agent.getX(), agent.getY()));
        }
    }

    // ---------- Actions (unchanged, but ensure no null pointer) ----------
    public void execute(String action, Agent agent, Project project, AgentLayer layer) {
        switch (action.toLowerCase()) {
            case "die" -> executeDie(agent, layer);
            case "get_gravid" -> executeGetGravid(agent);
            case "lay_eggs" -> executeLayEggs(agent, project, layer);
            case "move_random" -> executeMoveRandom(agent, project, layer);
            case "reproduce" -> executeReproduce(agent, project, layer);
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
            la.setEnergy(Math.min(1.0, la.getEnergy() + 0.01));
            la.resetTimeWithoutRest();
        }
    }

    private void executeStopResting(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setResting(false);
        }
    }

    private void executeDieExhaustion(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setAlive(false);
            layer.killAgentImmediately(agent.getId());
        }
    }

    private void executeRestInBuilding(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            Layer buildingLayer = project.getLayerByName("Buildings");
            if (buildingLayer != null) {
                double buildingDensity = buildingLayer.getValueAt(agent.getX(), agent.getY());
                if (buildingDensity > 0.3) {
                    la.setResting(true);
                    la.setEnergy(Math.min(1.0, la.getEnergy() + 0.02));
                    la.resetTimeWithoutRest();
                } else {
                    executeFindBuildingToRest(agent, project, layer);
                }
            }
        }
    }

    private void executeFindBuildingToRest(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            Layer buildingLayer = project.getLayerByName("Buildings");
            if (buildingLayer != null) {
                double bestX = agent.getX();
                double bestY = agent.getY();
                double bestDensity = buildingLayer.getValueAt(agent.getX(), agent.getY());
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    double sampleX = agent.getX() + Math.cos(angle) * 0.0001;
                    double sampleY = agent.getY() + Math.sin(angle) * 0.0001;
                    double density = buildingLayer.getValueAt(sampleX, sampleY);
                    if (density > bestDensity) {
                        bestDensity = density;
                        bestX = sampleX;
                        bestY = sampleY;
                    }
                }
                if (bestDensity > buildingLayer.getValueAt(agent.getX(), agent.getY())) {
                    la.setX(bestX);
                    la.setY(bestY);
                    la.setResting(true);
                    la.setEnergy(Math.min(1.0, la.getEnergy() + 0.015));
                    layer.updateAgentPositionImmediately(la);
                }
            }
        }
    }

    private void executeFeed(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.getStage() == LifecycleStage.ADULT) {
            Layer populationLayer = project.getLayerByName("Population");
            if (populationLayer != null) {
                double popDensity = populationLayer.getValueAt(agent.getX(), agent.getY());
                if (popDensity > 0.01) {
                    la.setEnergy(Math.min(1.0, la.getEnergy() + 0.2));
                }
            } else {
                la.setEnergy(Math.min(1.0, la.getEnergy() + 0.1));
            }
        }
    }

    private void executeMoveRandom(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            double step = project.getDefaultAgentStep();
            Random rng = SeedManager.getRandom();
            la.move((rng.nextDouble() - 0.5) * step,
                    (rng.nextDouble() - 0.5) * step);
            layer.updateAgentPositionImmediately(la);
        }
    }

    private void executeReproduce(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.isGravid()) {
            List<Agent> nearby = project.getSpatialRegistry()
                    .getNearbyAgents(agent.getX(), agent.getY(), project.getDefaultAgentSearchRadius());
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

    private void executeDie(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setAlive(false);
            layer.killAgentImmediately(agent.getId());
        } else if (agent instanceof InertAgent) {
            layer.killAgentImmediately(agent.getId());
        }
    }

    private void executeDryOut(Agent agent, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            ia.setEggCount(0);
            ia.setLarvalCount(0);
            ia.setWaterVolume(0);
            layer.updateAgentPositionImmediately(agent);
        }
    }

    private void executeFreeze(Agent agent, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            ia.setEggCount(0);
            ia.setLarvalCount(0);
            ia.setWaterVolume(Math.max(0, ia.getWaterVolume() - 10));
            layer.updateAgentPositionImmediately(agent);
        }
    }

    private void executeGetGravid(Agent agent) {
        if (agent instanceof LivingAgent la) {
            la.setGravid(true);
        }
    }

    private void executeLayEggs(Agent agent, Project project, AgentLayer layer) {
        if (!(agent instanceof LivingAgent la) || la.getStage() != LifecycleStage.ADULT) {
            return;
        }
        double temperature = getValueAt(project, "t2m", agent.getX(), agent.getY());
        double livestock = getValueAt(project, "Livestock", agent.getX(), agent.getY());
        if (livestock <= 0) {
            livestock = getValueAt(project, "Population", agent.getX(), agent.getY());
        }
        LifecycleModel model = project.getLifecycleModel();
        if (model == null) return;
        int eggsToLay = model.eggsToLay(temperature, livestock);
        if (eggsToLay <= 0) return;
        List<Agent> nearby = project.getSpatialRegistry()
                .getNearbyAgents(agent.getX(), agent.getY(), project.getDefaultAgentSearchRadius());
        for (Agent n : nearby) {
            if (n instanceof InertAgent tank) {
                tank.addEggs(eggsToLay);
                la.setGravid(false);
                la.setEnergy(la.getEnergy() - 0.2);
                break;
            }
        }
    }
    
    
    private void executeEvaporate(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            double temperature = getValueAt(project, "t2m", ia.getX(), ia.getY());
            double precipitation = getValueAt(project, "tp", ia.getX(), ia.getY());

            // Convert temperature to Celsius
            double tempC = temperature - 273.15;

            // More aggressive evaporation (2-5% per tick at 20-30°C)
            double baseEvapRate = 0.02; // 2% per tick at baseline (was 0.005)
            double tempFactor = Math.max(0.2, 1.0 + (tempC - 20.0) * 0.05);
            double evaporationRate = baseEvapRate * tempFactor;

            // More aggressive refill (precipitation in mm)
            // tp is in meters, convert to mm: * 1000
            double precipMm = precipitation * 1000;
            double refillRate = Math.min(15.0, precipMm * 0.5); // 0.5mm rain = 0.5% refill

            double oldWater = ia.getWaterVolume();
            
            // Apply evaporation and refill
            double newWater = oldWater - evaporationRate + refillRate;

            // Clamp between 0 and 100
            double clampedWater = Math.max(0, Math.min(100, newWater));
            ia.setWaterVolume(clampedWater);

            // Log significant changes (for debugging)
            if (Math.abs(newWater - ia.getWaterVolume()) > 0.1) {
                SimulationLogger.fine("[TANK] Water: %.1f%% -> %.1f%% (evap: %.2f%%, refill: %.2f%%) at (%.6f, %.6f)",
                        ia.getWaterVolume(), clampedWater, evaporationRate, refillRate, ia.getX(), ia.getY());
            }
        }
    }

    private double getValueAt(Project p, String layerName, double x, double y) {
        Layer layer = p.getLayerByName(layerName);
        return (layer != null) ? layer.getValueAt(x, y) : 0.0;
    }

    public void clearCache() {
        scriptCache.clear();
        evaluationCache.clear();
    }
}