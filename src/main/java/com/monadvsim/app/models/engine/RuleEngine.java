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

public class RuleEngine {

    private final ConcurrentHashMap<String, Source> scriptCache = new ConcurrentHashMap<>();
    private volatile Map<String, Boolean> evaluationCache = new ConcurrentHashMap<>();
    
    // ---- Targeting behavior for gravid females -------------------------------
    private static final int    TARGET_ACQUIRE_TIMEOUT_TICKS = 600;   // ~6 days
    private static final double TARGET_ACQUIRE_RADIUS_MULT   = 20.0;  // was 5.0
    private static final double SEEK_STEP_MULT               = 5.0;
    private static final double MIN_TANK_WATER               = 1.0;
    private static final double LAY_RADIUS_MULT              = 20.0;  // was 3.0
    
    
    /** Cache for getTotalLarvae, invalidated once per tick by AgentLayer. */
    private volatile int cachedTotalLarvae = 0;
    private volatile long cacheTick = -1L;
    
    
    /** Called by AgentLayer at the top of each tick, once. */
    public void updateTickCache(Project project, long tick) {
        if (tick == cacheTick) return;
        int total = 0;
        for (AgentLayer layer : project.getAgentLayers()) {
            for (Agent a : layer.getAgents()) {
                if (a instanceof InertAgent ia) {
                    total += ia.getLarvalCount();
                }
            }
        }
        cachedTotalLarvae = total;
        cacheTick = tick;
    }

    
    private final ThreadLocal<Context> threadLocalContext = ThreadLocal.withInitial(() -> {
        try {
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
        String stateHash = getAgentStateHash(agent);
        String cacheKey = condition + "|" + stateHash;

        Boolean cached = evaluationCache.get(cacheKey);
        if (cached != null) return cached;

        Context context = threadLocalContext.get();
        context.enter();
        try {
            Value bindings = context.getBindings("js");
            populateBindings(bindings, agent, project);

            Source source = scriptCache.computeIfAbsent(condition, c ->
                    Source.newBuilder("js", c, "rule-condition").buildLiteral()
            );

            Value result = context.eval(source);
            boolean boolResult = result.isBoolean() && result.asBoolean();
            evaluationCache.put(cacheKey, boolResult);
            return boolResult;
        } catch (Exception e) {
            SimulationLogger.severe("Rule Evaluation Error: " + condition + " -> " + e.toString());
            return false;
        } finally {
            context.leave();
        }
    }

    private String getAgentStateHash(Agent agent) {
        if (agent instanceof LivingAgent la) {
            StringBuilder sb = new StringBuilder();
            sb.append(la.getStage()).append("_");
            sb.append((int)(la.getEnergy() * 10)).append("_");
            sb.append(la.isGravid() ? "1" : "0").append("_");
            sb.append(la.isResting() ? "1" : "0");
            int ageBucket = Math.min(la.getAge() / 10, 100);
            sb.append("_").append(ageBucket);
            return sb.toString();
        } else if (agent instanceof InertAgent ia) {
            int waterBucket = (int)(ia.getWaterVolume() / 10);
            return "inert_" + waterBucket;
        }
        return "default";
    }
    
    private void populateBindings(Value bindings, Agent agent, Project project) {
        bindings.putMember("agent", agent);
        
        if (agent instanceof LivingAgent la) {
            bindings.putMember("stage", la.getStage().toString());
            bindings.putMember("age", la.getAge());
            bindings.putMember("stageAgeTick", la.getStageAgeTicks());
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
            bindings.putMember("stage", "UNKNOWN");
            bindings.putMember("age", 0);
            bindings.putMember("energy", 0.0);
            bindings.putMember("gravid", false);
            bindings.putMember("alive", true);
            bindings.putMember("resting", false);
            bindings.putMember("x", agent != null ? agent.getX() : 0);
            bindings.putMember("y", agent != null ? agent.getY() : 0);
        }

        List<String> layerNames = project.getLayerNames();
        List<String> tokens = project.getTokens();
        if (layerNames != null && tokens != null && layerNames.size() == tokens.size()) {
            double ax = (agent != null) ? agent.getX() : 0;
            double ay = (agent != null) ? agent.getY() : 0;
            for (int i = 0; i < layerNames.size(); i++) {
                bindings.putMember(tokens.get(i), getValueAt(project, layerNames.get(i), ax, ay));
            }
        }

        if (!bindings.hasMember("temperature")) {
            bindings.putMember("temperature", getValueAt(project, "t2m", agent.getX(), agent.getY()));
        }
        if (!bindings.hasMember("precipitation")) {
            bindings.putMember("precipitation", getValueAt(project, "tp", agent.getX(), agent.getY()));
        }
        bindings.putMember("getTotalLarvae", cachedTotalLarvae);
    }

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
            case "pupate" -> executePupate(agent, project, layer);
            case "emerge" -> executeEmerge(agent, project, layer);
            case "thin_larvae" -> executeThinLarvae(agent, layer);
            case "seek_tank" -> executeSeekTank(agent, project, layer);
        }
    }
    
    
    private void executeSeekTank(Agent agent, Project project, AgentLayer layer) {
        
        if (!(agent instanceof LivingAgent la) || la.getStage() != LifecycleStage.ADULT) return;

        if (!la.isGravid()) {
            la.clearTargetTank();
            return;
        }
        
        SimulationLogger.fine("[SEEK] %s at (%.5f,%.5f) target=%s",
            la.getId().substring(0, 8), la.getX(), la.getY(),
            la.getTargetTankId() != null ? la.getTargetTankId().substring(0, 8) : "null");

        SpatialRegistry registry = project.getSpatialRegistry();
        double step         = project.getDefaultAgentStep();
        double acquireRadius = project.getDefaultAgentSearchRadius() * TARGET_ACQUIRE_RADIUS_MULT;

        // 1. Resolve the current target, if any; drop it if it is now unusable.
        InertAgent target = null;
        if (la.getTargetTankId() != null) {
            Agent a = registry.getAgentById(la.getTargetTankId());
            if (a instanceof InertAgent t && t.getWaterVolume() > MIN_TANK_WATER) {
                target = t;
            } else {
                la.clearTargetTank();
            }
        }

        // 2. Acquire a new target if needed.
        if (target == null) {
            target = findNearestUsableTank(registry, la, acquireRadius);
            if (target == null) {
                // No tank in range: perform a short random walk so she keeps searching.
                Random rng = SeedManager.getRandom();
                la.move((rng.nextDouble() - 0.5) * step * 2.0,
                        (rng.nextDouble() - 0.5) * step * 2.0);
                layer.updateAgentPositionImmediately(la);
                return;
            }
            la.setTargetTankId(target.getId());
        }

        // 3. Move one step toward the target.
        double dx = target.getX() - la.getX();
        double dy = target.getY() - la.getY();
        double dist = Math.hypot(dx, dy);

        if (dist <= step) {
            // Snap onto the tank.
            la.setX(target.getX());
            la.setY(target.getY());
            layer.updateAgentPositionImmediately(la);
        } else {
            double moveStep = Math.min(step * SEEK_STEP_MULT, dist);
            la.move((dx / dist) * moveStep, (dy / dist) * moveStep);
            layer.updateAgentPositionImmediately(la);
        }

        // 4. Timeout: if she has been chasing too long, drop the target
        //    so she can reconsider on subsequent ticks.
        la.incrementTargetTankAgeTicks();
        if (la.getTargetTankAgeTicks() > TARGET_ACQUIRE_TIMEOUT_TICKS) {
            la.clearTargetTank();
        }
    }

