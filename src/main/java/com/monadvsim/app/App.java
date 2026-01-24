package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.netcdf.NetCDFClimateReader;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.services.RasterLoader;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {
    
    private static SimulationEngine engine;
    private static ScheduledExecutorService monitorExecutor;

    public static void main(String[] args) {
        try {
            
            LocalDateTime simStart = LocalDateTime.of(2024, 1, 1, 0, 0);
            NetCDFClimateReader climateReader = null;
            EnhancedTimeManager timeManager = null;
            
            // Initialize with thread-safe components
            Project project = initializeProject(simStart, climateReader,
                    timeManager);
            // Setup simulation
            setupSimulation(project, simStart,  climateReader,
                    timeManager);
            
            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutdown requested...");
                if (engine != null) {
                    engine.stop();
                }
                if (monitorExecutor != null) {
                    monitorExecutor.shutdown();
                }
            }));
            
            // Run simulation
            runSimulation(project);
        } catch (Exception e) {
            System.err.println("❌ Critical Simulation Failure:");
            e.printStackTrace();
        }
    } 
    
    
    private static Project initializeProject(LocalDateTime simStart, 
            NetCDFClimateReader climateReader,
            EnhancedTimeManager timeManager ) throws Exception {
        Project project = new Project("ThreadSafe_Simulation");
        
        // load netcdf file climate data
        loadTimeSeriesNetCDFData(project, simStart, climateReader, timeManager);
        // Load other rasters
        ProjectPersistenceService persistence = new ProjectPersistenceService();
        RasterLayer pop = new MemoryMappedRasterLayer("Population", 1, 1, 1);
        persistence.loadRasterData(pop, "prepared_data/pop_addis.tiff");
            
        RasterLayer build = new MemoryMappedRasterLayer("Buildings", 1, 1, 1);
        persistence.loadRasterData(build, "prepared_data/buildings_addis.tiff");

        project.addRasterLayer(pop);
        project.addRasterLayer(build);
   
        return project;
    }
    
    
    private static void loadTimeSeriesNetCDFData(Project project, 
            LocalDateTime simStart, NetCDFClimateReader climateReader,
            EnhancedTimeManager timeManager){
        // 2. Load NetCDF climate data first (to get time information)
        System.out.println("=== Loading Climate Data ===");

        try {
            // Open NetCDF file
            climateReader = new NetCDFClimateReader("prepared_data/climate_2024_01.nc");                
            // Detect climate variables
            NetCDFClimateReader.ClimateVariables climateVars = 
                climateReader.detectClimateVariables();
            // Get time values from NetCDF
            int netcdfTimeSteps = climateReader.getTimeValues().size();
            System.out.println("NetCDF has " + netcdfTimeSteps + " time steps");
            // Create time manager synchronized with NetCDF
            timeManager = new EnhancedTimeManager(
                simStart, netcdfTimeSteps, 15, climateReader);

            // Create raster layers with correct dimensions
            int[] tempShape = climateVars.temperature.getShape();
            int width = tempShape[2];  // Longitude dimension
            int height = tempShape[1]; // Latitude dimension

            RasterLayer tempLayer = new MemoryMappedRasterLayer(
                "Temperature", width, height, netcdfTimeSteps);
            RasterLayer rainLayer = new MemoryMappedRasterLayer(
                "Rainfall", width, height, netcdfTimeSteps);

            // Use enhanced loading
            ProjectPersistenceService persistence = new ProjectPersistenceService();
            persistence.loadClimateNetCDFEnhanced(
                tempLayer, rainLayer,
                "prepared_data/climate_2024_01.nc",
                simStart, netcdfTimeSteps, 15);

            project.addRasterLayer(tempLayer);
            project.addRasterLayer(rainLayer);

        } catch (IOException ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        } catch (Exception ex) {
            Logger.getLogger(App.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
                if (climateReader != null) {
                    climateReader.close();
                }
            }
        }
    
    
    private static void setupSimulation(Project project, LocalDateTime simStart, 
            NetCDFClimateReader climateReader,
            EnhancedTimeManager timeManager) {
        if (timeManager == null) {
            // Fallback to basic time manager
            timeManager = new EnhancedTimeManager(simStart, 3000, 15, null);
        }
        // Setup spatial registry with appropriate cell size
        double cellSize = 0.001; // ~100m at equator
        Rectangle2D worldBounds = new Rectangle2D.Double(38.70, 8.95, 0.1, 0.1);
        IncrementalSpatialRegistry spatialRegistry = new IncrementalSpatialRegistry(worldBounds, cellSize);
        // Create thread-safe rule engine
        ThreadSafeRuleEngine ruleEngine = new ThreadSafeRuleEngine();
        // Create agent layers with thread-safe containers
        AgentLayer habitatLayer = new AgentLayer("Water_Tanks", ruleEngine);
        AgentLayer mosquitoLayer = new AgentLayer("Mosquitoes", ruleEngine);
        // --- SCIENTIFIC RULE DEFINITIONS ---
        habitatLayer.addRule("rain > 0.05 && larvae > 0 && random < 0.1", "hatch", 10);
        mosquitoLayer.addRule("temp > 38 || age > 2500", "die", 100); // High priority
        mosquitoLayer.addRule("!isGravid && random < 0.3", "move_random", 50);
        mosquitoLayer.addRule("isGravid", "lay_eggs", 80);
        mosquitoLayer.addRule("!isGravid && pop > 0.4 && random < 0.05", "get_gravid", 60);
        
        project.addAgentLayer(habitatLayer);
        project.addAgentLayer(mosquitoLayer);
        project.setSpatialRegistry(spatialRegistry);
        
        // Create simulation engine
        engine = new SimulationEngine(project, timeManager, spatialRegistry);
    }
    
    
    private static void runSimulation(Project project) {
        System.out.println("=== Starting Thread-Safe Simulation ===");
        System.out.println("Project: " + project.getName());
        System.out.println("Available Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max Memory: " + Runtime.getRuntime().maxMemory() / (1024*1024) + " MB");
        System.out.println("=====================================\n");
        
        // Start monitoring thread
        startMonitoring(project);
        
        // Run simulation in separate thread
        Thread simulationThread = new Thread(() -> {
            try {
                engine.run();
            } catch (Exception e) {
                System.err.println("Simulation thread error: " + e.getMessage());
            }
        });
        simulationThread.setName("Simulation-Main");
        simulationThread.start();
        
        // Wait for simulation to complete
        try {
            simulationThread.join();
        } catch (InterruptedException e) {
            System.out.println("Main thread interrupted");
            Thread.currentThread().interrupt();
        }
        
        System.out.println("\n=== Simulation Complete ===");
    }
    
    private static void startMonitoring(Project project) {
        monitorExecutor = Executors.newScheduledThreadPool(1);
        
        monitorExecutor.scheduleAtFixedRate(() -> {
            try {
                printSystemStats(project);
            } catch (Exception e) {
                System.err.println("Monitoring error: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    private static void printSystemStats(Project project) {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        System.out.printf("[System] Memory: %.1f/%.1f MB (%.1f%%) | Threads: %d%n",
            usedMemory / (1024.0 * 1024.0),
            totalMemory / (1024.0 * 1024.0),
            (usedMemory * 100.0) / totalMemory,
            Thread.activeCount());
        
        if (engine != null) {
            Map<String, Object> state = engine.getState();
            System.out.printf("[Sim] Running: %s | Tick: %d | Agents: %d%n",
                state.get("running"), state.get("tick"), state.get("totalAgents"));
        }
    }
    
    
    private static void runSimulationWithMetrics(Project project, TimeManager timeManager,
            IncrementalSpatialRegistry spatialRegistry) {
        SimulationEngine engine = new SimulationEngine(project, timeManager, spatialRegistry);

        // Add performance monitoring
        long startTime = System.currentTimeMillis();
        long lastReportTime = startTime;
        int lastTick = 0;

        System.out.println("--- Starting Optimized Simulation ---");

        // Run in a separate thread to allow monitoring
        Thread engineThread = new Thread(() -> {
            try {
                engine.run();
            } catch (Exception e) {
                System.err.println("Engine error: " + e.getMessage());
            }
        });

        engineThread.start();

        // Monitor performance
        while (engineThread.isAlive()) {
            try {
                Thread.sleep(5000); // Report every 5 seconds

                long currentTime = System.currentTimeMillis();
                int currentTick = (int) timeManager.getTickCount();
                int ticksProcessed = currentTick - lastTick;

                if (ticksProcessed > 0) {
                    double timePerTick = (currentTime - lastReportTime) / (double) ticksProcessed;
                    System.out.printf("Performance: %.2f ms/tick | %d agents%n",
                            timePerTick, getTotalAgents(project));

                    lastReportTime = currentTime;
                    lastTick = currentTick;
                }

            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    
    private static int getTotalAgents(Project project) {
        return project.getAgentLayers().stream()
            .mapToInt(l -> l.getAgents().size())
            .sum();
    }
    
    private static void seedInitialPopulation(AgentLayer habitatLayer, 
                                             AgentLayer mosquitoLayer,
                                             RasterLayer buildings,
                                             RasterLayer population,
                                             IncrementalSpatialRegistry spatialRegistry) {
        System.out.println("Seeding initial population with immediate spatial registration...");
        
        Random rand = new Random();
        int tanksToSeed = 1000;
        int mosquitoesToSeed = 500;
        
        double minLon = 38.70, minLat = 8.95;
        double widthLon = 0.1, heightLat = 0.1;
        
        // Seed water tanks
        for (int i = 0; i < tanksToSeed; i++) {
            double rx = minLon + (widthLon * rand.nextDouble());
            double ry = minLat + (heightLat * rand.nextDouble());
            
            // Only in built-up areas
            if (buildings.getValueAt(rx, ry) > 0.5) {
                InertAgent tank = new InertAgent(rx, ry);
                tank.setLarvalCount(rand.nextInt(50) + 10);
                tank.setCapacity(200);
                
                // Add to layer AND register immediately in spatial registry
                habitatLayer.addAgent(tank);
                spatialRegistry.registerAgent(tank);
            }
        }
        
        // Seed mosquitoes
        for (int i = 0; i < mosquitoesToSeed; i++) {
            double rx = minLon + (widthLon * rand.nextDouble());
            double ry = minLat + (heightLat * rand.nextDouble());
            
            // Only in populated areas
            if (population.getValueAt(rx, ry) > 0.3) {
                LivingAgent mosquito = new LivingAgent(rx, ry);
                mosquito.setAge(rand.nextInt(500));
                mosquito.setGravid(rand.nextDouble() < 0.3);
                
                // Add to layer AND register immediately
                mosquitoLayer.addAgent(mosquito);
                spatialRegistry.registerAgent(mosquito);
            }
        }
        
        System.out.printf("Seeded: %d tanks, %d mosquitoes%n", 
            habitatLayer.getAgents().size(), mosquitoLayer.getAgents().size());
    }
    
}
