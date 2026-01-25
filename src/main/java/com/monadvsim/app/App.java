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
            project.setDefaultAgentSearchRadius(0.005); // 5 meters
            project.setDefaultAgentStep(0.0005); // 50 cm in 15 min
            project.setDefaultBirthRate(20);
            
            
            // 2. Set up time manager (simulate 30 days at 15-minute intervals)
            LocalDateTime startDate = LocalDateTime.of(2023, 6, 1, 0, 0);
            int totalTicks = 30 * 24 * 4; // 30 days * 24 hours * 4 (15-min intervals)
            TimeManager timeManager = new TimeManager(startDate, totalTicks, 15);
            
            // 3. Create spatial registry
            Rectangle2D worldBounds = new Rectangle2D.Double(-180, -90, 360, 180);
            SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds, 1.0); // 1-degree cells
            
            // 4. Load climate data
            ProjectPersistenceService persistenceService = new ProjectPersistenceService();
            String netcdfFile = "prepared_data/climate_2023_50_km_6.nc";
            
//            ClimateDatasetManager climateManager = persistenceService.loadClimateData(
//                project, netcdfFile, timeManager);
            
            ClimateDatasetManager climateManager = new ClimateDatasetManager(timeManager);
            climateManager.loadAvailableVariables(netcdfFile);
            
            // Add layers to project
            for (InterpolatedRasterLayer layer : climateManager.getLayers().values()) {
                project.addLayer(layer);

                // Set up tokens for rule engine
                if (project.getTokens() == null) {
                    project.setTokens(new ArrayList<>());
                }
                if (project.getLayerNames() == null) {
                    project.setLayerNames(new ArrayList<>());
                }

                // Map variable names to tokens
                String token = ProjectPersistenceService.getTokenForVariable(layer.getName());
                project.getTokens().add(token);
                project.getLayerNames().add(layer.getName());
            }
            
            climateManager.printStatistics();
            
            // 5. Create agent layers with climate-aware rules
//            RuleEngine ruleEngine = new RuleEngine();
//            AgentLifecycleManager lifecycleManager = new AgentLifecycleManager(spatialRegistry);
//            
//            AgentLayer mosquitoLayer = new AgentLayer("Mosquitoes", ruleEngine, lifecycleManager);
//            
//            // Add climate-dependent rules
//            mosquitoLayer.addRule(
//                "temperature > 298 && precipitation < 0.001", // ~25°C and dry
//                "reproduce",
//                1
//            );
//            
//            mosquitoLayer.addRule(
//                "temperature < 283 || temperature > 313", // <10°C or >40°C
//                "die",
//                2
//            );
//            
//            mosquitoLayer.addRule(
//                "wind_speed > 10", // High wind
//                "move_shelter",
//                3
//            );
//            
//            project.addLayer(mosquitoLayer);
//            project.setSpatialRegistry(spatialRegistry);
            
//            // 6. Create simulation engine
//            SimulationEngine engine = new SimulationEngine(project, timeManager, spatialRegistry);
//            
//            // 7. Run simulation in a separate thread
//            Thread simulationThread = new Thread(engine);
//            simulationThread.start();
//            
//            // 8. Add shutdown hook
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