    private InertAgent findNearestUsableTank(SpatialRegistry registry,
                                             LivingAgent la,
                                             double radius) {
        List<Agent> nearby = registry.getNearbyAgents(la.getX(), la.getY(), radius);
        InertAgent best = null;
        double bestD = Double.MAX_VALUE;
        for (Agent a : nearby) {
            if (a instanceof InertAgent tank && tank.getWaterVolume() > MIN_TANK_WATER) {
                double d = Math.hypot(tank.getX() - la.getX(), tank.getY() - la.getY());
                if (d < bestD) { bestD = d; best = tank; }
            }
        }
        return best;
    }
    
    
    private void executeThinLarvae(Agent agent, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            double cap = ia.getCapacity();
            int current = ia.getLarvalCount();
            if (current > cap * 0.8) {
                int target = (int)(cap * 0.8);
                int toKill = current - target;
                ia.setLarvalCount(target);
                SimulationLogger.fine("[TANK] Killed %d excess larvae, capacity=%.1f", toKill, cap);
            }
        }
    }
    
    private void executePupate(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.getStage() == LifecycleStage.LARVA) {
            if (project != null) {
                LifecycleModel model = project.getLifecycleModel();
                if (model != null) {
                    double tempKelvin = getValueAt(project, "t2m", agent.getX(), agent.getY());
                    model.tryAdvanceFromLarva(la, tempKelvin);
                }
            }
        }
    }

    private void executeEmerge(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.getStage() == LifecycleStage.PUPA) {
            if (project != null) {
                LifecycleModel model = project.getLifecycleModel();
                if (model != null) {
                    double tempKelvin = getValueAt(project, "t2m", agent.getX(), agent.getY());
                    model.tryAdvanceFromPupa(la, tempKelvin);
                }
            }
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
        if (agent instanceof LivingAgent la) la.setResting(false);
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
            double popDensity = populationLayer != null ? populationLayer.getValueAt(agent.getX(), agent.getY()) : 0.0;
            la.setEnergy(Math.min(1.0, la.getEnergy() + (popDensity > 0.01 ? 0.2 : 0.1)));
        }
    }

    private void executeMoveRandom(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            double step = project.getDefaultAgentStep();
            Random rng = SeedManager.getRandom();
            la.move((rng.nextDouble() - 0.5) * step, (rng.nextDouble() - 0.5) * step);
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
        if (agent instanceof LivingAgent la) la.setAlive(false);
        layer.killAgentImmediately(agent.getId());
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
        if (agent instanceof LivingAgent la) la.setGravid(true);
    }

    
    private void executeLayEggs(Agent agent, Project project, AgentLayer layer) {
        if (!(agent instanceof LivingAgent la) || la.getStage() != LifecycleStage.ADULT) return;
        if (!la.isGravid()) return;

        SpatialRegistry registry = project.getSpatialRegistry();
        double layRadius = Math.max(project.getDefaultAgentStep() * LAY_RADIUS_MULT, 1e-4);

        // 1. Prefer the persistent target if she is currently on / at it.
        InertAgent tank = null;
        if (la.getTargetTankId() != null) {
            Agent a = registry.getAgentById(la.getTargetTankId());
            if (a instanceof InertAgent t
                    && t.getWaterVolume() > MIN_TANK_WATER
                    && Math.hypot(t.getX() - la.getX(), t.getY() - la.getY()) <= layRadius) {
                tank = t;
            }
        }

        // 2. Fall back to the nearest usable tank within layRadius.
        if (tank == null) {
            List<Agent> nearby = registry.getNearbyAgents(la.getX(), la.getY(), layRadius);
            double bestD = Double.MAX_VALUE;
            for (Agent a : nearby) {
                if (a instanceof InertAgent t && t.getWaterVolume() > MIN_TANK_WATER) {
                    double d = Math.hypot(t.getX() - la.getX(), t.getY() - la.getY());
                    if (d < bestD) { bestD = d; tank = t; }
                }
            }
//            SimulationLogger.info("[NOLAY] %s gravid at (%.5f,%.5f) target=%s",
//                la.getId().substring(0, 8), la.getX(), la.getY(),
//                la.getTargetTankId() != null ? la.getTargetTankId().substring(0, 8) : "null");
            return;
        }
        
        // 3. Compute eggs and lay.
        double temperature = getValueAt(project, "t2m", la.getX(), la.getY());
        double population  = getValueAt(project, "Population", la.getX(), la.getY());
        if (population <= 0) population = 1.0;

        LifecycleModel model = project.getLifecycleModel();
        if (model == null) return;
        int eggs = model.eggsToLay(temperature, population);
        if (eggs <= 0) return;

        tank.addEggs(eggs);
        tank.updateCapacityFromVolume();
        
//        SimulationLogger.info("[LAY] %s laid %d eggs @ (%.5f,%.5f) tankWater=%.1f tankLarvae=%d",
//            la.getId().substring(0, 8), eggs, la.getX(), la.getY(),
//            tank.getWaterVolume(), tank.getLarvalCount()
//        );

        la.setGravid(false);
        la.clearTargetTank();
        la.setEnergy(Math.max(0.0, la.getEnergy() - 0.2));
    }
    
    
    private void executeEvaporate(Agent agent, Project project, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            double temperature = getValueAt(project, "t2m", ia.getX(), ia.getY());
            double precipitation = getValueAt(project, "tp", ia.getX(), ia.getY());

            double tempC = temperature - 273.15;
            double baseEvapRate = 0.02;
            double tempFactor = Math.max(0.2, 1.0 + (tempC - 20.0) * 0.05);
            double evaporationRate = baseEvapRate * tempFactor;
            double precipMm = precipitation * 1000;
            double refillRate = Math.min(15.0, precipMm * 0.5);

            double oldWater = ia.getWaterVolume();
            double clampedWater = Math.max(0, Math.min(100, oldWater - evaporationRate + refillRate));
            ia.setWaterVolume(clampedWater);

            if (Math.abs(clampedWater - oldWater) > 5.0) {
                SimulationLogger.fine("[TANK] Water: %.1f%% -> %.1f%% at (%.6f, %.6f)",
                        oldWater, clampedWater, ia.getX(), ia.getY());
            }
        }
    }

    private double getValueAt(Project p, String layerName, double x, double y) {
        Layer layer = p.getLayerByName(layerName);
        return (layer != null) ? layer.getValueAt(x, y) : 0.0;
    }

    public void clearCache() {
        scriptCache.clear();
        evaluationCache = new ConcurrentHashMap<>();
    }
}