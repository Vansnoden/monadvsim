package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.*;
import javax.script.ScriptEngine;
import java.util.List;
import java.util.Map;
import javax.script.Bindings;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;


public class RuleEngine {
    private final ScriptEngine engine;
    private final double defaultSearchRadius = 0.005;
    private final double defaultHatchProb = 0.01;
    private final double defaultStep = 0.0005;

    public RuleEngine() {
        System.setProperty("polyglot.js.nashorn-compat", "true");
        ScriptEngineManager manager = new ScriptEngineManager();
        this.engine = manager.getEngineByName("graal.js");
        if (this.engine == null) {
            throw new RuntimeException("-> GraalVM JS Engine not found. "
                    + "Ensure pom.xml dependencies are loaded.");
        }
    }

    /**
     * The core logic evaluator.
     * @param condition The logic string (e.g., "temp > 30 && random < 0.01")
     * @param agent The agent currently being processed
     * @param project The project containing environmental rasters
     * @return true if the rule conditions are met
     */
    public boolean evaluate(String condition, Agent agent, Project project) {
        try {
            Bindings bindings = engine.createBindings();
            
            // Map Raster Values (Environment)
            bindings.put("temp", getSafeValue(project, 
                    "Temperature", agent.getX(), agent.getY()) - 273.15);
            bindings.put("rain", getSafeValue(project, 
                    "Rainfall", agent.getX(), agent.getY()));
            bindings.put("pop", getSafeValue(project, 
                    "Population", agent.getX(), agent.getY()));
            bindings.put("elev", getSafeValue(project, 
                    "Elevation", agent.getX(), agent.getY()));
            
            // Map Agent State (Biology)
            if(agent instanceof LivingAgent la){
                bindings.put("age", la.getAge());
                bindings.put("isGravid", la.isGravid());
                bindings.put("random", Math.random());
            }
            
            
            // 3. Special Logic for Habitats (InertAgents)
            if (agent instanceof InertAgent tank) {
                bindings.put("larvae", tank.getLarvalCount());
                bindings.put("capacity", tank.getCapacity());
            }

            // Evaluate the string
            Object result = engine.eval(condition, bindings);
            return result instanceof Boolean && (Boolean) result;

        } catch (ScriptException e) {
            System.err.println("-> Rule Error for agent " + agent.getId() + ": " + condition);
            return false; // Fail safely
        } catch (Exception e) {
            System.err.println("-> Unexpected error in rule evaluation");
            return false;
        }
    }

    /**
     * Executes the resulting action in the simulation world.
     */
    public void execute(String action, Agent agent, Project project) {
        switch (action.toLowerCase()) {
            case "die":
                if (agent instanceof LivingAgent la) {
                    la.setAlive(false);
                }
                break;

            case "get_gravid":
                if (agent instanceof LivingAgent la) {
                    la.setGravid(true);
                }
                break;

            case "lay_eggs":
                // Interaction: Mosquito searches for nearby 
                // Tank in Spatial Registry
                List<Agent> nearby = project.getSpatialRegistry()
                        .getNearbyAgents(
                                agent.getX(), agent.getY(), 
                                defaultSearchRadius);
                for (Agent n : nearby) {
                    if (n instanceof InertAgent ia) {
                        ia.setEggCount(20); // Adds eggs to the habitat
                        if( agent instanceof LivingAgent la){
                            la.setGravid(false);
                        }
                        break;
                    }
                }
                break;

            case "hatch":
                if (agent instanceof InertAgent ia) {
                    // Logic to spawn a new LivingAgent at this location
                    int hatchCount = (int) (ia.getLarvalCount() * defaultHatchProb); 
                    ia.setLarvalCount(hatchCount);
                }
                break;

            case "move_random":
                double step = 0.0005;
                if(agent instanceof LivingAgent la){
                    la.move((Math.random() - 0.5) * step, 
                            (Math.random() - 0.5) * step);
                }
                break;
        }
    }

    private double getSafeValue(Project p, String layerName, double x, double y) {
        RasterLayer layer = p.getRasterByName(layerName);
        return (layer != null) ? layer.getValueAt(x, y) : 0.0;
    }
}