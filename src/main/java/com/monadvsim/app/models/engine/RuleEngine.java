package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * JavaScript Rule Evaluator
 *
 * Evaluates agent behavior rules using GraalVM JavaScript engine.
 * Supports dynamic rule evaluation with environmental context access.
 * Implements thread-local contexts for concurrent rule evaluation.
 * Executes predefined actions (die, lay_eggs, move_random, etc.).
 * Includes caching for rule conditions and results.
 *
 * <p><b>Note:</b> Actions like {@code pupate}, {@code emerge}, and {@code hatch}
 * are now handled automatically by the {@link AgentLayer} using the
 * {@link LifecycleModel}. They are kept for compatibility but are deprecated.
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
            throw new RuntimeException("Failed to initialize GraalVM Context for thread: " + Thread.currentThread().getName(), e);
        }
    });

    public RuleEngine() {
        System.out.println("✅ RuleEngine initialized with ThreadLocal GraalVM Contexts.");
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
            System.err.println("Rule Evaluation Error: " + condition + " -> " + e.getMessage());
            return false;
        }
    }

    private void populateBindings(Value bindings, Agent agent, Project project) {
        // Bind agent as 'agent' object
        bindings.putMember("agent", agent);

        // Bind commonly used agent properties for easier access
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
            double value = getValueAt(project, project.getLayerNames().get(i), agent.getX(), agent.getY());
            bindings.putMember(token, value);
        }
    }

    public void execute(String action, Agent agent, Project project, AgentLayer layer) {
        switch (action.toLowerCase()) {
            case "die" -> executeDie(agent, layer);
            case "get_gravid" -> executeGetGravid(agent);
            case "lay_eggs" -> executeLayEggs(agent, project, layer);
            case "hatch" -> executeHatch(agent, project, layer);          // Deprecated – automatic in AgentLayer
            case "move_random" -> executeMoveRandom(agent, project, layer);
            case "reproduce" -> executeReproduce(agent, project, layer);
            case "pupate" -> executePupate(agent, layer);                // Deprecated – automatic in AgentLayer
            case "emerge" -> executeEmerge(agent, layer);                // Deprecated – automatic in AgentLayer
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

    // ------------------------------------------------------------------------
    // Behavioural actions
    // ------------------------------------------------------------------------

    private void executeRest(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setResting(true);
            la.setEnergy(Math.min(1.0, la.getEnergy() + 0.01));
            la.resetTimeWithoutRest();
//            System.out.println("Mosquito " + agent.getId() + " is resting. Energy: " + la.getEnergy());
        }
    }

    private void executeStopResting(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
            la.setResting(false);
//            System.out.println("Mosquito " + agent.getId() + " stopped resting");
        }
    }

    private void executeDieExhaustion(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la) {
//            System.out.println("Mosquito " + agent.getId() + " died from exhaustion after " +
//                    la.getTimeWithoutRest() + " ticks without rest");
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
                    System.out.println("Mosquito " + agent.getId() + " resting in building. " +
                            "Building density: " + buildingDensity + ", Energy: " + la.getEnergy());
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
                    System.out.println("Mosquito " + agent.getId() + " moved to better resting spot. " +
                            "Building density: " + bestDensity);
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
                if (popDensity > 1.0) {
                    la.setEnergy(Math.min(1.0, la.getEnergy() + 0.3));
                    System.out.println("Adult " + agent.getId() + " fed, energy: " + la.getEnergy());
                }
            }
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

    // ------------------------------------------------------------------------
    // Lifecycle actions – DEPRECATED (now handled automatically by AgentLayer)
    // ------------------------------------------------------------------------

    /**
     * @deprecated Pupation is now automatically driven by the LifecycleModel.
     */
    @Deprecated
    private void executePupate(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.getStage() == LifecycleStage.LARVA) {
            la.setStage(LifecycleStage.PUPA);
            la.setEnergy(0.6);
            System.out.println("Larva " + agent.getId() + " pupated");
        }
    }

    /**
     * @deprecated Emergence is now automatically driven by the LifecycleModel.
     */
    @Deprecated
    private void executeEmerge(Agent agent, AgentLayer layer) {
        if (agent instanceof LivingAgent la && la.getStage() == LifecycleStage.PUPA) {
            la.setStage(LifecycleStage.ADULT);
            la.setEnergy(0.9);
            System.out.println("Pupa " + agent.getId() + " emerged as adult");
        }
    }

    /**
     * @deprecated Hatching is now automatically handled in AgentLayer for InertAgent.
     */
    @Deprecated
    private void executeHatch(Agent agent, Project project, AgentLayer layer) {
        // This method is kept only for compatibility; it will not be called if you remove the "hatch" rule.
        // The automatic hatching in AgentLayer should be used instead.
        System.err.println("WARNING: Deprecated executeHatch called. Remove the 'hatch' rule.");
    }

    // ------------------------------------------------------------------------
    // Other lifecycle / environmental actions
    // ------------------------------------------------------------------------

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
            int eggsKilled = ia.getEggCount();
            int larvaeKilled = ia.getLarvalCount();
            ia.setEggCount(0);
            ia.setLarvalCount(0);
            ia.setWaterVolume(0);
            System.out.printf("Tank %s dried out! Killed %d eggs and %d larvae%n",
                    agent.getId(), eggsKilled, larvaeKilled);
            layer.updateAgentPositionImmediately(agent);
        }
    }

    private void executeFreeze(Agent agent, AgentLayer layer) {
        if (agent instanceof InertAgent ia) {
            int eggsKilled = ia.getEggCount();
            int larvaeKilled = ia.getLarvalCount();
            ia.setEggCount(0);
            ia.setLarvalCount(0);
            ia.setWaterVolume(Math.max(0, ia.getWaterVolume() - 10));
            System.out.printf("Tank %s frozen! Killed %d eggs and %d larvae. Water reduced to %.1f%%%n",
                    agent.getId(), eggsKilled, larvaeKilled, ia.getWaterVolume());
            layer.updateAgentPositionImmediately(agent);
        }
    }

    private void executeGetGravid(Agent agent) {
        if (agent instanceof LivingAgent la) la.setGravid(true);
    }

    /**
     * Temperature‑ and host‑dependent egg laying using the LifecycleModel.
     */
    private void executeLayEggs(Agent agent, Project project, AgentLayer layer) {
        if (!(agent instanceof LivingAgent la)) return;
        if (la.getStage() != LifecycleStage.ADULT) return;

        double temperature = getValueAt(project, "t2m", agent.getX(), agent.getY());
        double livestock = getValueAt(project, "Livestock", agent.getX(), agent.getY());
        if (livestock <= 0) {
            livestock = getValueAt(project, "Population", agent.getX(), agent.getY());
        }

        LifecycleModel model = project.getLifecycleModel();
        if (model == null) return;

        double eggsPerTick = model.effectiveFecundity(temperature, livestock);
        int eggsToLay = (int) Math.round(eggsPerTick);
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
            double evaporationRate = Math.max(0.01, (temperature - 293.15) / 20.0);
            evaporationRate *= (1.0 - Math.min(1.0, precipitation * 1000));
            double newWater = Math.max(0, ia.getWaterVolume() - evaporationRate);
            ia.setWaterVolume(newWater);
            if (precipitation > 0.001) {
                double refill = precipitation * 100;
                ia.setWaterVolume(Math.min(100, ia.getWaterVolume() + refill));
            }
            layer.updateAgentPositionImmediately(agent);
        }
    }

    // ------------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------------

    private double getValueAt(Project p, String layerName, double x, double y) {
        Layer layer = p.getLayerByName(layerName);
        return (layer != null) ? layer.getValueAt(x, y) : 0.0;
    }

    public void clearCache() {
        scriptCache.clear();
        evaluationCache.clear();
    }
}