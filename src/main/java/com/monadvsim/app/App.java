package com.monadvsim.app;

import com.monadvsim.app.models.utils.SimulationLogger;
import com.monadvsim.app.models.config.SimulationConfig;
import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.utils.ConfigLoader;
import com.monadvsim.app.models.utils.SeedManager;
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
 * All configuration is read from a single YAML file.
 *
 * Command line arguments:
 *   args[0] - seed (optional, default: current time)
 *   args[1] - output directory (optional, default: "results")
 *   args[2] - config file path (optional, default: "config/simulation.yaml")
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
    private static String outputDir;

    // Default configuration path (classpath resource)
    private static final String DEFAULT_CONFIG_PATH = "config/simulation.yaml";
    private static final String DEFAULT_OUTPUT_DIR = "results";

    public static void main(String[] args) {
        SimulationLogger.info("Starting Multi-Agent Simulation System");

        try {
            // ======================================================================
            // 1. PARSE COMMAND LINE ARGUMENTS
            // ======================================================================
            long seed = System.currentTimeMillis();
            String outputDir = DEFAULT_OUTPUT_DIR;
            String configPath = DEFAULT_CONFIG_PATH;
            
            if (args.length > 0) {
                // First argument: seed (optional)
                try {
                    seed = Long.parseLong(args[0]);
                    SimulationLogger.info("Seed from command line: %d", seed);
                } catch (NumberFormatException e) {
                    // If first arg is not a number, treat it as config path
                    configPath = args[0];
                    SimulationLogger.info("Config path from command line: %s", configPath);
                }
            }
            
            if (args.length > 1) {
                // Second argument: output directory
                outputDir = args[1];
                SimulationLogger.info("Output directory from command line: %s", outputDir);
            }
            
            if (args.length > 2) {
                // Third argument: config path (overrides previous)
                configPath = args[2];
                SimulationLogger.info("Config path from command line (arg 3): %s", configPath);
            }
            
            // Ensure config path works for both classpath and file system
            // Don't force leading "/" - let ConfigLoader handle it
            if (configPath.startsWith("/")) {
                // Classpath resource
                configPath = configPath;
            } else if (!configPath.startsWith("file:") && !configPath.startsWith("http")) {
                // Default: try classpath first, then file system
                // We'll let loadConfig handle the resolution
            }
            
            // ======================================================================
            // 2. SETUP OUTPUT DIRECTORY AND LOGGING
            // ======================================================================
            
            File resultsDir = new File(outputDir);
            if (!resultsDir.exists()) {
                if (!resultsDir.mkdirs()) {
                    SimulationLogger.severe("Could not create results directory: " + outputDir);
                    return;
                }
            }
            
            // Initialize logger with output directory
            String logDir = resultsDir.getAbsolutePath() + "/logs";
            SimulationLogger.initialize(logDir);
            SimulationLogger.info("Log directory: %s", logDir);
            
            App.outputDir = resultsDir.getAbsolutePath();
            
            // Set seed
            SeedManager.setSeed(seed);
            SimulationLogger.info("Using seed: %d", seed);
            SimulationLogger.info("Output directory: %s", resultsDir.getAbsolutePath());
            SimulationLogger.info("Config file: %s", configPath);

            // ======================================================================
            // 3. LOAD CONFIGURATION FROM YAML
            // ======================================================================
            
            config = loadConfig(configPath);
            if (config == null) {
                SimulationLogger.severe("Failed to load configuration from: %s", configPath);
                SimulationLogger.info("Using default configuration values...");
                config = createDefaultConfig();
            }

            // ======================================================================
            // 4. SETUP TIME MANAGER
            // ======================================================================
            
            LocalDateTime startDate = LocalDateTime.parse(config.time.startDateTime);
            timeManager = new TimeManager(startDate, config.time.totalTicks, config.time.tickMinutes);

            // ======================================================================
            // 5. WORLD BOUNDS FROM STUDY SITE
            // ======================================================================
            
            worldBounds = getWorldBoundsFromStudySite(config.files.studySite);
            SimulationLogger.info("World bounds from study site: " + worldBounds);

            // ======================================================================
            // 6. LOAD STUDY AREA GEOMETRY FOR SEEDING
            // ======================================================================
            
            Geometry studyAreaGeometry = null;
            try {
                studyAreaGeometry = VectorBoundsLoader.getStudyAreaGeometry(config.files.studySite);
                SimulationLogger.info("Loaded study area polygon for seeding constraints.");
            } catch (Exception e) {
                SimulationLogger.severe("Could not load study area geometry: " + e.getMessage());
                SimulationLogger.severe("Seeding will use rectangular bounds only.");
            }

            // ======================================================================
            // 7. SPATIAL REGISTRY
            // ======================================================================
            
            spatialRegistry = new SpatialRegistry(worldBounds, config.gridCellSizeDegrees);

            // ======================================================================
            // 8. CREATE AND CONFIGURE PROJECT
            // ======================================================================
            
            project = new Project("Mosquito Simulation");
            configureSimulation(project, spatialRegistry, timeManager, worldBounds, config, studyAreaGeometry);

            // ======================================================================
            // 9. START SIMULATION
            // ======================================================================
            
            createAndStartSimulation(project, timeManager, spatialRegistry, resultsDir.getAbsolutePath());

            // ======================================================================
            // 10. SHUTDOWN HOOK
            // ======================================================================
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                SimulationLogger.info("\n🛑 Shutdown signal received...");
                if (simulationEngine != null) simulationEngine.stop();
            }));

            // ======================================================================
            // 11. WAIT FOR COMPLETION
            // ======================================================================
            
            if (simulationThread != null && simulationThread.isAlive()) simulationThread.join();

            // ======================================================================
            // 12. FINAL STATISTICS AND MERGE
            // ======================================================================
            
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine, resultsDir.getAbsolutePath());

            SnapshotMerger.mergeSnapshotsAndCleanup(resultsDir.getAbsolutePath(), 
                String.format("merged_snapshots_%s.csv", 
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))));

            SimulationLogger.info("Simulation completed successfully!");

        } catch (InterruptedException e) {
            SimulationLogger.severe("Error in simulation: " + e.getMessage());
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine, outputDir);
        } catch (Exception e) {
            SimulationLogger.severe("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            saveFinalStatisticsToFile(project, spatialRegistry, simulationEngine, outputDir);
        }
    }

    // ======================================================================
    // CONFIGURATION LOADING
    // ======================================================================
    
    /**
     * Load configuration from the specified path.
     * Supports classpath resources and file system paths.
     */
    private static SimulationConfig loadConfig(String configPath) {
        SimulationConfig loadedConfig = null;
        
        // Try loading from classpath first (default behavior)
        try {
            String classpathPath = configPath;
            if (!classpathPath.startsWith("/")) {
                classpathPath = "/" + classpathPath;
            }
            loadedConfig = ConfigLoader.loadFromYaml(classpathPath);
            SimulationLogger.info("Configuration loaded from classpath: %s", classpathPath);
            return loadedConfig;
        } catch (Exception e) {
            SimulationLogger.fine("Could not load from classpath: %s", e.getMessage());
        }
        
        // Try loading from file system
        try {
            File configFile = new File(configPath);
            if (configFile.exists()) {
                loadedConfig = ConfigLoader.loadFromFile(configPath);
                SimulationLogger.info("Configuration loaded from file: %s", configPath);
                return loadedConfig;
            }
        } catch (Exception e) {
            SimulationLogger.fine("Could not load from file: %s", e.getMessage());
        }
        
        // Try with "config/" prefix for file system
        try {
            String prefixedPath = "config/" + configPath;
            File configFile = new File(prefixedPath);
            if (configFile.exists()) {
                loadedConfig = ConfigLoader.loadFromFile(prefixedPath);
                SimulationLogger.info("Configuration loaded from file: %s", prefixedPath);
                return loadedConfig;
            }
        } catch (Exception e) {
            SimulationLogger.fine("Could not load from config/ path: %s", e.getMessage());
        }
        
        // Try with leading slash for classpath (if not already)
        if (!configPath.startsWith("/")) {
            try {
                String classpathPath = "/" + configPath;
                loadedConfig = ConfigLoader.loadFromYaml(classpathPath);
                SimulationLogger.info("Configuration loaded from classpath (with /): %s", classpathPath);
                return loadedConfig;
            } catch (Exception e) {
                SimulationLogger.fine("Could not load from classpath with /: %s", e.getMessage());
            }
        }
        
        // Try with "config/" and leading slash for classpath
        if (!configPath.startsWith("/")) {
            try {
                String classpathPath = "/config/" + configPath;
                loadedConfig = ConfigLoader.loadFromYaml(classpathPath);
                SimulationLogger.info("Configuration loaded from classpath: %s", classpathPath);
                return loadedConfig;
            } catch (Exception e) {
                SimulationLogger.fine("Could not load from classpath config/: %s", e.getMessage());
            }
        }
        
        SimulationLogger.severe("Could not load configuration from any location: %s", configPath);
        return null;
    }
    
    /**
     * Create a default configuration with hard-coded values.
     * Used as a fallback when config file cannot be loaded.
     */
    private static SimulationConfig createDefaultConfig() {
        SimulationLogger.info("Creating default configuration...");
        
        SimulationConfig defaultConfig = new SimulationConfig();
        
        // Time settings
        defaultConfig.time = new SimulationConfig.SimulationTime();
        defaultConfig.time.startDateTime = "2020-04-01T00:00:00";
        defaultConfig.time.totalTicks = 17280;
        defaultConfig.time.tickMinutes = 15;
        
        // File paths
        defaultConfig.files = new SimulationConfig.FilePaths();
        defaultConfig.files.studySite = "prepared_data/somali/somali.shp";
        defaultConfig.files.elevation = "prepared_data/somali/Small_Somali_Elevation_10m.tif";
        defaultConfig.files.buildings = "prepared_data/somali/Small_Somali_Building_Density_10m.tif";
        defaultConfig.files.population = "prepared_data/somali/population_2020_1km.tif";
        defaultConfig.files.climateNetCDF = "prepared_data/somali/climate_t2m_tp_2020.nc";
        
        // Grid size
        defaultConfig.gridCellSizeDegrees = 0.001;
        
        // Species parameters (default values from your config)
        defaultConfig.species = new SimulationConfig.SpeciesParameters();
        // Egg development
        defaultConfig.species.egg_dev_rho = 0.005000000000000001;
        defaultConfig.species.egg_dev_k = 39.20841161317061;
        defaultConfig.species.egg_dev_Delta = 1.9999999999999998;
        defaultConfig.species.egg_dev_lambda = -0.854916887158478;
        // Larva development
        defaultConfig.species.larva_dev_a = 2.7054689312186438e-05;
        defaultConfig.species.larva_dev_Tmin = 5.123026541348266;
        defaultConfig.species.larva_dev_Tmax = 44.999999999835175;
        defaultConfig.species.larva_dev_m = 1.663077023745059;
        // Pupa development
        defaultConfig.species.pupa_dev_rho = 0.005115463305281753;
        defaultConfig.species.pupa_dev_k = 39.940257466039114;
        defaultConfig.species.pupa_dev_Delta = 1.9999999999999998;
        defaultConfig.species.pupa_dev_lambda = -0.9081661833839708;
        // Mortality
        defaultConfig.species.egg_mort_b1 = 3.572876;
        defaultConfig.species.egg_mort_b2 = -0.323474;
        defaultConfig.species.egg_mort_b3 = 0.004941;
        defaultConfig.species.larva_mort_b1 = 1.9999999999999998;
        defaultConfig.species.larva_mort_b2 = -0.7395036647759831;
        defaultConfig.species.larva_mort_b3 = 0.017491023810106067;
        defaultConfig.species.pupa_mort_b1 = 5.882576;
        defaultConfig.species.pupa_mort_b2 = -0.578528;
        defaultConfig.species.pupa_mort_b3 = 0.009458;
        // Fecundity
        defaultConfig.species.fecundity_rmax = 1.6021994175985133;
        defaultConfig.species.fecundity_Topt = 30.634033187663604;
        defaultConfig.species.fecundity_c = -0.0052723551194922445;
        // Adult mortality
        defaultConfig.species.adult_mort_b1 = -1.4775;
        defaultConfig.species.adult_mort_b2 = -0.1377;
        defaultConfig.species.adult_mort_b3 = 0.003908;
        defaultConfig.species.adult_mortality_per_day = 0.1198;
        defaultConfig.species.sex_ratio = 0.5;
        
        // Project defaults
        defaultConfig.project = new SimulationConfig.ProjectDefaults();
        defaultConfig.project.defaultAgentSearchRadius = 0.005;
        defaultConfig.project.defaultAgentStep = 0.00005;
        defaultConfig.project.defaultMaxAgentAge = 2880;
        
        // Seeding
        defaultConfig.seeding = new SimulationConfig.SeedingConfig();
        defaultConfig.seeding.seedAcrossFullStudySite = false;
        defaultConfig.seeding.tanksToSeed = 5000;
        defaultConfig.seeding.mosquitoesToSeed = 10000;
        defaultConfig.seeding.habitatGridSizeX = 100;
        defaultConfig.seeding.habitatGridSizeY = 100;
        defaultConfig.seeding.tankBuildingThreshold = 0.0001;
        defaultConfig.seeding.tankPopulationThreshold = 0.0001;
        defaultConfig.seeding.mosquitoBuildingThreshold = 0.0001;
        defaultConfig.seeding.mosquitoPopulationThreshold = 0.0001;
        
        // Tokens
        defaultConfig.tokens = new ArrayList<>();
        SimulationConfig.TokenMapping tempToken = new SimulationConfig.TokenMapping();
        tempToken.token = "temperature";
        tempToken.layer = "t2m";
        defaultConfig.tokens.add(tempToken);
        
        SimulationConfig.TokenMapping precipToken = new SimulationConfig.TokenMapping();
        precipToken.token = "precipitation";
        precipToken.layer = "tp";
        defaultConfig.tokens.add(precipToken);
        
        SimulationConfig.TokenMapping popToken = new SimulationConfig.TokenMapping();
        popToken.token = "population";
        popToken.layer = "Population";
        defaultConfig.tokens.add(popToken);
        
        SimulationConfig.TokenMapping buildToken = new SimulationConfig.TokenMapping();
        buildToken.token = "building_density";
        buildToken.layer = "Buildings";
        defaultConfig.tokens.add(buildToken);
        
        SimulationConfig.TokenMapping elevToken = new SimulationConfig.TokenMapping();
        elevToken.token = "elevation";
        elevToken.layer = "Elevation";
        defaultConfig.tokens.add(elevToken);
        
        // Agent layers (simplified)
        defaultConfig.agentLayers = new ArrayList<>();
        
        // Mosquitoes layer
        SimulationConfig.AgentLayerConfig mosquitoLayer = new SimulationConfig.AgentLayerConfig();
        mosquitoLayer.name = "Mosquitoes";
        mosquitoLayer.rules = new ArrayList<>();
        defaultConfig.agentLayers.add(mosquitoLayer);
        
        // WaterTanks layer
        SimulationConfig.AgentLayerConfig waterLayer = new SimulationConfig.AgentLayerConfig();
        waterLayer.name = "WaterTanks";
        waterLayer.rules = new ArrayList<>();
        defaultConfig.agentLayers.add(waterLayer);
        
        return defaultConfig;
    }

    // ======================================================================
    // HELPER METHODS (unchanged from your original)
    // ======================================================================

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
            SimulationLogger.severe("Could not read study site file: " + e.getMessage());
            SimulationLogger.severe("Using fallback bounds (Dire Dawa centroid + 5 km buffer).");
            // Fallback to centroid + buffer (Dire Dawa approximate coordinates)
            return VectorBoundsLoader.createFallbackBounds(41.8562, 9.6041, 5.0);
        }
    }

    private static void configureSimulation(Project project,
                                            SpatialRegistry spatialRegistry,
                                            TimeManager timeManager,
                                            Rectangle2D worldBounds,
                                            SimulationConfig config,
                                            Geometry studyAreaGeometry) {
        SimulationLogger.info("Configuring project from YAML");
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
                SimulationLogger.info("Static raster layers loaded successfully");
            } catch (Exception e) {
                SimulationLogger.severe("Failed to load raster layers: " + e.getMessage());
                createFallbackRasters(elev, buildings, population);
                project.addLayer(elev);
                project.addLayer(buildings);
                project.addLayer(population);
            }

            // ---- Climate data ----
            SimulationLogger.info("Loading climate data...");
            try {
                persistenceService.loadClimateData(project, config.files.climateNetCDF, timeManager);
                SimulationLogger.info("Climate data loaded successfully");
            } catch (Exception e) {
                SimulationLogger.severe("Failed to load climate data: " + e.getMessage());
                SimulationLogger.info("Using fallback climate data...");
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
                SimulationLogger.info("Added agent layer '%s' with %d rules", layerConfig.name, layerConfig.rules.size());
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
            SimulationLogger.info("Loaded %d tokens from YAML", tokens.size());

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
            SimulationLogger.info("Seeding initial agents population...");
            seedInitialPopulation(project, config.seeding, buildings, population, spatialRegistry, worldBounds, studyAreaGeometry);

            int totalTanks = project.getAgentLayers().stream()
                    .filter(l -> l.getName().equals("WaterTanks"))
                    .findFirst().map(l -> l.getAgents().size()).orElse(0);
            int totalMosquitoes = project.getAgentLayers().stream()
                    .filter(l -> l.getName().equals("Mosquitoes"))
                    .findFirst().map(l -> l.getAgents().size()).orElse(0);
            SimulationLogger.info("Initial agents: %d tanks, %d mosquitoes", totalTanks, totalMosquitoes);

        } catch (Exception e) {
            SimulationLogger.severe("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------------

    private static void copySpeciesParams(SpeciesParameters target, SimulationConfig.SpeciesParameters source) {
        // Egg development
        target.eggDev_rho = source.egg_dev_rho;
        target.eggDev_k = source.egg_dev_k;
        target.eggDev_Delta = source.egg_dev_Delta;
        target.eggDev_lambda = source.egg_dev_lambda;
        // Larva development
        target.larvaDev_a = source.larva_dev_a;
        target.larvaDev_Tmin = source.larva_dev_Tmin;
        target.larvaDev_Tmax = source.larva_dev_Tmax;
        target.larvaDev_m = source.larva_dev_m;
        // Pupa development
        target.pupaDev_rho = source.pupa_dev_rho;
        target.pupaDev_k = source.pupa_dev_k;
        target.pupaDev_Delta = source.pupa_dev_Delta;
        target.pupaDev_lambda = source.pupa_dev_lambda;
        // Egg mortality
        target.eggMort_b1 = source.egg_mort_b1;
        target.eggMort_b2 = source.egg_mort_b2;
        target.eggMort_b3 = source.egg_mort_b3;
        // Larva mortality
        target.larvaMort_b1 = source.larva_mort_b1;
        target.larvaMort_b2 = source.larva_mort_b2;
        target.larvaMort_b3 = source.larva_mort_b3;
        // Pupa mortality
        target.pupaMort_b1 = source.pupa_mort_b1;
        target.pupaMort_b2 = source.pupa_mort_b2;
        target.pupaMort_b3 = source.pupa_mort_b3;
        // Fecundity
        target.fecundity_rmax = source.fecundity_rmax;
        target.fecundity_Topt = source.fecundity_Topt;
        target.fecundity_c = source.fecundity_c;
        // Adult mortality
        target.adultMortalityPerDay = source.adult_mortality_per_day;
        target.adultMort_b1 = source.adult_mort_b1;
        target.adultMort_b2 = source.adult_mort_b2;
        target.adultMort_b3 = source.adult_mort_b3;
        // Sex ratio
        target.sexRatio = source.sex_ratio;
    }

    private static void seedInitialPopulation(Project project,
                                          SimulationConfig.SeedingConfig seeding,
                                          RasterLayer buildings,
                                          RasterLayer population,
                                          SpatialRegistry spatialRegistry,
                                          Rectangle2D worldBounds,
                                          Geometry studyAreaGeometry) {
        Random rand = SeedManager.getRandom();

        AgentLayer habitatLayer = project.getAgentLayers().stream()
                .filter(l -> l.getName().equals("WaterTanks")).findFirst().orElse(null);
        AgentLayer mosquitoLayer = project.getAgentLayers().stream()
                .filter(l -> l.getName().equals("Mosquitoes")).findFirst().orElse(null);
        if (habitatLayer == null || mosquitoLayer == null) {
            SimulationLogger.severe("ERROR: Cannot find agent layers for seeding");
            return;
        }

        // ===== UNIFORM SEEDING (memory‑efficient rejection sampling) =====
        if (seeding.seedAcrossFullStudySite) {
            SimulationLogger.info("Seeding uniformly across the whole study area (seedAcrossFullStudySite = true)");

            // Point generator that returns random points inside the study area (rejection sampling)
            java.util.function.Supplier<double[]> pointGenerator;
            if (studyAreaGeometry != null && !studyAreaGeometry.isEmpty()) {
                GeometryFactory geomFactory = new GeometryFactory();
                pointGenerator = () -> {
                    double x, y;
                    do {
                        x = worldBounds.getMinX() + rand.nextDouble() * worldBounds.getWidth();
                        y = worldBounds.getMinY() + rand.nextDouble() * worldBounds.getHeight();
                    } while (!studyAreaGeometry.contains(geomFactory.createPoint(new Coordinate(x, y))));
                    return new double[]{x, y};
                };
                SimulationLogger.info("Using polygon‑constrained uniform points (rejection sampling)");
            } else {
                // No polygon – just the bounding box
                pointGenerator = () -> new double[]{
                        worldBounds.getMinX() + rand.nextDouble() * worldBounds.getWidth(),
                        worldBounds.getMinY() + rand.nextDouble() * worldBounds.getHeight()
                };
                SimulationLogger.info("Using bounding‑box uniform points (no polygon)");
            }

            // Seed water tanks
            SimulationLogger.info("Seeding water tanks uniformly...");
            int tanksPlaced = 0;
            for (int i = 0; i < seeding.tanksToSeed; i++) {
                double[] point = pointGenerator.get();
                double rx = point[0], ry = point[1];
                InertAgent tank = new InertAgent(rx, ry);
                tank.setWaterVolume(30 + rand.nextDouble() * 70);
                tank.setLarvalCount(rand.nextInt(30) + 10);
                tank.setEggCount(rand.nextInt(80) + 20);
                tank.setCapacity(300 + rand.nextDouble() * 200);
                habitatLayer.addAgent(tank);
                spatialRegistry.registerAgent(tank);
                tanksPlaced++;
                if (tanksPlaced % 100 == 0) {
                    SimulationLogger.info("  Placed %d/%d water tanks", tanksPlaced, seeding.tanksToSeed);
                }
            }
            SimulationLogger.info("Seeded %d water tanks", tanksPlaced);

            // Seed mosquitoes
            SimulationLogger.info("Seeding mosquitoes uniformly...");
            int mosquitoesPlaced = 0;
            for (int i = 0; i < seeding.mosquitoesToSeed; i++) {
                double[] point = pointGenerator.get();
                double rx = point[0], ry = point[1];
                LivingAgent mosquito = new LivingAgent(rx, ry);
                double r = rand.nextDouble();
                LifecycleStage stage = r < 0.6 ? LifecycleStage.ADULT : (r < 0.85 ? LifecycleStage.LARVA : LifecycleStage.PUPA);
                mosquito.setStage(stage);
                switch (stage) {
                    case ADULT:
                        mosquito.setAge(0);
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
                    default:
                        break;
                }
                mosquitoLayer.addAgent(mosquito);
                spatialRegistry.registerAgent(mosquito);
                mosquitoesPlaced++;
                if (mosquitoesPlaced % 500 == 0) {
                    SimulationLogger.info("  Placed %d/%d mosquitoes", mosquitoesPlaced, seeding.mosquitoesToSeed);
                }
            }
            SimulationLogger.info("Seeded %d mosquitoes", mosquitoesPlaced);
            SimulationLogger.info("\n=== Seeding Summary (Uniform) ===");
            SimulationLogger.info("Water tanks: %d (target: %d)", tanksPlaced, seeding.tanksToSeed);
            SimulationLogger.info("Mosquitoes: %d (target: %d)", mosquitoesPlaced, seeding.mosquitoesToSeed);
            return;  // exit early – uniform seeding done
        }

        // ===== ORIGINAL WEIGHTED HABITAT SEEDING (seedAcrossFullStudySite = false) =====
        SimulationLogger.info("Seeding using weighted habitat selection (seedAcrossFullStudySite = false)");

        HabitatCalculator habitatCalc = new HabitatCalculator(buildings, population, worldBounds,
                seeding.habitatGridSizeX, seeding.habitatGridSizeY, studyAreaGeometry);

        // Seed water tanks (weighted)
        SimulationLogger.info("Seeding water tanks (weighted)...");
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
                    SimulationLogger.info("  Placed %d/%d water tanks (bldg=%.2f%%, pop=%.2f%%)",
                            tanksPlaced, seeding.tanksToSeed, buildingDensity, popDensity);
            }
        }
        SimulationLogger.info("Seeded %d water tanks", tanksPlaced);

        assert tanksPlaced > 0 : "Sorry no tank seeded";
        // Seed mosquitoes (weighted)
        SimulationLogger.info("Seeding mosquitoes (weighted)...");
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
                    SimulationLogger.info("  Placed %d/%d mosquitoes (bldg=%.2f%%, pop=%.2f%%)",
                            mosquitoesPlaced, seeding.mosquitoesToSeed, buildingDensity, popDensity);
            }
        }
        SimulationLogger.info("Seeded %d mosquitoes", mosquitoesPlaced);
        SimulationLogger.info("\n=== Seeding Summary (Weighted) ===");
        SimulationLogger.info("Water tanks: %d (target: %d)", tanksPlaced, seeding.tanksToSeed);
        SimulationLogger.info("Mosquitoes: %d (target: %d)", mosquitoesPlaced, seeding.mosquitoesToSeed);
    }

    // ------------------------------------------------------------------------
    // The rest of the helper methods
    // ------------------------------------------------------------------------

    private static void createAndStartSimulation(Project project, 
            TimeManager timeManager, 
            SpatialRegistry spatialRegistry,
            String outputDir) {
        SimulationLogger.info("Creating simulation engine...");
        simulationEngine = new SimulationEngine(project, timeManager, spatialRegistry, outputDir);
        simulationThread = new Thread(() -> {
            try {
                simulationEngine.run();
                SimulationLogger.info("Simulation thread completed");
            } catch (Exception e) {
                SimulationLogger.severe("Error in simulation thread: " + e.getMessage());
                e.printStackTrace();
            }
        });
        simulationThread.setName("Simulation-Thread");
        simulationThread.setDaemon(false);
        simulationThread.start();
        startWatchdog(simulationEngine, simulationThread, project, spatialRegistry, outputDir);
        SimulationLogger.info("Simulation started! Press Ctrl+C to stop.");
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
        SimulationLogger.info("Created fallback climate layers");
    }

    private static void monitorSimulation(SimulationEngine engine, Project project) {
        while (engine != null) {
            try {
                Thread.sleep(5000);
                Map<String, Object> state = engine.getState();
                boolean running = (Boolean) state.get("running");
                long tick = (Long) state.get("tick");

                if (!running) {
                    SimulationLogger.info("Simulation has stopped normally");
                    break;
                }
                if (tick >= timeManager.getTotalTicks()) {
                    SimulationLogger.info("Simulation reached total ticks, stopping engine...");
                    engine.stop();
                    break;
                }

                int totalAgents = (Integer) state.getOrDefault("totalAgents", 0);
                double avgTickTime = (Double) state.getOrDefault("avgTickTime", 0.0);
                SimulationLogger.info("[Monitor] Tick: %d | Agents: %d | Avg Tick Time: %.2f ms",
                        tick, totalAgents, avgTickTime);

                if (tick % 100 == 0) {
                    SimulationLogger.info("--- Detailed Status ---");
                    for (AgentLayer layer : project.getAgentLayers()) {
                        Map<String, Object> layerStats = layer.getStatistics();
                        SimulationLogger.info("  %s: %d agents, %d rules evaluated",
                                layer.getName(), layerStats.get("agentCount"), layerStats.get("rulesEvaluated"));
                    }
                    SimulationLogger.info("----------------------");
                }

                if (avgTickTime > 10000) {
                    SimulationLogger.severe("CRITICAL: Tick time too slow (" + avgTickTime + "ms), simulation may be hanging");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SimulationLogger.info("Monitor thread interrupted");
                break;
            } catch (Exception e) {
                SimulationLogger.severe("Error in monitor: " + e.getMessage());
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
                                      Project project, SpatialRegistry spatialRegistry,
                                      String outputDir) {
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
                                SimulationLogger.severe("CRITICAL: Simulation appears stuck for over 30 seconds, forcing shutdown");
                                saveFinalStatisticsToFile(project, spatialRegistry, engine, outputDir);
                                SnapshotMerger.mergeAfterSimulation();
                                engine.stop();
                                simulationThread.interrupt();
                                Thread.sleep(5000);
                                if (simulationThread.isAlive()) {
                                    SimulationLogger.severe("Simulation thread still alive, forcing termination");
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
                        SimulationLogger.severe("Error in watchdog while checking state: " + e.getMessage());
                        if (stuckCount++ > 5) {
                            SimulationLogger.severe("CRITICAL: Cannot retrieve simulation state, forcing shutdown");
                            saveFinalStatisticsToFile(project, spatialRegistry, engine, outputDir);
                            simulationThread.interrupt();
                            break;
                        }
                    }
                }
                SimulationLogger.info("Watchdog thread exiting");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SimulationLogger.info("Watchdog thread interrupted");
            }
        });
        watchdog.setDaemon(true);
        watchdog.setName("Simulation-Watchdog");
        watchdog.setPriority(Thread.MIN_PRIORITY);
        watchdog.start();
        SimulationLogger.info("Watchdog thread started");
    }

    private static void saveFinalStatisticsToFile(Project project, 
            SpatialRegistry spatialRegistry, SimulationEngine engine,
            String outputDir) {
        if (project == null || spatialRegistry == null) return;
        try {
            
            File resultsDir = new File(outputDir);
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeName = project.getName().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String filename = String.format("%s/%s_final_statistics_%s.txt", outputDir, safeName, timestamp);
           
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
                SimulationLogger.info("Final statistics saved to: " + filename);
            }
        } catch (Exception e) {
            SimulationLogger.severe("Error saving final statistics: " + e.getMessage());
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
        private final Random random = SeedManager.getRandom();
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

        private void calculateSuitability(RasterLayer buildings, RasterLayer population) {
            double total = 0.0;
            GeometryFactory geomFactory = new GeometryFactory();
            for (int i = 0; i < gridSizeX; i++) {
                for (int j = 0; j < gridSizeY; j++) {
                    double x = minX + (i + 0.5) * cellSize;
                    double y = minY + (j + 0.5) * cellSize;

                    // If study area polygon is provided, skip cells that lie outside it
                    if (studyArea != null) {
                        Point point = geomFactory.createPoint(new Coordinate(x, y));
                        if (!studyArea.contains(point)) {
                            suitabilityGrid[i][j] = 0.0;
                            continue;
                        }
                    }

                    double b = Math.max(0, buildings.getValueAt(x, y));
                    double p = Math.max(0, population.getValueAt(x, y));
                    double suit;
                    if (b > config.seeding.tankBuildingThreshold 
                            && p > config.seeding.tankPopulationThreshold) {
                        suit = b * 0.6 + (Math.min(p, 100) / 100.0) * 0.4;
                    } else if (b > config.seeding.tankBuildingThreshold 
                            || p > config.seeding.tankPopulationThreshold) {
                        suit = (b * 0.3 + (Math.min(p, 50) / 50.0) * 0.2) * 0.5;
                    } else {
                        suit = 0.01;
                    }
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