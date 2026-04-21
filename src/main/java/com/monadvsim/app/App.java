package com.monadvsim.app;

import com.monadvsim.app.models.config.SimulationConfig;
import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.utils.ConfigLoader;
import com.monadvsim.app.models.utils.SnapshotMerger;
import com.monadvsim.app.models.utils.VectorBoundsLoader;

import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Coordinate;

/**
 * Main Application Entry Point
 *
 * Configures project settings, loads data (rasters, climate NetCDF) and seeds initial agent populations.
 * All configuration is read from a single YAML file (config/simulation.yaml).
 *
 * @author void
 */
public class App {

    private static SimulationEngine simulationEngine;
    private static Thread simulationThread;
    private static long simulationStartTime = System.currentTimeMillis();
    private static Project project;
    private static SpatialRegistry spatialRegistry;
    private static TimeManager timeManager;
    private static Rectangle2D worldBounds;
    private static SimulationConfig config;

    public static void main(String[] args) {
        System.out.println("Starting Multi-Agent Simulation System");

        try {
            // 1. Load configuration from YAML
            config = ConfigLoader.loadFromYaml("/config/simulation.yaml");
            System.out.println("Configuration loaded from /config/simulation.yaml");

            // 2. Create results directory
            File resultsDir = new File("results");
            if (!resultsDir.exists()) resultsDir.mkdirs();

            // 3. Setup time manager from config
            LocalDateTime startDate = LocalDateTime.parse(config.time.startDateTime);
            timeManager = new TimeManager(startDate, config.time.totalTicks, config.time.tickMinutes);

            // 4. World bounds from study site file (shapefile)
            worldBounds = getWorldBoundsFromStudySite(config.files.studySite);
            System.out.println("World bounds from study site: " + worldBounds);

            // 5. Load study area geometry for seeding constraints
            Geometry studyAreaGeometry = null;
            try {
                studyAreaGeometry = VectorBoundsLoader.getStudyAreaGeometry(config.files.studySite);
                System.out.println("Loaded study area polygon for seeding constraints.");
            } catch (Exception e) {
                System.err.println("Could not load study area geometry: " + e.getMessage());
                System.err.println("Seeding will use rectangular bounds only.");
            }

            // 6. Spatial registry with grid cell size from config
            spatialRegistry = new SpatialRegistry(worldBounds, config.gridCellSizeDegrees);

            // 7. Create project and configure (pass geometry)
            project = new Project("Mosquito Simulation");
            configureSimulation(project, spatialRegistry, timeManager, worldBounds, config, studyAreaGeometry);

            // 8. Start simulation
            createAndStartSimulation(project, timeManager, spatialRegistry);

            // 9. Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutdown signal received...");
                if (simulationEngine != null) simulationEngine.stop();
            }));

            // 10. Wait for completion
            if (simulationThread != null && simulationThread.isAlive()) simulationThread.join();

            // 11. Final statistics and merge
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine);
            SnapshotMerger.mergeAfterSimulation();

            System.out.println("Simulation completed successfully!");

        } catch (InterruptedException e) {
            System.err.println("Error in simulation: " + e.getMessage());
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine);
        }
    }

    /**
     * Reads the bounding box from a shapefile or QGIS project.
     * Falls back to a default centroid+radius if reading fails.
     */
    private static Rectangle2D getWorldBoundsFromStudySite(String studySitePath) {
        try {
            // Try to read as shapefile first
            if (studySitePath.toLowerCase().endsWith(".shp")) {
                return VectorBoundsLoader.getBoundsFromShapefile(studySitePath);
            } else if (studySitePath.toLowerCase().endsWith(".qgz") || studySitePath.toLowerCase().endsWith(".qgs")) {
                // QGIS project – we cannot directly parse; recommend user provides a shapefile.
                System.err.println("QGIS project files are not directly readable for bounds. " +
                        "Please extract the bounding box manually or provide a shapefile.");
                // Fallback: try to find a .shp with same name?
                String shpPath = studySitePath.replaceAll("\\.qgz$", ".shp").replaceAll("\\.qgs$", ".shp");
                File shpFile = new File(shpPath);
                if (shpFile.exists()) {
                    return VectorBoundsLoader.getBoundsFromShapefile(shpPath);
                } else {
                    throw new IOException("No shapefile found for QGIS project.");
                }
            } else {
                throw new IOException("Unsupported file type for study site: " + studySitePath);
            }
        } catch (Exception e) {
            System.err.println("Could not read study site file: " + e.getMessage());
            System.err.println("Using fallback bounds (Dire Dawa centroid + 5 km buffer).");
            // Fallback to centroid + buffer (Dire Dawa approximate coordinates)
            return VectorBoundsLoader.createFallbackBounds(41.8562, 9.6041, 5.0);
        }
    }

    private static void configureSimulation(Project project,
                                            SpatialRegistry spatialRegistry,
                                            TimeManager timeManager,
                                            Rectangle2D worldBounds,
                                            SimulationConfig config,
                                            Geometry studyAreaGeometry) {  // NEW: added geometry parameter
        System.out.println("Configuring project from YAML");
        try {
            // ---- Project defaults from config ----
            project.setDefaultAgentSearchRadius(config.project.defaultAgentSearchRadius);
            project.setDefaultAgentStep(config.project.defaultAgentStep);
            project.setDefaultMaxAgentAge(config.project.defaultMaxAgentAge);

            // ---- Species parameters and lifecycle model ----
            SpeciesParameters params = new SpeciesParameters();
            copySpeciesParams(params, config.species);
            LifecycleModel lifecycleModel = new LifecycleModel(params, config.time.tickMinutes);
            project.setLifecycleModel(lifecycleModel);

            // ---- Static raster layers ----
            ProjectPersistenceService persistenceService = new ProjectPersistenceService();
            RasterLayer elev = new MemoryMappedRasterLayer("Elevation", 1, 1, 1);
            RasterLayer buildings = new MemoryMappedRasterLayer("Buildings", 1, 1, 1);
            RasterLayer population = new MemoryMappedRasterLayer("Population", 1, 1, 1);

            try {
                persistenceService.loadRasterData(elev, config.files.elevation);
                persistenceService.loadRasterData(buildings, config.files.buildings);
                persistenceService.loadRasterData(population, config.files.population);
                project.addLayer(elev);
                project.addLayer(buildings);
                project.addLayer(population);
                System.out.println("Static raster layers loaded successfully");
            } catch (Exception e) {
                System.err.println("Failed to load raster layers: " + e.getMessage());
                createFallbackRasters(elev, buildings, population);
                project.addLayer(elev);
                project.addLayer(buildings);
                project.addLayer(population);
            }

            // ---- Climate data ----
            System.out.println("Loading climate data...");
            try {
                persistenceService.loadClimateData(project, config.files.climateNetCDF, timeManager);
                System.out.println("Climate data loaded successfully");
            } catch (Exception e) {
                System.err.println("Failed to load climate data: " + e.getMessage());
                System.out.println("Using fallback climate data...");
                createFallbackClimateLayers(project, timeManager);
            }

            project.setSpatialRegistry(spatialRegistry);

            // ---- Rule engine & lifecycle manager ----
            RuleEngine ruleEngine = new RuleEngine();
            AgentLifeCycleManager lifecycleManager = new AgentLifeCycleManager(spatialRegistry);

            // ---- Agent layers (created from config) ----
            for (var layerConfig : config.agentLayers) {
                AgentLayer layer = new AgentLayer(layerConfig.name, ruleEngine, lifecycleManager);
                layer.setLifecycleManager(lifecycleManager);
                layer.setLifecycleModel(lifecycleModel);
                for (var rule : layerConfig.rules) {
                    layer.addRule(rule.condition, rule.action, rule.priority);
                }
                project.addLayer(layer);
                System.out.printf("Added agent layer '%s' with %d rules%n", layerConfig.name, layerConfig.rules.size());
            }

            // ---- Tokens for rule engine (loaded from YAML) ----
            List<String> tokens = new ArrayList<>();
            List<String> layerNames = new ArrayList<>();
            for (var tokenMapping : config.tokens) {
                tokens.add(tokenMapping.token);
                layerNames.add(tokenMapping.layer);
            }
            project.setTokens(tokens);
            project.setLayerNames(layerNames);
            System.out.printf("Loaded %d tokens from YAML%n", tokens.size());

            // ---- Standardise geographic bounds for all raster layers ----
            double minLon = worldBounds.getMinX();
            double maxLon = worldBounds.getMaxX();
            double minLat = worldBounds.getMinY();
            double maxLat = worldBounds.getMaxY();
            for (Layer layer : project.getLayers()) {
                if (layer instanceof RasterLayer rl) {
                    rl.setBounds(minLon, maxLon, minLat, maxLat);
                }
            }

            // ---- Seed initial population using config seeding parameters ----
            System.out.println("Seeding initial agents population...");
            seedInitialPopulation(project, config.seeding, buildings, population, spatialRegistry, worldBounds, studyAreaGeometry);

            int totalTanks = project.getAgentLayers().stream()
                    .filter(l -> l.getName().equals("WaterTanks"))
                    .findFirst().map(l -> l.getAgents().size()).orElse(0);
            int totalMosquitoes = project.getAgentLayers().stream()
                    .filter(l -> l.getName().equals("Mosquitoes"))
                    .findFirst().map(l -> l.getAgents().size()).orElse(0);
            System.out.printf("Initial agents: %d tanks, %d mosquitoes%n", totalTanks, totalMosquitoes);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------------

    private static void copySpeciesParams(SpeciesParameters target, SimulationConfig.SpeciesParameters source) {
        target.fecundityA = source.fecundity_a;
        target.fecundityB = source.fecundity_b;
        target.fecundityTmax = source.fecundity_Tmax;
        target.fecundityC = source.fecundity_c;
        target.eggDevA = source.egg_dev_a;
        target.eggDevB = source.egg_dev_b;
        target.eggDevC = source.egg_dev_c;
        target.larvaDevA = source.larva_dev_a;
        target.larvaDevB = source.larva_dev_b;
        target.larvaDevC = source.larva_dev_c;
        target.pupaDevA = source.pupa_dev_a;
        target.pupaDevB = source.pupa_dev_b;
        target.pupaDevC = source.pupa_dev_c;
        target.eggSurvivalAmp = source.egg_survival_amp;
        target.eggSurvivalMean = source.egg_survival_mean;
        target.eggSurvivalSigma = source.egg_survival_sigma;
        target.larvaSurvivalAmp = source.larva_survival_amp;
        target.larvaSurvivalMean = source.larva_survival_mean;
        target.larvaSurvivalSigma = source.larva_survival_sigma;
        target.pupaSurvivalAmp = source.pupa_survival_amp;
        target.pupaSurvivalMean = source.pupa_survival_mean;
        target.pupaSurvivalSigma = source.pupa_survival_sigma;
        target.adultMortA = source.adult_mort_a;
        target.adultMortB = source.adult_mort_b;
        target.adultMortC = source.adult_mort_c;
    }

    private static void seedInitialPopulation(Project project,
                                              SimulationConfig.SeedingConfig seeding,
                                              RasterLayer buildings,
                                              RasterLayer population,
                                              SpatialRegistry spatialRegistry,
                                              Rectangle2D worldBounds,
                                              Geometry studyAreaGeometry) {  // NEW: added geometry parameter
        Random rand = new Random();

        AgentLayer habitatLayer = project.getAgentLayers().stream()
                .filter(l -> l.getName().equals("WaterTanks")).findFirst().orElse(null);
        AgentLayer mosquitoLayer = project.getAgentLayers().stream()
                .filter(l -> l.getName().equals("Mosquitoes")).findFirst().orElse(null);
        if (habitatLayer == null || mosquitoLayer == null) {
            System.err.println("ERROR: Cannot find agent layers for seeding");
            return;
        }

        // Create habitat calculator with study area geometry (if provided)
        HabitatCalculator habitatCalc = new HabitatCalculator(buildings, population, worldBounds,
                seeding.habitatGridSizeX, seeding.habitatGridSizeY, studyAreaGeometry);

        // Seed water tanks
        System.out.println("\nSeeding water tanks...");
        int tanksPlaced = 0;
        for (int i = 0; i < seeding.tanksToSeed; i++) {
            double[] point = habitatCalc.getRandomWeightedPoint();
            double rx = point[0], ry = point[1];
            double buildingDensity = buildings.getValueAt(rx, ry);
            double popDensity = population.getValueAt(rx, ry);
            if (buildingDensity > seeding.tankBuildingThreshold && popDensity > seeding.tankPopulationThreshold) {
                InertAgent tank = new InertAgent(rx, ry);
                tank.setWaterVolume(70 + rand.nextDouble() * 30);
                tank.setLarvalCount(rand.nextInt(30) + 10);
                tank.setEggCount(rand.nextInt(80) + 20);
                tank.setCapacity(300 + rand.nextDouble() * 200);
                habitatLayer.addAgent(tank);
                spatialRegistry.registerAgent(tank);
                tanksPlaced++;
                if (tanksPlaced % 100 == 0)
                    System.out.printf("  Placed %d/%d water tanks (bldg=%.2f%%, pop=%.2f%%)%n",
                            tanksPlaced, seeding.tanksToSeed, buildingDensity, popDensity);
            }
        }
        System.out.printf("Seeded %d water tanks%n", tanksPlaced);

        // Seed mosquitoes
        System.out.println("\nSeeding mosquitoes...");
        int mosquitoesPlaced = 0;
        for (int i = 0; i < seeding.mosquitoesToSeed; i++) {
            double[] point = habitatCalc.getRandomWeightedPoint();
            double rx = point[0], ry = point[1];
            double buildingDensity = buildings.getValueAt(rx, ry);
            double popDensity = population.getValueAt(rx, ry);
            if (buildingDensity > seeding.mosquitoBuildingThreshold || popDensity > seeding.mosquitoPopulationThreshold) {
                LivingAgent mosquito = new LivingAgent(rx, ry);
                double r = rand.nextDouble();
                LifecycleStage stage = r < 0.6 ? LifecycleStage.ADULT : (r < 0.85 ? LifecycleStage.LARVA : LifecycleStage.PUPA);
                mosquito.setStage(stage);
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
                        break;
                    case PUPA:
                        mosquito.setAge(rand.nextInt(3 * 24 * 4));
                        mosquito.setEnergy(0.5 + rand.nextDouble() * 0.3);
                        break;
                }
                mosquitoLayer.addAgent(mosquito);
                spatialRegistry.registerAgent(mosquito);
                mosquitoesPlaced++;
                if (mosquitoesPlaced % 500 == 0)
                    System.out.printf("  Placed %d/%d mosquitoes (bldg=%.2f%%, pop=%.2f%%)%n",
                            mosquitoesPlaced, seeding.mosquitoesToSeed, buildingDensity, popDensity);
            }
        }
        System.out.printf("Seeded %d mosquitoes%n", mosquitoesPlaced);
        System.out.println("\n=== Seeding Summary ===");
        System.out.printf("Water tanks: %d (target: %d)%n", tanksPlaced, seeding.tanksToSeed);
        System.out.printf("Mosquitoes: %d (target: %d)%n", mosquitoesPlaced, seeding.mosquitoesToSeed);
    }

    // ------------------------------------------------------------------------
    // The rest of the helper methods (unchanged)
    // ------------------------------------------------------------------------

    private static void createAndStartSimulation(Project project, TimeManager timeManager, SpatialRegistry spatialRegistry) {
        System.out.println("Creating simulation engine...");
        simulationEngine = new SimulationEngine(project, timeManager, spatialRegistry);
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
        monitorSimulation(simulationEngine, project);
    }

    private static void createFallbackRasters(RasterLayer elev, RasterLayer buildings, RasterLayer population) {
        elev.initialize(100, 100, 1);
        elev.setBounds(38.70, 38.80, 8.95, 9.05);
        buildings.initialize(100, 100, 1);
        buildings.setBounds(38.70, 38.80, 8.95, 9.05);
        population.initialize(100, 100, 1);
        population.setBounds(38.70, 38.80, 8.95, 9.05);

        Random rand = new Random();
        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 100; y++) {
                double elevValue = 2000 + Math.sin(x * 0.1) * Math.cos(y * 0.1) * 500;
                elev.setData(0, x, y, elevValue);
                double dist = Math.sqrt((x-50)*(x-50) + (y-50)*(y-50));
                double buildingValue = Math.max(0, 1.0 - dist / 50.0);
                buildings.setData(0, x, y, buildingValue);
                double popValue = Math.max(0, 0.8 - dist / 60.0) + rand.nextDouble() * 0.2;
                population.setData(0, x, y, popValue);
            }
        }
    }

    private static void createFallbackClimateLayers(Project project, TimeManager timeManager) {
        RasterLayer temperatureLayer = new RasterLayer("t2m", 10, 10, 24*30);
        RasterLayer precipitationLayer = new RasterLayer("tp", 10, 10, 24*30);
        temperatureLayer.setBounds(38.70, 38.80, 8.95, 9.05);
        precipitationLayer.setBounds(38.70, 38.80, 8.95, 9.05);

        Random rand = new Random();
        for (int t = 0; t < 24*30; t++) {
            for (int x = 0; x < 10; x++) {
                for (int y = 0; y < 10; y++) {
                    int hour = t % 24;
                    double temp = 293.15 + 5.0 + 10.0 * Math.sin(hour * Math.PI / 12.0) + rand.nextDouble() * 2.0;
                    temperatureLayer.setData(t, x, y, temp);
                    double precip = rand.nextDouble() < 0.1 ? 0.001 + rand.nextDouble() * 0.005 : 0.0;
                    precipitationLayer.setData(t, x, y, precip);
                }
            }
        }
        project.addLayer(temperatureLayer);
        project.addLayer(precipitationLayer);
        System.out.println("Created fallback climate layers");
    }

    private static void monitorSimulation(SimulationEngine engine, Project project) {
        while (engine != null) {
            try {
                Thread.sleep(5000);
                Map<String, Object> state = engine.getState();
                boolean running = (Boolean) state.get("running");
                long tick = (Long) state.get("tick");

                if (!running) {
                    System.out.println("Simulation has stopped normally");
                    break;
                }
                if (tick >= timeManager.getTotalTicks()) {
                    System.out.println("Simulation reached total ticks, stopping engine...");
                    engine.stop();
                    break;
                }

                int totalAgents = (Integer) state.getOrDefault("totalAgents", 0);
                double avgTickTime = (Double) state.getOrDefault("avgTickTime", 0.0);
                System.out.printf("[Monitor] Tick: %d | Agents: %d | Avg Tick Time: %.2f ms%n",
                        tick, totalAgents, avgTickTime);

                if (tick % 100 == 0) {
                    System.out.println("--- Detailed Status ---");
                    for (AgentLayer layer : project.getAgentLayers()) {
                        Map<String, Object> layerStats = layer.getStatistics();
                        System.out.printf("  %s: %d agents, %d rules evaluated%n",
                                layer.getName(), layerStats.get("agentCount"), layerStats.get("rulesEvaluated"));
                    }
                    System.out.println("----------------------");
                }

                if (avgTickTime > 10000) {
                    System.err.println("CRITICAL: Tick time too slow (" + avgTickTime + "ms), simulation may be hanging");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Monitor thread interrupted");
                break;
            } catch (Exception e) {
                System.err.println("Error in monitor: " + e.getMessage());
                try { Thread.sleep(10000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    public static Rectangle2D createWorldBounds(double centerLat, double centerLon, double bufferKm) {
        double bufferDegrees = bufferKm / 111.32;
        double minLon = centerLon - bufferDegrees;
        double minLat = centerLat - bufferDegrees;
        double sizeDegrees = bufferDegrees * 2;
        return new Rectangle2D.Double(minLon, minLat, sizeDegrees, sizeDegrees);
    }

    private static void startWatchdog(SimulationEngine engine, Thread simulationThread,
                                      Project project, SpatialRegistry spatialRegistry) {
        Thread watchdog = new Thread(() -> {
            try {
                int stuckCount = 0;
                long lastTick = 0;
                long lastTickTime = System.currentTimeMillis();
                while (simulationThread.isAlive() && !simulationThread.isInterrupted()) {
                    Thread.sleep(10000);
                    try {
                        Map<String, Object> state = engine.getState();
                        long currentTick = (Long) state.get("tick");
                        long currentTime = System.currentTimeMillis();
                        if (currentTick == lastTick) {
                            stuckCount++;
                            long stuckSeconds = (currentTime - lastTickTime) / 1000;
                            System.err.println("WARNING: Simulation may be stuck at tick " + currentTick +
                                    " (stuck for " + stuckSeconds + " seconds, count: " + stuckCount + ")");
                            if (stuckCount > 3) {
                                System.err.println("CRITICAL: Simulation appears stuck for over 30 seconds, forcing shutdown");
                                saveFinalStatisticsToFile(project, spatialRegistry, engine);
                                SnapshotMerger.mergeAfterSimulation();
                                engine.stop();
                                simulationThread.interrupt();
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

    private static void saveFinalStatisticsToFile(Project project, SpatialRegistry spatialRegistry, SimulationEngine engine) {
        if (project == null || spatialRegistry == null) return;
        try {
            File resultsDir = new File("results");
            if (!resultsDir.exists()) resultsDir.mkdirs();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeName = project.getName().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String filename = String.format("results/%s_final_statistics_%s.txt", safeName, timestamp);

            try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
                writer.println("=".repeat(80));
                writer.println("FINAL SIMULATION STATISTICS");
                writer.println("=".repeat(80));
                writer.println();
                writer.println("SIMULATION METADATA");
                writer.println("-".repeat(40));
                writer.printf("Project Name: %s%n", project.getName());
                writer.printf("Timestamp: %s%n", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                writer.printf("Total Runtime: %.2f seconds%n", engine != null ?
                        (System.currentTimeMillis() - simulationStartTime) / 1000.0 : 0);
                writer.println();

                writer.println("AGENT STATISTICS");
                writer.println("-".repeat(40));
                int totalAgents = 0;
                for (AgentLayer layer : project.getAgentLayers()) {
                    int layerAgents = layer.getAgents().size();
                    writer.printf("%-20s: %,9d agents%n", layer.getName(), layerAgents);
                    totalAgents += layerAgents;
                    Map<String, Object> layerStats = layer.getStatistics();
                    if (layerStats != null) {
                        for (Map.Entry<String, Object> entry : layerStats.entrySet()) {
                            if (!"agentCount".equals(entry.getKey()))
                                writer.printf("  %-18s: %s%n", entry.getKey(), entry.getValue());
                        }
                    }
                }
                writer.printf("%-20s: %,9d agents%n", "TOTAL", totalAgents);
                writer.println();

                writer.println("SPATIAL REGISTRY STATISTICS");
                writer.println("-".repeat(40));
                Map<String, Object> spatialStats = spatialRegistry.getStatistics();
                if (spatialStats != null) {
                    for (Map.Entry<String, Object> entry : spatialStats.entrySet())
                        writer.printf("%-25s: %s%n", entry.getKey(), entry.getValue());
                }
                writer.println();

                writer.println("LAYER STATISTICS");
                writer.println("-".repeat(40));
                int raster = 0, agent = 0, other = 0;
                for (Layer layer : project.getLayers()) {
                    if (layer instanceof RasterLayer || layer instanceof InterpolatedRasterLayer) raster++;
                    else if (layer instanceof AgentLayer) agent++;
                    else other++;
                }
                writer.printf("Raster Layers: %d%n", raster);
                writer.printf("Agent Layers: %d%n", agent);
                writer.printf("Other Layers: %d%n", other);
                writer.printf("Total Layers: %d%n", project.getLayers().size());
                writer.println();

                writer.println("SYSTEM RESOURCES");
                writer.println("-".repeat(40));
                Runtime rt = Runtime.getRuntime();
                long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                long total = rt.totalMemory() / (1024 * 1024);
                long max = rt.maxMemory() / (1024 * 1024);
                writer.printf("Memory Used: %d MB%n", used);
                writer.printf("Memory Total: %d MB%n", total);
                writer.printf("Memory Max: %d MB%n", max);
                writer.printf("Memory Usage: %.1f%%%n", (used * 100.0) / total);
                writer.println();

                if (engine != null) {
                    writer.println("PERFORMANCE METRICS");
                    writer.println("-".repeat(40));
                    Map<String, Object> engineStats = engine.getState();
                    for (Map.Entry<String, Object> entry : engineStats.entrySet())
                        writer.printf("%-25s: %s%n", entry.getKey(), entry.getValue());
                    try {
                        Map<String, Object> detailed = engine.getDetailedStatistics();
                        if (detailed != null && !detailed.isEmpty()) {
                            writer.println();
                            writer.println("DETAILED STATISTICS");
                            writer.println("-".repeat(40));
                            for (Map.Entry<String, Object> entry : detailed.entrySet())
                                writer.printf("%-30s: %s%n", entry.getKey(), entry.getValue());
                        }
                    } catch (Exception ignored) {}
                }

                writer.println();
                writer.println("ENVIRONMENT STATISTICS");
                writer.println("-".repeat(40));
                writer.printf("Default Search Radius: %.6f degrees (approx. %.1f meters)%n",
                        project.getDefaultAgentSearchRadius(), project.getDefaultAgentSearchRadius() * 111320);
                writer.printf("Default Agent Step: %.6f degrees (approx. %.1f meters)%n",
                        project.getDefaultAgentStep(), project.getDefaultAgentStep() * 111320);
                writer.printf("Default Max Agent Age: %d ticks (%.1f days)%n",
                        project.getDefaultMaxAgentAge(), project.getDefaultMaxAgentAge() / 96.0);

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
                System.out.println("Final statistics saved to: " + filename);
            }
        } catch (Exception e) {
            System.err.println("Error saving final statistics: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper inner class for weighted habitat selection with polygon constraint
    private static class HabitatCalculator {
        private final double[][] suitabilityGrid;
        private final double[] cumulativeDistribution;
        private final double cellSize;
        private final double minX, minY;
        private final int gridSizeX, gridSizeY;
        private final Random random = new Random();
        private final Geometry studyArea;

        // Constructor without geometry (fallback to rectangular bounds)
        public HabitatCalculator(RasterLayer buildings, RasterLayer population,
                                 Rectangle2D worldBounds, int gridSizeX, int gridSizeY) {
            this(buildings, population, worldBounds, gridSizeX, gridSizeY, null);
        }

        // New constructor with study area polygon
        public HabitatCalculator(RasterLayer buildings, RasterLayer population,
                                 Rectangle2D worldBounds, int gridSizeX, int gridSizeY,
                                 Geometry studyArea) {
            this.gridSizeX = gridSizeX;
            this.gridSizeY = gridSizeY;
            this.cellSize = Math.min(worldBounds.getWidth() / gridSizeX, worldBounds.getHeight() / gridSizeY);
            this.minX = worldBounds.getMinX();
            this.minY = worldBounds.getMinY();
            this.studyArea = studyArea;
            this.suitabilityGrid = new double[gridSizeX][gridSizeY];
            calculateSuitability(buildings, population);
            this.cumulativeDistribution = buildCumulativeDistribution();
        }

//        private void calculateSuitability(RasterLayer buildings, RasterLayer population) {
//            double total = 0.0;
//            GeometryFactory geomFactory = new GeometryFactory();
//            for (int i = 0; i < gridSizeX; i++) {
//                for (int j = 0; j < gridSizeY; j++) {
//                    double x = minX + (i + 0.5) * cellSize;
//                    double y = minY + (j + 0.5) * cellSize;
//
//                    // If study area polygon is provided, skip cells that lie outside it
//                    if (studyArea != null) {
//                        Point point = geomFactory.createPoint(new Coordinate(x, y));
//                        if (!studyArea.contains(point)) {
//                            suitabilityGrid[i][j] = 0.0;
//                            continue;
//                        }
//                    }
//
//                    double b = Math.max(0, buildings.getValueAt(x, y));
//                    double p = Math.max(0, population.getValueAt(x, y));
//                    double suit;
//                    if (b > 0.2 && p > 0.1) {
//                        suit = b * 0.6 + (Math.min(p, 100) / 100.0) * 0.4;
//                    } else if (b > 0.1 || p > 0.05) {
//                        suit = (b * 0.3 + (Math.min(p, 50) / 50.0) * 0.2) * 0.5;
//                    } else {
//                        suit = 0.01;
//                    }
//                    suit *= (0.9 + random.nextDouble() * 0.2);
//                    suitabilityGrid[i][j] = suit;
//                    total += suit;
//                }
//            }
//            if (total > 0) {
//                for (int i = 0; i < gridSizeX; i++) {
//                    for (int j = 0; j < gridSizeY; j++) {
//                        suitabilityGrid[i][j] /= total;
//                    }
//                }
//            }
//        }
        
        private void calculateSuitability(RasterLayer buildings, RasterLayer population) {
            double total = 0.0;
            GeometryFactory geomFactory = new GeometryFactory();
            for (int i = 0; i < gridSizeX; i++) {
                for (int j = 0; j < gridSizeY; j++) {
                    double x = minX + (i + 0.5) * cellSize;
                    double y = minY + (j + 0.5) * cellSize;

                    // Skip points outside study area if geometry provided
                    if (studyArea != null) {
                        Point point = geomFactory.createPoint(new Coordinate(x, y));
                        if (!studyArea.contains(point)) {
                            suitabilityGrid[i][j] = 0.0;
                            continue;
                        }
                    }

                    double b = Math.max(0, buildings.getValueAt(x, y));
                    double p = Math.max(0, population.getValueAt(x, y));

                    // Stronger weighting: prefer areas with both high building and population
                    double suit = (b * 0.7) + (Math.min(p, 100) / 100.0) * 0.3;
                    // Boost areas where both are present
                    if (b > 0.2 && p > 0.1) suit *= 1.5;
                    // Add a small random factor to avoid exact ties
                    suit *= (0.9 + random.nextDouble() * 0.2);

                    suitabilityGrid[i][j] = suit;
                    total += suit;
                }
            }
            if (total > 0) {
                for (int i = 0; i < gridSizeX; i++) {
                    for (int j = 0; j < gridSizeY; j++) {
                        suitabilityGrid[i][j] /= total;
                    }
                }
            }
        }

        private double[] buildCumulativeDistribution() {
            double[] cdf = new double[gridSizeX * gridSizeY];
            double cum = 0.0;
            for (int i = 0; i < gridSizeX; i++) {
                for (int j = 0; j < gridSizeY; j++) {
                    cum += suitabilityGrid[i][j];
                    cdf[i * gridSizeY + j] = cum;
                }
            }
            if (cum > 0) {
                for (int i = 0; i < cdf.length; i++) cdf[i] /= cum;
            }
            return cdf;
        }

        public double[] getRandomWeightedPoint() {
            double r = random.nextDouble();
            int idx = java.util.Arrays.binarySearch(cumulativeDistribution, r);
            if (idx < 0) idx = -(idx + 1);
            if (idx >= cumulativeDistribution.length) idx = cumulativeDistribution.length - 1;
            int i = idx / gridSizeY;
            int j = idx % gridSizeY;
            double x = minX + (i + random.nextDouble()) * cellSize;
            double y = minY + (j + random.nextDouble()) * cellSize;
            return new double[]{x, y};
        }
    }
}