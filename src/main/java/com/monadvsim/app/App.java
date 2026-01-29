package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.utils.ClimateDatasetManager;
import com.monadvsim.app.models.utils.SnapshotMerger;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;


/**
 * Main Application Entry Point
 * 
 * The primary driver class that initializes and runs the simulation
 * 
 * Configures project settings, loads data (rasters, climate NetCDF) and seeds initial agent populations
 * 
 * Sets up simulation bounds, spatial registry, time manager, and rule engine
 * 
 * Contains the main simulation loop and monitoring system
 * 
 * Implements watchdog thread for simulation health monitoring
 * 
 * Handles graceful shutdown and statistics export.
 * 
 * @author void
 */


public class App {
    
    private static SimulationEngine simulationEngine;
    private static Thread simulationThread;
    // Helper method to track simulation start time
    private static long simulationStartTime = System.currentTimeMillis();
    private static Project project;
    private static SpatialRegistry spatialRegistry;
    private static TimeManager timeManager;
    private static Rectangle2D worldBounds;
    private static ProjectPersistenceService persistenceService;
      
    
    public static void main(String[] args) {
        System.out.println("Starting Multi-Agent Simulation System");

        try {
            // Initialize snapshot output directory
            File resultsDir = new File("results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
                System.out.println("📁 Created results directory");
            }

            // Configure and run simulation
            
            // define simulation time
            LocalDateTime startDate = LocalDateTime.of(2025, 9, 1, 0, 0);
            int totalTicks = 60 * 24 * 4; // 30 days * 24 hours * 4 (15-min intervals)
            timeManager = new TimeManager(startDate, totalTicks, 15);
            
            // configure simulation bounds (world bounds)
            // Create spatial registry
            worldBounds = createWorldBounds(
                                41.8562149505558, // longitude
                                9.604134790332163, // latitude
                                5 // Buffer in km
                            );
            // initialize project persistence service
            persistenceService = new ProjectPersistenceService();
            
            // initialize spatial registry
            spatialRegistry = new SpatialRegistry(worldBounds, 0.001); // 0.001 degree, Smaller cells for better spatial resolution
            
            // poject initialization
            project = new Project("Mosquito Simulation");
            configureSimulation(project, persistenceService, spatialRegistry, timeManager, 
                    worldBounds);
            
            createAndStartSimulation(project, timeManager, spatialRegistry);

            // Simple shutdown hook - just stop the engine
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑Shutdown signal received...");
                if (simulationEngine != null) {
                    simulationEngine.stop();
                }
            }));

            // Wait for simulation to complete if running in foreground
            if (simulationThread != null && simulationThread.isAlive()) {
                simulationThread.join();
            }
            
            // Save statistics after normal completion
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine);
            SnapshotMerger.mergeAfterSimulation();

            System.out.println("Simulation completed successfully!");

        } catch (InterruptedException e) {
            System.err.println("Error in simulation: " + e.getMessage());
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine);
        }catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine);
        }
    }
    
    
    private static void configureSimulation(Project project, 
            ProjectPersistenceService persistenceService, 
            SpatialRegistry spatialRegistry, TimeManager timeManager, 
            Rectangle2D worldBounds){
        System.out.println("Configuring project");
        try {
                        
            // 1 degree of latitude is about 111 km
            project.setDefaultAgentSearchRadius(0.0005); // which is about 0.0005 * 111km = 55m
            project.setDefaultAgentStep(0.00005); // about ~5.5 meters / 15 min per move
            project.setDefaultBirthRate(20);
            project.setDefaultMaxAgentAge(20 * 24 * 4); // 20 days * 24h * 4 (15 days at 15min a tick) 
            project.setDefaultHatchingProbability(0.8);
            
            // load static rasters
            String elevation_file = "prepared_data/elevation_5_km.tiff";
            String buildings_file = "prepared_data/buildings_100_km.tif";
            String population_file = "prepared_data/population_density_100m.tif";
            
            RasterLayer elev = new MemoryMappedRasterLayer("Elevation", 1, 1, 1);
            RasterLayer buildings = new MemoryMappedRasterLayer("Buildings", 1, 1, 1);
            RasterLayer population = new MemoryMappedRasterLayer("Population", 1, 1, 1);
            
            try {
                persistenceService.loadRasterData(elev, elevation_file);
                persistenceService.loadRasterData(buildings, buildings_file);
                persistenceService.loadRasterData(population, population_file);
                project.addLayer(elev);
                project.addLayer(buildings);
                project.addLayer(population);
                System.out.println("Static raster layers loaded successfully");
            } catch (Exception e) {
                System.err.println("Failed to load raster layers: " + e.getMessage());
                // Create fallback data
                createFallbackRasters(elev, buildings, population);
                project.addLayer(elev);
                project.addLayer(buildings);
                project.addLayer(population);
            }
            
            // Load climate data
            System.out.println("Loading climate data...");
            String netcdfFile = "prepared_data/historical_climate_2025_5_km_9_12.nc";
            ClimateDatasetManager climateManager = null;
            
            try {
                climateManager = persistenceService.loadClimateData(project, 
                        netcdfFile, timeManager);
                System.out.println("Climate data loaded successfully");
            } catch (Exception e) {
                System.err.println("Failed to load climate data: " + e.getMessage());
                System.out.println("Using fallback climate data...");
                // Create fallback climate layers
                createFallbackClimateLayers(project, timeManager);
            }
            
            // set project spatial registry
            project.setSpatialRegistry(spatialRegistry);
            
            // Create Rule engine and lifecycle manager
            RuleEngine ruleEngine = new RuleEngine();
            AgentLifeCycleManager lifecycleManager = new AgentLifeCycleManager(spatialRegistry);
            
            // Create agent layers
            AgentLayer mosquitoLayer = new AgentLayer("Mosquitoes", ruleEngine, lifecycleManager);
            AgentLayer habitatLayer = new AgentLayer("WaterTanks", ruleEngine, lifecycleManager);
            
            mosquitoLayer.setLifecycleManager(lifecycleManager);
            habitatLayer.setLifecycleManager(lifecycleManager);
            
            // Add rules for mosquitoLayer
            // Feed: Adult mosquitoes between 22.5-67.5 hours old with low energy
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.age >= 6*15 && agent.age <= 18*15 && agent.energy < 0.5", 
                "feed", 
                5
            );

            // Become gravid: Adult with energy, after 48 hours
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.energy > 0 && agent.age > 192", 
                "get_gravid", 
                4
            );

            // An. stephensi prefers populated urban container
            mosquitoLayer.addRule(
                "agent.gravid == true && temperature > 293.15 && building_density > 0.1 && population > 0.05", 
                "lay_eggs", 
                6
            );

            // Pupate: Larva older than 8 days, temperature > 22°C
            mosquitoLayer.addRule(
                "agent.stage == 'LARVA' && agent.age > 768 && temperature > 295.15", 
                "pupate", 
                3
            );

            // Emerge: Pupa older than 2 days
            mosquitoLayer.addRule(
                "agent.stage == 'PUPA' && agent.age > 192", 
                "emerge", 
                3
            );

            // Die: Extreme temperatures (10°C or 40°C)
            mosquitoLayer.addRule(
                "temperature < 283.15 || temperature > 313.15", 
                "die", 
                10
            );
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.energy < 0.5 && population > 0.10", 
                "feed", 
                5
            );
            // Rest during heavy rain (mosquitoes seek shelter)
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && precipitation > 0.005 && building_density > 0.1 && !agent.resting", 
                "rest_in_building", 
                4
            );
            // Rest after feeding (high energy + buildings available)
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.energy > 0.7 && building_density > 0.2 && !agent.resting", 
                "rest_in_building", 
                3
            );

            // Rest when tired (low energy + buildings available)
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.energy < 0.3 && building_density > 0.1 && !agent.resting", 
                "rest_in_building", 
                4
            );

            // Stop resting when energy is restored or building density is poor
            mosquitoLayer.addRule(
                "agent.resting && (agent.energy > 0.9 || building_density < 0.05)", 
                "stop_resting", 
                2
            );

            // Rest during hot midday (11am-3pm) - mosquitoes avoid extreme heat
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && temperature > 303.15 && building_density > 0.3 && !agent.resting", 
                "rest_in_building", 
                3
            );

            // Rest during cold nights
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && temperature < 288.15 && building_density > 0.2 && !agent.resting", 
                "rest", 
                3
            );

            // Emergency rest - too long without rest
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.timeWithoutRest > 48 && !agent.resting", 
                "rest", 
                5  // High priority - need to rest!
            );

            // Die from exhaustion if too long without rest and energy critically low
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.timeWithoutRest > 72 && agent.energy < 0.1", 
                "die_exhaustion", 
                10
            );

            // Move less when resting
            mosquitoLayer.addRule(
                "agent.resting", 
                "",  // Empty action - just prevents move_random from executing
                6
            );
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && !agent.resting && agent.energy > 0.3", 
                "move_random", 
                2
            );

            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.energy < 0.2 && building_density > 0.3", 
                "move_random", 
                1
            );
            // Larval development acceleration
            mosquitoLayer.addRule(
                "agent.stage == 'LARVA' && temperature > 303.15",  // >30°C
                "",  // Empty action speeds development via faster aging
                3
            );

            // Adult activity suppression in cold
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && temperature < 288.15",  // <15°C
                "rest", 
                4
            );
            // Enhanced feeding rule (was incorrect)
            mosquitoLayer.addRule(
                "agent.stage == 'ADULT' && agent.energy < 0.4 && population > 0.15 && !agent.resting", 
                "feed", 
                5
            );

            // Enhanced egg-laying with habitat preference
            mosquitoLayer.addRule(
                "agent.gravid == true && temperature > 293.15 && building_density > 0.15 && population > 0.10", 
                "lay_eggs", 
                6
            );
            
            
            // rules habitat layer
            // Hatch eggs: Moderate temperature (14-33°C) AND tank has water
            habitatLayer.addRule(
                "temperature > 287.15 && temperature < 306.15 && agent.waterVolume > 10", 
                "hatch",
                1
            );

            // Tank dries out: No water OR too hot
            habitatLayer.addRule(
                // Hatch when: temperature is optimal AND water level is sufficient AND eggs exist
                "temperature >= 293.15 && temperature <= 303.15 && " +  // 20-30°C optimal range
                "agent.waterVolume > 20 && agent.eggCount > 0", 
                "hatch",
                1
            );

            // Freeze when temperature < 0°C (273.15K)
            habitatLayer.addRule(
                "temperature < 273.15 && agent.waterVolume > 0", 
                "freeze",
                2
            );

            // Dry out when water is very low
            habitatLayer.addRule(
                "agent.waterVolume <= 5 && agent.waterVolume > 0", 
                "dry_out",
                3
            );

            // Complete evaporation - remove tank if dry for too long
            habitatLayer.addRule(
                "agent.waterVolume == 0 && agent.eggCount == 0 && agent.larvalCount == 0", 
                "die",
                4
            );
            habitatLayer.addRule(
                // Evaporate faster in hot, dry conditions
                "temperature > 303.15 && precipitation < 0.001", 
                "evaporate",
                5
            );
            
            // Add agent layers to project
            project.addLayer(mosquitoLayer);
            project.addLayer(habitatLayer);
            
            // Set up tokens for rule engine
            // These should match the variable names in your climate data
            List<String> tokens = new ArrayList<>();
            List<String> layerNames = new ArrayList<>();
            
            
            // Add climate variables
            tokens.add("temperature");
            layerNames.add("t2m"); // Temperature variable name in NetCDF
            
            tokens.add("precipitation");
            layerNames.add("tp"); // Precipitation variable name in NetCDF
            
            // Add static layers
            tokens.add("population");
            layerNames.add("Population");
            
            tokens.add("building_density");
            layerNames.add("Buildings");
            
            tokens.add("elevation");
            layerNames.add("Elevation");
            
            tokens.add("age");
            layerNames.add("Mosquitoes");
            
            tokens.add("stage");
            layerNames.add("Mosquitoes");
            
            tokens.add("gravid");
            layerNames.add("Mosquitoes");

            
            project.setTokens(tokens);
            project.setLayerNames(layerNames);
            
            
            // Standardize all layers to the same geographic "surface"
            double minLon = worldBounds.getMinX();
            double maxLon = worldBounds.getMaxX();
            double minLat = worldBounds.getMinY();
            double maxLat = worldBounds.getMaxY();

            // If you have climate layers in a list:
            for (Layer layer : project.getLayers()) {
                if (layer instanceof RasterLayer rl) {
                    rl.setBounds(minLon, maxLon, minLat, maxLat);
                }
            }
            
            // Seed initial population
            System.out.println("Seeding initial agents population...");
            seedInitialPopulation(habitatLayer, 
                    mosquitoLayer, buildings, population, spatialRegistry,
                    worldBounds);
            
            System.out.printf("Initial agents: %d tanks, %d mosquitoes%n",
                habitatLayer.getAgents().size(), mosquitoLayer.getAgents().size());
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    private static void createAndStartSimulation(Project project, 
            TimeManager timeManager, SpatialRegistry spatialRegistry){
        // Create and start simulation engine
        System.out.println("Creating simulation engine...");
        simulationEngine = new SimulationEngine(project, timeManager, spatialRegistry);
        
        // Run simulation in background thread
        simulationThread = new Thread(() -> {
            try {
                simulationEngine.run();
                System.out.println("Simulation thread completed");
            } catch (Exception e) {
                System.err.println("Error in simulation thread: " + e.getMessage());
                e.printStackTrace();
            }
        });

        simulationThread.setName("Simulation-Thread");
        simulationThread.setDaemon(false);
        simulationThread.start();

        startWatchdog(simulationEngine, simulationThread, project, spatialRegistry);

        System.out.println("Simulation started! Press Ctrl+C to stop.");

        // Monitor simulation progress
        monitorSimulation(simulationEngine, project);
    }
    
    
    private static void createFallbackRasters(RasterLayer elev, 
            RasterLayer buildings, RasterLayer population) {
        // Create 100x100 grid for Addis Ababa area
        elev.initialize(100, 100, 1);
        elev.setBounds(38.70, 38.80, 8.95, 9.05);
        
        buildings.initialize(100, 100, 1);
        buildings.setBounds(38.70, 38.80, 8.95, 9.05);
        
        population.initialize(100, 100, 1);
        population.setBounds(38.70, 38.80, 8.95, 9.05);
        
        Random rand = new Random();
        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 100; y++) {
                // Higher elevation in center
                double elevValue = 2000 + Math.sin(x * 0.1) 
                        * Math.cos(y * 0.1) * 500;
                elev.setData(0, x, y, elevValue);
                
                // Buildings concentrated in center
                double distFromCenter = Math.sqrt(Math.pow(x-50, 2) 
                        + Math.pow(y-50, 2));
                double buildingValue = Math.max(0, 1.0 - distFromCenter / 50.0);
                buildings.setData(0, x, y, buildingValue);
                
                // Population density
                double popValue = Math.max(0, 0.8 - distFromCenter / 60.0) 
                        + rand.nextDouble() * 0.2;
                population.setData(0, x, y, popValue);
            }
        }
    }
    
    
    private static void createFallbackClimateLayers(Project project, 
            TimeManager timeManager) {
        // Create fallback temperature layer as a regular RasterLayer
        RasterLayer temperatureLayer = new RasterLayer("t2m", 10, 10, 24*30); // 30 days of hourly data
        
        // Create fallback precipitation layer
        RasterLayer precipitationLayer = new RasterLayer("tp", 10, 10, 24*30);
        
        // Set bounds for Addis Ababa area
        temperatureLayer.setBounds(38.70, 38.80, 8.95, 9.05);
        precipitationLayer.setBounds(38.70, 38.80, 8.95, 9.05);
        
        // Generate realistic climate data
        Random rand = new Random();
        for (int t = 0; t < 24*30; t++) {
            for (int x = 0; x < 10; x++) {
                for (int y = 0; y < 10; y++) {
                    // Daily temperature cycle (20-30°C range)
                    int hourOfDay = t % 24;
                    double baseTemp = 293.15 + 5.0; // ~20°C base
                    double dailyVariation = 10.0 * Math.sin(hourOfDay * Math.PI / 12.0);
                    double tempValue = baseTemp + dailyVariation + rand.nextDouble() * 2.0;
                    
                    // Occasional rainfall
                    double precipValue = 0.0;
                    if (rand.nextDouble() < 0.1) { // 10% chance of rain
                        precipValue = 0.001 + rand.nextDouble() * 0.005; // 1-6mm/hour
                    }
                    
                    temperatureLayer.setData(t, x, y, tempValue);
                    precipitationLayer.setData(t, x, y, precipValue);
                }
            }
        }
        
        project.addLayer(temperatureLayer);
        project.addLayer(precipitationLayer);
        
        System.out.println("Created fallback climate layers");
    }
    
    
    private static void seedInitialPopulation(AgentLayer habitatLayer, 
                                         AgentLayer mosquitoLayer,
                                         RasterLayer buildings,
                                         RasterLayer population,
                                         SpatialRegistry spatialRegistry,
                                         Rectangle2D worldBounds) {
        System.out.println("=== DEBUG: Checking raster values ===");

        // Test some points to understand the raster values
        Random rand = new Random();
        for (int i = 0; i < 5; i++) {
            double testX = worldBounds.getMinX() + rand.nextDouble() * worldBounds.getWidth();
            double testY = worldBounds.getMinY() + rand.nextDouble() * worldBounds.getHeight();
            double buildingVal = buildings.getValueAt(testX, testY);
            double popVal = population.getValueAt(testX, testY);
            System.out.printf("Test point %d (%.6f, %.6f): buildings=%.6f, population=%.6f%n",
                             i, testX, testY, buildingVal, popVal);
        }

        System.out.println("\nSeeding initial population with ADJUSTED thresholds...");

        int tanksToSeed = 1000;  // Start smaller for testing
        int mosquitoesToSeed = 500000;  // Start smaller for testing

        // Create habitat calculator with adjusted thresholds
        HabitatCalculator habitatCalc = new HabitatCalculator(buildings, population, 
                                                             worldBounds, 50, 50); // Smaller grid for testing

        // Seed water tanks
        System.out.println("\nSeeding water tanks...");
        int tanksPlaced = 0;
        for (int i = 0; i < tanksToSeed; i++) {
            double[] point = habitatCalc.getRandomWeightedPoint();
            double rx = point[0];
            double ry = point[1];

            double buildingDensity = buildings.getValueAt(rx, ry);
            double popDensity = population.getValueAt(rx, ry);

            if (buildingDensity > 0.15 && popDensity > 0.05) {
                InertAgent tank = new InertAgent(rx, ry);

                // Initialize tank properties
                tank.setWaterVolume(70 + rand.nextDouble() * 30); // 70-100%
                tank.setLarvalCount(rand.nextInt(30) + 10);      // 10-40 larvae
                tank.setEggCount(rand.nextInt(80) + 20);         // 20-100 eggs
                tank.setCapacity(300 + rand.nextDouble() * 200); // 300-500 capacity

                habitatLayer.addAgent(tank);
                spatialRegistry.registerAgent(tank);
                tanksPlaced++;

                if (tanksPlaced % 100 == 0) {
                    System.out.printf("  Placed %d/%d water tanks (bldg=%.2f%%, pop=%.2f%%)%n", 
                                     tanksPlaced, tanksToSeed, buildingDensity, popDensity);
                }
            }
        }

        System.out.printf("Seeded %d water tanks%n", tanksPlaced);

        // Seed mosquitoes
        System.out.println("\nSeeding mosquitoes...");
        int mosquitoesPlaced = 0;
        for (int i = 0; i < mosquitoesToSeed; i++) {
            double[] point = habitatCalc.getRandomWeightedPoint();
            double rx = point[0];
            double ry = point[1];

            double buildingDensity = buildings.getValueAt(rx, ry);
            double popDensity = population.getValueAt(rx, ry);

            // ADJUSTED: Lower thresholds for mosquitoes
            if (buildingDensity > 0.05 || popDensity > 0.02) {
                LivingAgent mosquito = new LivingAgent(rx, ry);

                // Determine stage
                double stageRand = rand.nextDouble();
                LifecycleStage stage;
                if (stageRand < 0.6) {      // 60% adults
                    stage = LifecycleStage.ADULT;
                } else if (stageRand < 0.85) { // 25% larvae
                    stage = LifecycleStage.LARVA;
                } else {                       // 15% pupae
                    stage = LifecycleStage.PUPA;
                }

                mosquito.setStage(stage);

                // Set properties based on stage
                switch (stage) {
                    case ADULT:
                        mosquito.setAge(rand.nextInt(20 * 24 * 4));
                        mosquito.setGravid(rand.nextDouble() < 0.2);
                        mosquito.setEnergy(0.3 + rand.nextDouble() * 0.5);
                        if (rand.nextDouble() < 0.3) {
                            mosquito.setResting(true);
                            mosquito.setRestingDuration(rand.nextInt(4));
                        }
                        break;

                    case LARVA:
                        mosquito.setAge(rand.nextInt(10 * 24 * 4));
                        mosquito.setEnergy(0.6 + rand.nextDouble() * 0.3);
                        mosquito.setDaysToPupa(5 + rand.nextInt(3));
                        break;

                    case PUPA:
                        mosquito.setAge(rand.nextInt(3 * 24 * 4));
                        mosquito.setEnergy(0.5 + rand.nextDouble() * 0.3);
                        mosquito.setDaysToAdult(2 + rand.nextInt(2));
                        break;
                }

                mosquitoLayer.addAgent(mosquito);
                spatialRegistry.registerAgent(mosquito);
                mosquitoesPlaced++;

                if (mosquitoesPlaced % 500 == 0) {
                    System.out.printf("  Placed %d/%d mosquitoes (bldg=%.2f%%, pop=%.2f%%)%n", 
                                     mosquitoesPlaced, mosquitoesToSeed, buildingDensity, popDensity);
                }
            }
        }

        System.out.printf("Seeded %d mosquitoes%n", mosquitoesPlaced);

        // Print summary
        System.out.println("\n=== Seeding Summary ===");
        System.out.printf("Water tanks: %d (target: %d)%n", tanksPlaced, tanksToSeed);
        System.out.printf("Mosquitoes: %d (target: %d)%n", mosquitoesPlaced, mosquitoesToSeed);
    }
    
    
    private static void monitorSimulation(SimulationEngine engine, Project project) {
        // Monitor simulation progress in main thread
        while (engine != null) {
            try {
                Thread.sleep(5000); // Check every 5 seconds

                Map<String, Object> state = engine.getState();
                boolean running = (Boolean) state.get("running");
                long tick = (Long) state.get("tick");

                if (!running) {
                    System.out.println("Simulation has stopped normally");
                    break;
                }
                
                // Check if simulation should have completed
                if (tick >= timeManager.getTotalTicks()) {
                    System.out.println("Simulation reached total ticks, stopping engine...");
                    engine.stop();
                    break;
                }
                
                if (!running) {
                    System.out.println("Simulation has stopped normally");
                    break;
                }

                // Print progress
                Integer totalAgentsObj = (Integer) state.get("totalAgents");
                int totalAgents = totalAgentsObj != null ? totalAgentsObj : 0;

                Double avgTickTimeObj = (Double) state.get("avgTickTime");
                double avgTickTime = avgTickTimeObj != null ? avgTickTimeObj : 0.0;

                System.out.printf("[Monitor] Tick: %d | Agents: %d | Avg Tick Time: %.2f ms%n",
                    tick, totalAgents, avgTickTime);

                // Every 100 ticks, print more detailed info
                if (tick % 100 == 0) {
                    System.out.println("--- Detailed Status ---");
                    for (AgentLayer layer : project.getAgentLayers()) {
                        Map<String, Object> layerStats = layer.getStatistics();
                        System.out.printf("  %s: %d agents, %d rules evaluated%n",
                            layer.getName(), 
                            layerStats.get("agentCount"),
                            layerStats.get("rulesEvaluated"));
                    }
                    System.out.println("----------------------");
                }

                // Check if we're making progress
                if (avgTickTime > 10000) { // 10 seconds per tick is too slow
                    System.err.println("CRITICAL: Tick time too slow (" 
                            + avgTickTime + "ms), simulation may be hanging");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Monitor thread interrupted");
                break;
            } catch (Exception e) {
                System.err.println("Error in monitor: " + e.getMessage());
                // Don't break immediately, might be temporary
                try {
                    Thread.sleep(10000); // Wait longer before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    
    public static Rectangle2D createWorldBounds(double centerLat, 
            double centerLon, double bufferKm) {
        // 1 degree is roughly 111.32 km at the equator
        double bufferDegrees = bufferKm / 111.32;
        double minLon = centerLon - bufferDegrees;
        double minLat = centerLat - bufferDegrees;
        double sizeDegrees = bufferDegrees * 2;
        return new Rectangle2D.Double(minLon, minLat, sizeDegrees, sizeDegrees);
    }
    
    // to enable simulation clean interruption at any time
    private static void startWatchdog(SimulationEngine engine,
            Thread simulationThread, Project project, 
            SpatialRegistry spatialRegistry) {
        Thread watchdog = new Thread(() -> {
            try {
                int stuckCount = 0;
                long lastTick = 0;
                long lastTickTime = System.currentTimeMillis();
                
                while (simulationThread.isAlive() 
                        && !simulationThread.isInterrupted()) {
                    Thread.sleep(10000); // Check every 10 seconds
                    
                    try {
                        Map<String, Object> state = engine.getState();
                        long currentTick = (Long) state.get("tick");
                        long currentTime = System.currentTimeMillis();
                        
                        if (currentTick == lastTick) {
                            stuckCount++;
                            long stuckSeconds = (currentTime - lastTickTime) / 1000;
                            
                            System.err.println("WARNING: Simulation may be "
                                    + "stuck at tick " + currentTick 
                                    + " (stuck for " + stuckSeconds 
                                    + " seconds, count: " + stuckCount + ")");
                            
                            if (stuckCount > 3) { // Stuck for 30+ seconds
                                System.err.println("CRITICAL: Simulation "
                                        + "appears stuck for over 30 "
                                        + "seconds, forcing shutdown");
                                
                                // Save statistics before shutting down
                                saveFinalStatisticsToFile(project, spatialRegistry, engine);
                                SnapshotMerger.mergeAfterSimulation();
                                
                                engine.stop();
                                simulationThread.interrupt();
                                
                                // Give it a chance to shut down gracefully
                                Thread.sleep(5000);
                                
                                if (simulationThread.isAlive()) {
                                    System.err.println("Simulation thread "
                                            + "still alive, forcing termination");
                                    System.exit(1);
                                }
                                break;
                            }
                        } else {
                            stuckCount = 0;
                            lastTick = currentTick;
                            lastTickTime = currentTime;
                        }
                        
                    } catch (Exception e) {
                        System.err.println("Error in watchdog while "
                                + "checking state: " + e.getMessage());
                        if (stuckCount++ > 5) {
                            System.err.println("CRITICAL: Cannot retrieve "
                                    + "simulation state, forcing shutdown");
                            saveFinalStatisticsToFile(project, spatialRegistry, engine);
                            simulationThread.interrupt();
                            break;
                        }
                    }
                }
                
                System.out.println("Watchdog thread exiting");
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Watchdog thread interrupted");
            }
        });
        
        watchdog.setDaemon(true);
        watchdog.setName("Simulation-Watchdog");
        watchdog.setPriority(Thread.MIN_PRIORITY);
        watchdog.start();
        
        System.out.println("Watchdog thread started");
    }
    
    
    /**
     * Saves final detailed statistics to a file
     */
    private static void saveFinalStatisticsToFile(Project project, 
            SpatialRegistry spatialRegistry, SimulationEngine engine) {
        if (project == null || spatialRegistry == null) {
            System.err.println("Cannot save statistics: project "
                    + "or spatial registry is null");
            return;
        }
        
        try {
            // Create results directory
            File resultsDir = new File("results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }
            
            // Generate filename with timestamp
            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeProjectName = project.getName()
                    .replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String filename = String.format("results/%s_final_statistics_%s.txt",
                    safeProjectName, timestamp);
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
                writer.println("=".repeat(80));
                writer.println("FINAL SIMULATION STATISTICS");
                writer.println("=".repeat(80));
                writer.println();
                
                // Simulation metadata
                writer.println("SIMULATION METADATA");
                writer.println("-".repeat(40));
                writer.printf("Project Name: %s%n", project.getName());
                writer.printf("Timestamp: %s%n", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                writer.printf("Total Runtime: %.2f seconds%n", engine != null ? 
                    (System.currentTimeMillis() - getSimulationStartTime()) / 1000.0 : 0);
                writer.println();
                
                // Agent Statistics
                writer.println("AGENT STATISTICS");
                writer.println("-".repeat(40));
                int totalAgents = 0;
                for (AgentLayer layer : project.getAgentLayers()) {
                    int layerAgents = layer.getAgents().size();
                    writer.printf("%-20s: %,9d agents%n", layer.getName(), layerAgents);
                    totalAgents += layerAgents;
                    
                    // Get detailed layer statistics if available
                    Map<String, Object> layerStats = layer.getStatistics();
                    if (layerStats != null && !layerStats.isEmpty()) {
                        for (Map.Entry<String, Object> entry : layerStats.entrySet()) {
                            if (!entry.getKey().equals("agentCount")) {
                                writer.printf("  %-18s: %s%n", entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
                writer.printf("%-20s: %,9d agents%n", "TOTAL", totalAgents);
                writer.println();
                
                // Spatial Registry Statistics
                writer.println("SPATIAL REGISTRY STATISTICS");
                writer.println("-".repeat(40));
                Map<String, Object> spatialStats = spatialRegistry.getStatistics();
                if (spatialStats != null) {
                    for (Map.Entry<String, Object> entry : spatialStats.entrySet()) {
                        writer.printf("%-25s: %s%n", entry.getKey(), entry.getValue());
                    }
                }
                writer.println();
                
                // Layer Statistics
                writer.println("LAYER STATISTICS");
                writer.println("-".repeat(40));
                int rasterLayers = 0;
                int agentLayers = 0;
                int otherLayers = 0;
                
                for (Layer layer : project.getLayers()) {
                    if (layer instanceof RasterLayer || layer instanceof InterpolatedRasterLayer) {
                        rasterLayers++;
                    } else if (layer instanceof AgentLayer) {
                        agentLayers++;
                    } else {
                        otherLayers++;
                    }
                }
                
                writer.printf("Raster Layers: %d%n", rasterLayers);
                writer.printf("Agent Layers: %d%n", agentLayers);
                writer.printf("Other Layers: %d%n", otherLayers);
                writer.printf("Total Layers: %d%n", project.getLayers().size());
                writer.println();
                
                // Memory Usage
                writer.println("SYSTEM RESOURCES");
                writer.println("-".repeat(40));
                Runtime runtime = Runtime.getRuntime();
                long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                long totalMB = runtime.totalMemory() / (1024 * 1024);
                long maxMB = runtime.maxMemory() / (1024 * 1024);
                
                writer.printf("Memory Used: %d MB%n", usedMB);
                writer.printf("Memory Total: %d MB%n", totalMB);
                writer.printf("Memory Max: %d MB%n", maxMB);
                writer.printf("Memory Usage: %.1f%%%n", (usedMB * 100.0) / totalMB);
                writer.println();
                
                // Performance Metrics (from SimulationEngine if available)
                if (engine != null) {
                    writer.println("PERFORMANCE METRICS");
                    writer.println("-".repeat(40));
                    Map<String, Object> engineStats = engine.getState();
                    for (Map.Entry<String, Object> entry : engineStats.entrySet()) {
                        writer.printf("%-25s: %s%n", entry.getKey(), entry.getValue());
                    }
                    
                    // Get detailed statistics if available
                    try {
                        Map<String, Object> detailedStats = engine.getDetailedStatistics();
                        if (detailedStats != null && !detailedStats.isEmpty()) {
                            writer.println();
                            writer.println("DETAILED STATISTICS");
                            writer.println("-".repeat(40));
                            for (Map.Entry<String, Object> entry : detailedStats.entrySet()) {
                                writer.printf("%-30s: %s%n", entry.getKey(), entry.getValue());
                            }
                        }
                    } catch (Exception e) {
                        writer.println("Detailed statistics not available");
                    }
                }
                
                // Environment Statistics
                writer.println();
                writer.println("ENVIRONMENT STATISTICS");
                writer.println("-".repeat(40));
                writer.printf("Default Search Radius: %.6f degrees (approx. %.1f meters)%n", 
                    project.getDefaultAgentSearchRadius(),
                    project.getDefaultAgentSearchRadius() * 111320);
                writer.printf("Default Agent Step: %.6f degrees (approx. %.1f meters)%n",
                    project.getDefaultAgentStep(),
                    project.getDefaultAgentStep() * 111320);
                writer.printf("Default Hatching Probability: %.3f%n", project.getDefaultHatchingProbability());
                writer.printf("Default Birth Rate: %d eggs%n", project.getDefaultBirthRate());
                writer.printf("Default Max Agent Age: %d ticks (%.1f days)%n", 
                    project.getDefaultMaxAgentAge(),
                    project.getDefaultMaxAgentAge() / 96.0); // 96 ticks per day (15-min intervals)
                
                // Simulation Summary
                writer.println();
                writer.println("=".repeat(80));
                writer.println("SIMULATION SUMMARY");
                writer.println("=".repeat(80));
                writer.printf("Total Agents Processed: %,d%n", totalAgents);
                writer.printf("Simulation Completed: %s%n", 
                    engine != null && !engine.getState().get("running").equals(true) ? "YES" : "NO");
                
                if (engine != null) {
                    Map<String, Object> state = engine.getState();
                    writer.printf("Final Tick: %d%n", state.get("tick"));
                    writer.printf("Average Tick Time: %.2f ms%n", state.get("avgTickTime"));
                    double fps = 1000.0 / (Double) state.get("avgTickTime");
                    writer.printf("Effective FPS: %.1f%n", fps);
                }
                
                writer.println("=".repeat(80));
                writer.println("Statistics saved to: " 
                        + new File(filename).getAbsolutePath());
                
                System.out.println("Final statistics saved to: " + filename);
                
            } catch (Exception e) {
                System.err.println("Error writing statistics file: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.err.println("Error saving final statistics: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    private static long getSimulationStartTime() {
        return simulationStartTime;
    }
    
    
    // Add this as a static inner class in App.java
    private static class HabitatCalculator {
        private final double[][] suitabilityGrid;
        private final double[] cumulativeDistribution;
        private final double cellSize;
        private final double minX, minY;
        private final int gridSizeX, gridSizeY;
        private final Random random = new Random();

        public HabitatCalculator(RasterLayer buildings, RasterLayer population, 
                                Rectangle2D worldBounds, int gridSizeX, int gridSizeY) {
            this.gridSizeX = gridSizeX;
            this.gridSizeY = gridSizeY;
            this.cellSize = Math.min(worldBounds.getWidth() / gridSizeX, 
                                    worldBounds.getHeight() / gridSizeY);
            this.minX = worldBounds.getMinX();
            this.minY = worldBounds.getMinY();
            this.suitabilityGrid = new double[gridSizeX][gridSizeY];
            calculateSuitability(buildings, population);
            this.cumulativeDistribution = buildCumulativeDistribution();
        }

        private void calculateSuitability(RasterLayer buildings, RasterLayer population) {
            double totalScore = 0.0;

            for (int i = 0; i < gridSizeX; i++) {
                for (int j = 0; j < gridSizeY; j++) {
                    double x = minX + (i + 0.5) * cellSize;
                    double y = minY + (j + 0.5) * cellSize;

                    // Get environmental values
                    double buildingDensity = Math.max(0, buildings.getValueAt(x, y));
//                    System.out.println("####> Building Density :"+buildingDensity);
                    double popDensity = Math.max(0, population.getValueAt(x, y));
                    System.out.println("----> Population Density :"+popDensity);

                    // Calculate suitability for Anopheles stephensi
                    // Prefers areas with both buildings AND people
                    double suitability = 0.0;

                    if (buildingDensity > 0.2 && popDensity > 0.1) {
                        // High suitability: urban core with both buildings and people
                        suitability = buildingDensity * 0.6 + (Math.min(popDensity, 100) / 100.0) * 0.4;
                    } else if (buildingDensity > 0.1 || popDensity > 0.05) {
                        // Medium suitability: suburban areas
                        suitability = (buildingDensity * 0.3 + (Math.min(popDensity, 50) / 50.0) * 0.2) * 0.5;
                    } else {
                        // Low suitability: rural/undeveloped
                        suitability = 0.01; // Small chance for exploration
                    }

                    // Add noise to avoid perfect patterns
                    suitability *= (0.9 + random.nextDouble() * 0.2);
                    suitabilityGrid[i][j] = suitability;
                    totalScore += suitability;
                }
            }

            // Normalize
            if (totalScore > 0) {
                for (int i = 0; i < gridSizeX; i++) {
                    for (int j = 0; j < gridSizeY; j++) {
                        suitabilityGrid[i][j] /= totalScore;
                    }
                }
            }
        }

        private double[] buildCumulativeDistribution() {
            double[] cdf = new double[gridSizeX * gridSizeY];
            double cumulative = 0.0;

            for (int i = 0; i < gridSizeX; i++) {
                for (int j = 0; j < gridSizeY; j++) {
                    cumulative += suitabilityGrid[i][j];
                    cdf[i * gridSizeY + j] = cumulative;
                }
            }

            // Ensure the last value is exactly 1.0
            if (cumulative > 0) {
                for (int i = 0; i < cdf.length; i++) {
                    cdf[i] /= cumulative;
                }
            }

            return cdf;
        }

        public double[] getRandomWeightedPoint() {
            double r = random.nextDouble();

            // Binary search for the cell index
            int index = java.util.Arrays.binarySearch(cumulativeDistribution, r);
            if (index < 0) {
                index = -(index + 1);
            }
            if (index >= cumulativeDistribution.length) {
                index = cumulativeDistribution.length - 1;
            }

            // Convert back to 2D coordinates
            int i = index / gridSizeY;
            int j = index % gridSizeY;

            // Random position within the cell
            double x = minX + (i + random.nextDouble()) * cellSize;
            double y = minY + (j + random.nextDouble()) * cellSize;

            return new double[]{x, y};
        }

        public double getSuitabilityAt(double x, double y) {
            int i = (int) ((x - minX) / cellSize);
            int j = (int) ((y - minY) / cellSize);

            if (i >= 0 && i < gridSizeX && j >= 0 && j < gridSizeY) {
                return suitabilityGrid[i][j];
            }
            return 0.0;
        }
    }
}