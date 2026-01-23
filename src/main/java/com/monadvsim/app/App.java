package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.util.Random;

public class App {

    public static void main(String[] args) {
        try {
            // 1. Initialize Core Project Context
            Project project = new Project("Addis_Ababa_Stephensis_Model");
            
            // Simulation starts Jan 1st, 2024. 
            // 3000 ticks roughly covers 4 months of hourly climate data.
            LocalDateTime simStart = LocalDateTime.of(2024, 1, 1, 0, 0);
            TimeManager timeManager = new TimeManager(simStart, 3000, 15); 

            ProjectPersistenceService persistence = new ProjectPersistenceService();
            RuleEngine biologicalRules = new RuleEngine();

            // 2. Load Environmental Data (Dynamic Sizing via GeoTools)
            // Initialize with dummy values, loadRasterData will resize them internally
            RasterLayer pop = new RasterLayer("Population", 1, 1, 1);
            persistence.loadRasterData(pop, "prepared_data/pop_addis.tiff");

            // Match other layers to Population resolution
            RasterLayer temp = new RasterLayer("Temperature", pop.getWidth(), pop.getHeight(), 3000);
            RasterLayer rain = new RasterLayer("Rainfall", pop.getWidth(), pop.getHeight(), 3000);
            RasterLayer build = new RasterLayer("Buildings", pop.getWidth(), pop.getHeight(), 1);

            persistence.loadRasterData(build, "prepared_data/buildings_addis.tiff");
            persistence.loadClimateNetCDF(temp, rain, "prepared_data/climate_2024_01.nc");

            project.addRasterLayer(pop);
            project.addRasterLayer(temp);
            project.addRasterLayer(rain);
            project.addRasterLayer(build);

            // 3. Setup Spatial Indexing
            // IMPORTANT: Match bounds to the actual geographic footprint of your TIFF
            // If pop.getMinLon() etc are implemented, use them here.
            double minLon = 38.70, minLat = 8.95; 
            double widthLon = 0.1, heightLat = 0.1; // Extent of simulation area
            
            Rectangle2D worldBounds = new Rectangle2D.Double(minLon, minLat, widthLon, heightLat);
            SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds);
            project.setSpatialRegistry(spatialRegistry);

            // 4. Create Agent Layers with Biological Rules
            AgentLayer habitatLayer = new AgentLayer("Water_Tanks", biologicalRules);
            AgentLayer mosquitoLayer = new AgentLayer("Mosquitoes", biologicalRules);

            // --- SCIENTIFIC RULE DEFINITIONS ---
            // These strings are parsed by GraalVM JS at runtime
            
            // Rule: Larvae hatch into adults if there is sufficient rain
            habitatLayer.addRule("rain > 0.05 && larvae > 0 && random < 0.1", "hatch");
            
            // Rule: Adult mortality based on thermal stress or senescence
            mosquitoLayer.addRule("temp > 38 || age > 2500", "die");
            
            // Rule: Host seeking (random movement when not gravid)
            mosquitoLayer.addRule("!isGravid && random < 0.3", "move_random");
            
            // Rule: Oviposition (seek Water_Tanks when gravid)
            mosquitoLayer.addRule("isGravid", "lay_eggs");
            
            // Rule: Blood feeding (becoming gravid based on human population density)
            mosquitoLayer.addRule("!isGravid && pop > 0.4 && random < 0.05", "get_gravid");

            // 5. Seed Initial Population
            Random rand = new Random();
            int totalAgentsToSeed = 5000;
            
            for (int i = 0; i < totalAgentsToSeed; i++) {
                double rx = minLon + (widthLon * rand.nextDouble());
                double ry = minLat + (heightLat * rand.nextDouble());

                // Spatially constrained seeding: Tanks only in built-up areas
                if (build.getValueAt(rx, ry) > 0.5) {
                    InertAgent tank = new InertAgent(rx, ry);
                    tank.setLarvalCount(50);
                    tank.setCapacity(200);
                    habitatLayer.addAgent(tank);
                }
                
                // Initial adult population seeding in populated areas
                if (pop.getValueAt(rx, ry) > 0.5 && i < 1000) {
                    mosquitoLayer.addAgent(new LivingAgent(rx, ry));
                }
            }

            // 6. Register Layers in Execution Order
            // We register Habitat first so eggs laid by mosquitoes in T-1 
            // can hatch in T-0.
            project.addAgentLayer(habitatLayer);
            project.addAgentLayer(mosquitoLayer);

            // 7. Initialize and Run Engine
            System.out.println("--- Starting Simulation: " + project.getName() + " ---");
            SimulationEngine engine = new SimulationEngine(project, timeManager, spatialRegistry);
            
            // This loop runs until timeManager.tick() returns false
            engine.run(); 

            // 8. Output and Analysis
            persistence.exportToCSV(project, "output/addis_full_results.csv");
            System.out.println("✅ Simulation Complete. Results exported to CSV.");

        } catch (Exception e) {
            System.err.println("❌ Critical Simulation Failure:");
            e.printStackTrace();
        }
    }
}
