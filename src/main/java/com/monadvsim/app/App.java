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

public class App {
    
    private static SimulationEngine simulationEngine;
    private static Thread simulationThread;
    // Helper method to track simulation start time
    private static long simulationStartTime = System.currentTimeMillis();
    private static Project currentProject;
    private static SpatialRegistry currentSpatialRegistry;
      
    
    public static void main(String[] args) {
        System.out.println("Hello world - Starting Multi-Agent Simulation System");

        try {
            // Initialize snapshot output directory
            File resultsDir = new File("results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
                System.out.println("📁 Created results directory");
            }

            // Configure and run simulation
            test();

            // Simple shutdown hook - just stop the engine
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutdown signal received...");
                if (simulationEngine != null) {
                    simulationEngine.stop();
                }
            }));

            // Wait for simulation to complete if running in foreground
            if (simulationThread != null && simulationThread.isAlive()) {
                simulationThread.join();
            }

            System.out.println("Simulation completed successfully!");

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
            currentProject = project; // Store for later use
            
            // 1 degree of latitude is about 111 km
            project.setDefaultAgentSearchRadius(0.0005); // which is about 0.0005 * 111km = 55m
            project.setDefaultAgentStep(0.00005); // about ~5.5 meters per move
            project.setDefaultBirthRate(20);
            project.setDefaultMaxAgentAge(2880); // Aging death (after 30 days at 15-min intervals: 30*24*4 = 2880 ticks) 
            project.setDefaultHatchingProbability(0.8);
            
            // Set up time manager (simulate 30 days at 15-minute intervals)
            LocalDateTime startDate = LocalDateTime.of(2023, 6, 1, 0, 0);
            int totalTicks = 2 * 30 * 24 * 4; // 4 * 30 days * 24 hours * 4 (15-min intervals)
            TimeManager timeManager = new TimeManager(startDate, totalTicks, 15);
            
            ProjectPersistenceService persistenceService = new ProjectPersistenceService();
            
            // load static rasters
            String elevation_50_km_file = "prepared_data/elevation_5_km.tiff";
            String buildings_50_km_file = "prepared_data/buildings_100_km.tif";
            String population_50_km_file = "prepared_data/population_density_100m.tif";
            
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
                climateManager = persistenceService.loadClimateData(project, netcdfFile, timeManager);
                System.out.println("Climate data loaded successfully");
            } catch (Exception e) {
                System.err.println("Failed to load climate data: " + e.getMessage());
                System.out.println("Using fallback climate data...");
                // Create fallback climate layers
                createFallbackClimateLayers(project, timeManager);
            }
            
            // Create spatial registry
            Rectangle2D worldBounds = createWorldBounds(
                                9.0265, // latitude
                                38.7311, // longitude
                                50 // BBuffer in km
                            );

            SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds, 0.001); // 0.001 degree, Smaller cells for better spatial resolution
            project.setSpatialRegistry(spatialRegistry);
            currentSpatialRegistry = spatialRegistry; // Store for later use
            
            
            // Create Rule engine and lifecycle manager
            RuleEngine ruleEngine = new RuleEngine();
            AgentLifecycleManager lifecycleManager = new AgentLifecycleManager(spatialRegistry);
            
            // Create agent layers
            AgentLayer mosquitoLayer = new AgentLayer("Mosquitoes", ruleEngine, lifecycleManager);
            AgentLayer habitatLayer = new AgentLayer("WaterTanks", ruleEngine, lifecycleManager);
            
            mosquitoLayer.setLifecycleManager(lifecycleManager);
            habitatLayer.setLifecycleManager(lifecycleManager);
            
            // Add rules for mosquitoes
            // Note: Temperature is in Kelvin in ERA5 data (0°C = 273.15K, 25°C = 298.15K)
            // 1. ADULT FEEDING (Only at night/dusk for Anopheles)
            // Note: Anopheles stephensi are primarily nocturnal biters.
            mosquitoLayer.addRule(
                "stage == 'ADULT' && hour >= 18 && hour <= 6 && energy < 0.5", 
                "feed", 
                5
            );

            // 2. DIGESTION & EGG DEVELOPMENT (The "Gravid" State)
            // Trigger: If fed and temperature is optimal (speeds up metabolism)
            mosquitoLayer.addRule(
                "stage == 'ADULT' && energy != 0 && age > 192", 
                "get_gravid", 
                4
            );

            // 3. EGG LAYING (Precipitation isn't strictly necessary for stephensi)
            // Unlike other species, they use man-made containers. 
            // humidity > 60% is a better trigger than rain.
            mosquitoLayer.addRule(
                "gravid == true && temperature > 293.15", 
                "lay_eggs", 
                6
            );

            // 4. LARVAL GROWTH (Optimized for 7-10 days)
            mosquitoLayer.addRule(
                "stage == 'LARVA' && age > 768 && temperature > 295.15", 
                "pupate", 
                3
            );

            // 5. PUPAL EMERGENCE (Fast: ~48 hours)
            mosquitoLayer.addRule(
                "stage == 'PUPA' && age > 192", 
                "emerge", 
                3
            );

            // 6. THERMAL DEATH (A. stephensi is hardy, but >40°C is lethal)
            mosquitoLayer.addRule(
                "temperature < 283.15 || temperature > 313.15", 
                "die", 
                10 
            );
            
            // habitat layer
            habitatLayer.addRule(
                "stage == 'EGG' && age > 240 && temperature > 287.15 && temperature < 306.15", 
                "hatch",
                1
            );

            habitatLayer.addRule(
                "stage == 'EGG' && temperature > 308.15", 
                "die", 
                2
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

            elev.setBounds(minLon, maxLon, minLat, maxLat);
            buildings.setBounds(minLon, maxLon, minLat, maxLat);
            population.setBounds(minLon, maxLon, minLat, maxLat);

            // If you have climate layers in a list:
            for (Layer layer : project.getLayers()) {
                if (layer instanceof RasterLayer rl) {
                    rl.setBounds(minLon, maxLon, minLat, maxLat);
                }
            }
            
            // Seed initial population
            System.out.println("Seeding initial population...");
            seedInitialPopulation(habitatLayer, 
                    mosquitoLayer, buildings, population, spatialRegistry,
                    worldBounds);
            
            System.out.printf("Initial agents: %d tanks, %d mosquitoes%n",
                habitatLayer.getAgents().size(), mosquitoLayer.getAgents().size());
            
            // Create and start simulation engine
            System.out.println("Creating simulation engine...");
            simulationEngine = new SimulationEngine(project, timeManager, spatialRegistry);

            // Run simulation in background thread
            simulationThread = new Thread(() -> {
                try {
                    simulationEngine.run();

                    // The engine.run() method will call cleanup() which now handles
                    // final snapshot export and merging automatically

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
                                         SpatialRegistry spatialRegistry,
                                         Rectangle2D worldBounds) {
        System.out.println("Seeding initial population...");

        Random rand = new Random();
        int tanksToSeed = 5000;  // Increased
        int mosquitoesToSeed = 150000;  // Reduced from 50000 for better distribution

        double minLon = worldBounds.getMinX(), minLat = worldBounds.getMinY();
        double widthLon = worldBounds.getWidth(), heightLat = worldBounds.getHeight();

        // Seed water tanks - spread more evenly
        int tanksPlaced = 0;
        while (tanksPlaced < tanksToSeed) {
            double rx = minLon + (widthLon * rand.nextDouble());
            double ry = minLat + (heightLat * rand.nextDouble());

            // More relaxed placement criteria
            double buildingDensity = buildings.getValueAt(rx, ry);
            if (buildingDensity > 0.01 || rand.nextDouble() < 0.3) { // 30% chance even in low density
                InertAgent tank = new InertAgent(rx, ry);
                tank.setLarvalCount(rand.nextInt(50) + 1);  // More larvae
                tank.setEggCount(rand.nextInt(100) + 20);   // More eggs
                tank.setCapacity(500);  // Larger capacity
                tank.setWaterVolume(30 + rand.nextDouble() * 70.0);  // More water

                habitatLayer.addAgent(tank);
                spatialRegistry.registerAgent(tank);
                tanksPlaced++;
            }
        }

        // Seed mosquitoes - spread more evenly
        int mosquitoesPlaced = 0;
        while (mosquitoesPlaced < mosquitoesToSeed) {
            double rx = minLon + (widthLon * rand.nextDouble());
            double ry = minLat + (heightLat * rand.nextDouble());

            // More relaxed placement
            double popDensity = population.getValueAt(rx, ry);
            if (popDensity > 0.01 || rand.nextDouble() < 0.4) { // 40% chance even in low density

                // Randomize life stages
                LifecycleStage stage;
                if (rand.nextDouble() < 0.7) { // 70% adults
                    stage = LifecycleStage.ADULT;
                } else if (rand.nextDouble() < 0.5) { // 15% larvae
                    stage = LifecycleStage.LARVA;
                } else { // 15% pupae
                    stage = LifecycleStage.PUPA;
                }

                LivingAgent mosquito = new LivingAgent(rx, ry);
                mosquito.setAge(rand.nextInt(1000));
                mosquito.setGravid(rand.nextDouble() < 0.2);  // 20% gravid initially
                mosquito.setStage(stage);
                mosquito.setEnergy(0.3 + rand.nextDouble() * 0.7);

                // Set development timers based on stage
                if (stage == LifecycleStage.LARVA) {
                    mosquito.setDaysToPupa(5 + rand.nextInt(3));
                } else if (stage == LifecycleStage.PUPA) {
                    mosquito.setDaysToAdult(2 + rand.nextInt(2));
                }

                mosquitoLayer.addAgent(mosquito);
                spatialRegistry.registerAgent(mosquito);
                mosquitoesPlaced++;
            }
        }

        System.out.printf("Seeded: %d tanks, %d mosquitoes (various stages)%n", 
                         tanksPlaced, mosquitoesPlaced);
    }
    
    
    private static void monitorSimulation(SimulationEngine engine, Project project) {
        // Monitor simulation progress in main thread
        while (engine != null) {
            try {
                Thread.sleep(5000); // Check every 5 seconds

                Map<String, Object> state = engine.getState();
                boolean running = (Boolean) state.get("running");

                if (!running) {
                    System.out.println("Simulation has stopped normally");
                    break;
                }

                // Print progress
                long tick = (Long) state.get("tick");
                int totalAgents = (Integer) state.get("totalAgents");
                double avgTickTime = (Double) state.get("avgTickTime");

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
                    System.err.println("CRITICAL: Tick time too slow (" + avgTickTime + "ms), simulation may be hanging");
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
    
    
    public static Rectangle2D createWorldBounds(double centerLat, double centerLon, double bufferKm) {
        // 1 degree is roughly 111.32 km at the equator
        double bufferDegrees = bufferKm / 111.32;
        double minLon = centerLon - bufferDegrees;
        double minLat = centerLat - bufferDegrees;
        double sizeDegrees = bufferDegrees * 2;
        return new Rectangle2D.Double(minLon, minLat, sizeDegrees, sizeDegrees);
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
    
    
    // Update the startWatchdog method to include project and spatialRegistry
    private static void startWatchdog(SimulationEngine engine, Thread simulationThread, 
                                     Project project, SpatialRegistry spatialRegistry) {
        Thread watchdog = new Thread(() -> {
            try {
                int stuckCount = 0;
                long lastTick = 0;
                long lastTickTime = System.currentTimeMillis();
                
                while (simulationThread.isAlive() && !simulationThread.isInterrupted()) {
                    Thread.sleep(10000); // Check every 10 seconds
                    
                    try {
                        Map<String, Object> state = engine.getState();
                        long currentTick = (Long) state.get("tick");
                        long currentTime = System.currentTimeMillis();
                        
                        if (currentTick == lastTick) {
                            stuckCount++;
                            long stuckSeconds = (currentTime - lastTickTime) / 1000;
                            
                            System.err.println("WARNING: Simulation may be stuck at tick " + currentTick + 
                                             " (stuck for " + stuckSeconds + " seconds, count: " + stuckCount + ")");
                            
                            if (stuckCount > 3) { // Stuck for 30+ seconds
                                System.err.println("CRITICAL: Simulation appears stuck for over 30 seconds, forcing shutdown");
                                
                                // Save statistics before shutting down
                                saveFinalStatisticsToFile(project, spatialRegistry, engine);
                                SnapshotMerger.mergeAfterSimulation();
                                
                                engine.stop();
                                simulationThread.interrupt();
                                
                                // Give it a chance to shut down gracefully
                                Thread.sleep(5000);
                                
                                if (simulationThread.isAlive()) {
                                    System.err.println("Simulation thread still alive, forcing termination");
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
                        System.err.println("Error in watchdog while checking state: " + e.getMessage());
                        if (stuckCount++ > 5) {
                            System.err.println("CRITICAL: Cannot retrieve simulation state, forcing shutdown");
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
    private static void saveFinalStatisticsToFile(Project project, SpatialRegistry spatialRegistry, SimulationEngine engine) {
        if (project == null || spatialRegistry == null) {
            System.err.println("Cannot save statistics: project or spatial registry is null");
            return;
        }
        
        try {
            // Create results directory
            File resultsDir = new File("results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }
            
            // Generate filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeProjectName = project.getName().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String filename = String.format("results/%s_final_statistics_%s.txt", safeProjectName, timestamp);
            
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
                writer.println("Statistics saved to: " + new File(filename).getAbsolutePath());
                
                System.out.println("Final statistics saved to: " + filename);
                
            } catch (Exception e) {
                System.err.println("Error writing statistics file: " + e.getMessage());
                // Fallback to console
                System.out.println("\nFailed to write statistics file, printing to console:");
                printFinalStatistics(project, spatialRegistry);
            }
            
        } catch (Exception e) {
            System.err.println("Error saving final statistics: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    private static long getSimulationStartTime() {
        return simulationStartTime;
    }
    
    
    private static void testExportDirectly() {
        System.out.println("=== Testing Export Directly ===");

        try {
            // Create a simple test project
            Project testProject = new Project("Test Export");

            // Create test layers
            AgentLayer testLayer = new AgentLayer("TestAgents", null, null);

            // Add some test agents
            Random rand = new Random();
            for (int i = 0; i < 100; i++) {
                LivingAgent agent = new LivingAgent(
                    38.7 + rand.nextDouble() * 0.1,
                    8.95 + rand.nextDouble() * 0.1
                );
                agent.setAge(rand.nextInt(1000));
                agent.setEnergy(rand.nextDouble());
                testLayer.addAgent(agent);
            }

            testProject.addLayer(testLayer);

            // Test export
            ProjectPersistenceService service = new ProjectPersistenceService();
            String testFile = "results/test_export.csv";

            System.out.println("Testing export to: " + testFile);
            service.exportToCSV(testProject, testFile, 999);

            File file = new File(testFile);
            if (file.exists()) {
                System.out.println("Test export successful! File size: " + file.length() + " bytes");
            } else {
                System.err.println("Test export failed - file not created!");
            }

        } catch (Exception e) {
            System.err.println("Test export failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}