package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.services.RasterLoader;
import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App {
    
    private static SimulationEngine engine;
    private static ScheduledExecutorService monitorExecutor;

    public static void main(String[] args) {
        try {
            
            // Initialize with thread-safe components
            Project project = initializeProject();
            // Setup simulation
            setupSimulation(project);
            
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
    
    
    private static Project initializeProject() throws Exception {
        Project project = new Project("ThreadSafe_Simulation");
        LocalDateTime simStart = LocalDateTime.of(2024, 1, 1, 0, 0);
        // Load rasters
        RasterLayer pop = new RasterLayer("Population", 1, 1, 1);
        RasterLoader.loadRasterBulk(pop, "prepared_data/pop_addis.tiff");
        RasterLayer temp = new RasterLayer("Temperature", pop.getWidth(), pop.getHeight(), 3000);
        RasterLayer rain = new RasterLayer("Rainfall", pop.getWidth(), pop.getHeight(), 3000);
        RasterLayer build = new RasterLayer("Buildings", pop.getWidth(), pop.getHeight(), 1);

        RasterLoader.loadRasterBulk(build, "prepared_data/buildings_addis.tiff");
        // Load climate data (you'll need to implement the bulk NetCDF loader)
        // RasterLoader.loadNetCDFOptimized(temp, rain, "prepared_data/climate_2024_01.nc", 3000);
        project.addRasterLayer(pop);
        project.addRasterLayer(temp);
        project.addRasterLayer(rain);
        project.addRasterLayer(build);
        return project;
    }
    
    
    private static void setupSimulation(Project project) {
        TimeManager timeManager = new TimeManager(
            LocalDateTime.of(2024, 1, 1, 0, 0), 3000, 15);
        
        // Setup spatial registry with appropriate cell size
        double cellSize = 0.001; // ~100m at equator
        Rectangle2D worldBounds = new Rectangle2D.Double(38.70, 8.95, 0.1, 0.1);
        SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds, cellSize);
        
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
            SpatialRegistry spatialRegistry) {
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
    
}
