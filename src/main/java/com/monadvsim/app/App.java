package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.utils.ClimateDatasetManager;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class App {
    
    private static SimulationEngine simulationEngine;
    private static Thread simulationThread;
    
    
    public static void main(String[] args) {
        System.out.print("Hello world");
        test();
        try {
            // init snapshot output file
            File resultsDir = new File("results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
                System.out.println("Created results directory");
            }
            
            // Configure simulation
            test();
            
            // Run simualtion
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutdown signal received...");
                if (simulationEngine != null) {
                    simulationEngine.stop();
                }
                if (simulationThread != null && simulationThread.isAlive()) {
                    try {
                        simulationThread.join(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
            
            // Wait for simulation to complete if running in foreground
            if (simulationThread != null && simulationThread.isAlive()) {
                simulationThread.join();
            }
            
        } catch (Exception e) {
            System.err.println("Error in simulation: " + e.getMessage());
            e.printStackTrace();
        }
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
            String buildings_50_km_file = "prepared_data/buildings_50_km.tif";
            String population_50_km_file = "prepared_data/pop_density_50_km.tiff";
            
            RasterLayer elev = new MemoryMappedRasterLayer("Elevation", 1, 1, 1);
            RasterLayer buildings = new MemoryMappedRasterLayer("Buildings", 1, 1, 1);
            RasterLayer population = new MemoryMappedRasterLayer("Population", 1, 1, 1);
            try {
                persistenceService.loadRasterData(elev, elevation_50_km_file);
                persistenceService.loadRasterData(buildings, buildings_50_km_file);
                persistenceService.loadRasterData(population, population_50_km_file);
                project.addLayer(elev);
                project.addLayer(buildings);
                project.addLayer(population);
                System.out.println("✅ Static raster layers loaded successfully");
            } catch (Exception e) {
                System.err.println("⚠️ Failed to load raster layers: " + e.getMessage());
                // Create fallback data
                createFallbackRasters(elev, buildings, population);
                project.addLayer(elev);
                project.addLayer(buildings);
                project.addLayer(population);
            }
            
            
            // Load climate data
            System.out.println("Loading climate data...");
            String netcdfFile = "prepared_data/climate_2023_50_km_6.nc";
            ClimateDatasetManager climateManager = null;
            
            try {
                climateManager = persistenceService.loadClimateData(project, netcdfFile, timeManager);
                System.out.println("✅ Climate data loaded successfully");
            } catch (Exception e) {
                System.err.println("⚠️ Failed to load climate data: " + e.getMessage());
                System.out.println("Using fallback climate data...");
                // Create fallback climate layers
                createFallbackClimateLayers(project, timeManager);
            }
            
            // Create spatial registry
            Rectangle2D worldBounds;
            if (climateManager != null && climateManager.getBounds() != null) {
                worldBounds = climateManager.getBounds();
                System.out.printf("Using climate bounds: [%.2f, %.2f, %.2f, %.2f]%n",
                    worldBounds.getMinX(), worldBounds.getMinY(),
                    worldBounds.getMaxX(), worldBounds.getMaxY());
            } else {
                // Addis Ababa area bounds as fallback
                worldBounds = new Rectangle2D.Double(38.70, 8.95, 0.1, 0.1);
                System.out.println("Using fallback bounds for Addis Ababa area");
            }
            
            SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds, 0.001); // 0.001 degree, Smaller cells for better spatial resolution
            project.setSpatialRegistry(spatialRegistry);
            
            
            // Create Rule engine and lifecycle manager
            RuleEngine ruleEngine = new RuleEngine();
            AgentLifecycleManager lifecycleManager = new AgentLifecycleManager(spatialRegistry);
            
            // Create agent layers
            AgentLayer mosquitoLayer = new AgentLayer("Mosquitoes", ruleEngine, lifecycleManager);
            AgentLayer habitatLayer = new AgentLayer("WaterTanks", ruleEngine, lifecycleManager);
            
            // Add rules for mosquitoes
            // Note: Temperature is in Kelvin in ERA5 data (0°C = 273.15K, 25°C = 298.15K)
            mosquitoLayer.addRule(
                "temperature > 298.15 && precipitation < 0.0001", // ~25°C and dry
                "move_random",
                1
            );
            mosquitoLayer.addRule(
                "temperature > 298.15 && precipitation < 0.0001", // ~25°C and dry
                "get_gravid",
                2
            );
            mosquitoLayer.addRule(
                "temperature > 298.15 && precipitation > 0.001", // ~25°C and rainy
                "lay_eggs",
                3
            );
            mosquitoLayer.addRule(
                "temperature < 283.15 || temperature > 313.15", // <10°C or >40°C
                "die",
                10 // High priority - death should happen first
            );
            
            mosquitoLayer.addRule(
                "age > 1000",
                "die",
                9 // Die from old age
            );
            
            // Add rules for water tanks (habitats)
            habitatLayer.addRule(
                "temperature > 293.15", // >20°C
                "hatch",
                1
            );
            
            // Add layers to project
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
            
            tokens.add("buildings");
            layerNames.add("Buildings");
            
            tokens.add("elevation");
            layerNames.add("Elevation");
            
            project.setTokens(tokens);
            project.setLayerNames(layerNames);
            
            // Seed initial population
            System.out.println("Seeding initial population...");
            seedInitialPopulation(habitatLayer, 
                    mosquitoLayer, buildings, population, spatialRegistry);
            
            System.out.printf("Initial agents: %d tanks, %d mosquitoes%n",
                habitatLayer.getAgents().size(), mosquitoLayer.getAgents().size());
            
            // Create and start simulation engine
            System.out.println("Creating simulation engine...");
            simulationEngine = new SimulationEngine(project, timeManager, spatialRegistry);
            
            // Run simulation in background thread
            simulationThread = new Thread(() -> {
                try {
                    simulationEngine.run();
                    
                    // After simulation completes, export results
                    System.out.println("Simulation completed, exporting results...");
                    String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                    String csvFile = String.format("results/simulation_results_%s.csv", timestamp);
                    
                    persistenceService.exportToCSV(project, csvFile);
                    System.out.println("✅ Results exported to: " + csvFile);
                    
                    // Print final statistics
                    printFinalStatistics(project, spatialRegistry);
                    
                } catch (Exception e) {
                    System.err.println("Error in simulation thread: " + e.getMessage());
                    e.printStackTrace();
                }
            });
            
            simulationThread.setName("Simulation-Thread");
            simulationThread.setDaemon(false);
            simulationThread.start();
            
            System.out.println("Simulation started! Press Ctrl+C to stop.");
            
            // 15. Monitor simulation progress
            monitorSimulation(simulationEngine, project);
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    private static void createFallbackRasters(RasterLayer elev, RasterLayer buildings, RasterLayer population) {
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
                double elevValue = 2000 + Math.sin(x * 0.1) * Math.cos(y * 0.1) * 500;
                elev.setData(0, x, y, elevValue);
                
                // Buildings concentrated in center
                double distFromCenter = Math.sqrt(Math.pow(x-50, 2) + Math.pow(y-50, 2));
                double buildingValue = Math.max(0, 1.0 - distFromCenter / 50.0);
                buildings.setData(0, x, y, buildingValue);
                
                // Population density
                double popValue = Math.max(0, 0.8 - distFromCenter / 60.0) + rand.nextDouble() * 0.2;
                population.setData(0, x, y, popValue);
            }
        }
    }
    
    
    private static void createFallbackClimateLayers(Project project, TimeManager timeManager) {
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
        
        System.out.println("✅ Created fallback climate layers");
    }
    
    
    private static void seedInitialPopulation(AgentLayer habitatLayer, 
                                             AgentLayer mosquitoLayer,
                                             RasterLayer buildings,
                                             RasterLayer population,
                                             SpatialRegistry spatialRegistry) {
        System.out.println("Seeding initial population with immediate spatial registration...");
        
        Random rand = new Random();
        int tanksToSeed = 100;
        int mosquitoesToSeed = 50;
        
        double minLon = 38.70, minLat = 8.95;
        double widthLon = 0.1, heightLat = 0.1;
        
        // Seed water tanks
        for (int i = 0; i < tanksToSeed; i++) {
            double rx = minLon + (widthLon * rand.nextDouble());
            double ry = minLat + (heightLat * rand.nextDouble());
            
            // Only in built-up areas
            if (buildings.getValueAt(rx, ry) > 0.1) {
                InertAgent tank = new InertAgent(rx, ry);
                tank.setLarvalCount(rand.nextInt(20) + 1);
                tank.setEggCount(rand.nextInt(50) + 10);
                tank.setCapacity(200);
                tank.setWaterVolume(rand.nextDouble() * 100.0);
                
                habitatLayer.addAgent(tank);
                spatialRegistry.registerAgent(tank);
            }
        }
        
        // Seed mosquitoes
        for (int i = 0; i < mosquitoesToSeed; i++) {
            double rx = minLon + (widthLon * rand.nextDouble());
            double ry = minLat + (heightLat * rand.nextDouble());
            
            // Only in populated areas
            if (population.getValueAt(rx, ry) > 0.1) {
                LivingAgent mosquito = new LivingAgent(rx, ry);
                mosquito.setAge(rand.nextInt(500));
                mosquito.setGravid(rand.nextDouble() < 0.3);
                mosquito.setStage(LifecycleStage.ADULT);
                mosquito.setEnergy(0.5 + rand.nextDouble() * 0.5);
                
                mosquitoLayer.addAgent(mosquito);
                spatialRegistry.registerAgent(mosquito);
            }
        }
    }
    
    
    private static void monitorSimulation(SimulationEngine engine, Project project) {
        // Monitor simulation progress in main thread
        while (engine != null) {
            try {
                Thread.sleep(5000); // Check every 5 seconds
                
                Map<String, Object> state = engine.getState();
                boolean running = (Boolean) state.get("running");
                
                if (!running) {
                    break;
                }
                
                // Print progress
                long tick = (Long) state.get("tick");
                int totalAgents = (Integer) state.get("totalAgents");
                double avgTickTime = (Double) state.get("avgTickTime");
                
                System.out.printf("[Monitor] Tick: %d | Agents: %d | Avg Tick Time: %.2f ms%n",
                    tick, totalAgents, avgTickTime);
                    
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    
    private static void printFinalStatistics(Project project, SpatialRegistry spatialRegistry) {
        System.out.println("\n=== FINAL SIMULATION STATISTICS ===");
        
        // Agent statistics
        int totalAgents = 0;
        for (AgentLayer layer : project.getAgentLayers()) {
            int layerAgents = layer.getAgents().size();
            System.out.printf("%s: %,d agents%n", layer.getName(), layerAgents);
            totalAgents += layerAgents;
        }
        System.out.printf("Total agents: %,d%n", totalAgents);
        
        // Spatial registry statistics
        Map<String, Object> spatialStats = spatialRegistry.getStatistics();
        System.out.printf("\nSpatial Registry Statistics:%n");
        System.out.printf("  Grid cells: %d%n", spatialStats.get("gridCells"));
        System.out.printf("  Total inserts: %d%n", spatialStats.get("totalInserts"));
        System.out.printf("  Total removes: %d%n", spatialStats.get("totalRemoves"));
        System.out.printf("  Total updates: %d%n", spatialStats.get("totalUpdates"));
        System.out.printf("  Max agents per cell: %d%n", spatialStats.get("maxAgentsPerCell"));
        System.out.printf("  Cache size: %d%n", spatialStats.get("cacheSize"));
        
        // Memory usage
        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long totalMB = runtime.totalMemory() / (1024 * 1024);
        long maxMB = runtime.maxMemory() / (1024 * 1024);
        
        System.out.printf("\nMemory Usage:%n");
        System.out.printf("  Used: %d MB%n", usedMB);
        System.out.printf("  Total: %d MB%n", totalMB);
        System.out.printf("  Max: %d MB%n", maxMB);
        System.out.println("====================================\n");
    }
}