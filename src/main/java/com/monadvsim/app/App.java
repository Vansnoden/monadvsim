package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.utils.ClimateDatasetManager;
import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Random;

public class App {
    public static void main(String[] args) {
        System.out.print("Hello world");
        test();
    }
    
    
    private static void test(){
        System.out.println("Starting Multi-Agent Simulation with Climate Data");
        
        try {
            // 1. Create project
            Project project = new Project("Mosquito Simulation");
            // 1 degree of latitude is about 111 km
            project.setDefaultAgentSearchRadius(0.0005); // which is about 0.0005 * 111km = 55m
            project.setDefaultAgentStep(0.000005); // about 55.5 cm
            project.setDefaultBirthRate(20);
            
            
            // Set up time manager (simulate 30 days at 15-minute intervals)
            LocalDateTime startDate = LocalDateTime.of(2023, 6, 1, 0, 0);
            int totalTicks = 30 * 24 * 4; // 30 days * 24 hours * 4 (15-min intervals)
            TimeManager timeManager = new TimeManager(startDate, totalTicks, 15);
            
            ProjectPersistenceService persistenceService = new ProjectPersistenceService();
            
            // load static rasters
            String elevation_50_km_file = "prepared_data/elevation_50_km.tiff";
            String buildings_50_km_file = "prepared_data/buildings_50_km.tiff";
            String population_50_km_file = "prepared_data/pop_density_50_km.tiff";
            try {
                RasterLayer elev = new MemoryMappedRasterLayer("Elevation", 1, 1, 1);
//                RasterLayer buildings = new MemoryMappedRasterLayer("Buildings", 1, 1, 1);
                RasterLayer population = new MemoryMappedRasterLayer("Population", 1, 1, 1);
                persistenceService.loadRasterData(elev, elevation_50_km_file);
//                persistenceService.loadRasterData(buildings, buildings_50_km_file);
                persistenceService.loadRasterData(population, population_50_km_file);
                project.addLayer(elev);
//                project.addLayer(buildings);
                project.addLayer(population);
            } catch (Exception e) {
                System.err.println("-> Failed to load raster layers: " + e.getMessage());
                // Continue with simulation without elevation
            }
            
            
            // Load climate data
            String netcdfFile = "prepared_data/climate_2023_50_km_6.nc";
            
            ClimateDatasetManager climateManager = persistenceService.loadClimateData(
                project, netcdfFile, timeManager); // automatically adds climate layers
            
            // Create spatial registry
            Rectangle2D worldBounds = climateManager.getBounds();
            if (worldBounds == null) {
                worldBounds = new Rectangle2D.Double(-180, -90, 360, 180); // global  bounds default
            }
            SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds, 1.0); // 1-degree cells
            
            
            
            // Create agent layers with climate-aware rules
            RuleEngine ruleEngine = new RuleEngine();
            AgentLifecycleManager lifecycleManager = new AgentLifecycleManager(spatialRegistry);

            AgentLayer mosquitoLayer = new AgentLayer("Mosquitoes", ruleEngine, lifecycleManager);
            
            // Add climate-dependent rules
            mosquitoLayer.addRule(
                "temperature > 298 && precipitation < 0.001", // ~25°C and dry
                "reproduce",
                1
            );
    
            mosquitoLayer.addRule(
                "temperature < 283 || temperature > 313", // <10°C or >40°C
                "die",
                2
            );
            
//            mosquitoLayer.addRule(
//                "wind_speed > 10", // High wind
//                "move_shelter",
//                3
//            );
            
            project.addLayer(mosquitoLayer);
            project.setSpatialRegistry(spatialRegistry);
            
            
//            // Create simulation engine
//            SimulationEngine engine = new SimulationEngine(project, timeManager, spatialRegistry);
//            
//            // Run simulation in a separate thread
//            Thread simulationThread = new Thread(engine);
//            simulationThread.start();
//            
//            // Add shutdown hook
//            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
//                System.out.println("Shutting down simulation...");
//                engine.stop();
//                try {
//                    simulationThread.join(5000);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//            }));
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}