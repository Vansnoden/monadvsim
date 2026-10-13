package com.monadvsim.app;

import com.monadvsim.app.models.utils.SimulationLogger;
import com.monadvsim.app.models.config.SimulationConfig;
import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.LayerFactory;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.utils.ConfigLoader;
import com.monadvsim.app.models.utils.OccurrenceLoader;
import com.monadvsim.app.models.utils.SeedManager;
import com.monadvsim.app.models.utils.SnapshotMerger;
import com.monadvsim.app.models.utils.VectorBoundsLoader;
import javax.imageio.ImageIO;
import org.geotools.coverage.grid.io.GridFormatFinder;

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
import com.monadvsim.app.models.engine.HabitatSuitabilityScorer;
import com.monadvsim.app.models.engine.WeightedRasterSuitability;

/**
 * Main Application Entry Point
 * 
 * Data-driven simulation configuration from YAML.
 * All layers (raster, climate, vector) are defined in config.
 *
 * Command line arguments:
 *   args[0] - seed (optional, default: current time)
 *   args[1] - output directory (optional, default: "results")
 *   args[2] - config file path (optional, default: "config/simulation.yaml")
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

    private static final String DEFAULT_CONFIG_PATH = "config/simulation.yaml";
    private static final String DEFAULT_OUTPUT_DIR = "results";

    public static void main(String[] args) {
        // Force ImageIO and GeoTools to discover raster SPI readers inside the fat JAR
        javax.imageio.ImageIO.scanForPlugins();
        org.geotools.coverage.grid.io.GridFormatFinder.scanForPlugins();
        
        SimulationLogger.info("Starting Multi-Agent Simulation System");

        try {
            // ======================================================================
            // 1. PARSE COMMAND LINE ARGUMENTS
            // ======================================================================
            long seed = System.currentTimeMillis();
            String outputDir = DEFAULT_OUTPUT_DIR;
            String configPath = DEFAULT_CONFIG_PATH;

            if (args.length > 0) {
                try {
                    seed = Long.parseLong(args[0]);
                    SimulationLogger.info("Seed from command line: %d", seed);
                } catch (NumberFormatException e) {
                    configPath = args[0];
                    SimulationLogger.info("Config path from command line: %s", configPath);
                }
            }

            if (args.length > 1) {
                outputDir = args[1];
                SimulationLogger.info("Output directory from command line: %s", outputDir);
            }

            if (args.length > 2) {
                configPath = args[2];
                SimulationLogger.info("Config path from command line: %s", configPath);
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

            String logDir = resultsDir.getAbsolutePath() + "/logs";
            SimulationLogger.initialize(logDir);
            SimulationLogger.info("Log directory: %s", logDir);

            App.outputDir = resultsDir.getAbsolutePath();
            SeedManager.setSeed(seed);
            SimulationLogger.info("Using seed: %d", seed);
            SimulationLogger.info("Output directory: %s", resultsDir.getAbsolutePath());
            SimulationLogger.info("Config file: %s", configPath);

            // ======================================================================
            // 3. LOAD CONFIGURATION
            // ======================================================================
            config = loadConfig(configPath);
            if (config == null) {
                SimulationLogger.severe("Failed to load configuration from: %s", configPath);
                SimulationLogger.info("Using default configuration values...");
                config = createDefaultConfig(); // Just for Testing
            }

            // ======================================================================
            // 4. SETUP TIME MANAGER
            // ======================================================================
            LocalDateTime startDate = LocalDateTime.parse(config.time.startDateTime);
            timeManager = new TimeManager(startDate, config.time.totalTicks, config.time.tickMinutes);

            timeManager.setDataStartDateTime(startDate);
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
                SimulationLogger.warning("Could not load study area geometry: " + e.getMessage());
                SimulationLogger.info("Seeding will use rectangular bounds only.");
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
            if (simulationThread != null && simulationThread.isAlive()) {
                simulationThread.join();
            }

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

    private static SimulationConfig loadConfig(String configPath) {
        // 1. Classpath
        try {
            String cpPath = configPath.startsWith("/") ? configPath : "/" + configPath;
            SimulationConfig c = ConfigLoader.loadFromYaml(cpPath);
            SimulationLogger.info("Loaded config from classpath: %s", cpPath);
            return c;
        } catch (Exception e) {
            SimulationLogger.info("Classpath load failed: %s", e.getMessage());
        }

        // 2. File system (exact)
        try {
            File configFile = new File(configPath);
            if (configFile.exists()) {
                SimulationConfig c = ConfigLoader.loadFromFile(configPath);
                SimulationLogger.info("Loaded config from file: %s", configFile.getAbsolutePath());
                return c;
            }
        } catch (Exception e) {
            SimulationLogger.warning("File load failed for '%s': %s", configPath, e.getMessage());
        }

        // 3. config/ prefix
        try {
            String prefixed = "config/" + configPath;
            File configFile = new File(prefixed);
            if (configFile.exists()) {
                SimulationConfig c = ConfigLoader.loadFromFile(prefixed);
                SimulationLogger.info("Loaded config from file: %s", configFile.getAbsolutePath());
                return c;
            }
        } catch (Exception e) {
            SimulationLogger.warning("Prefixed file load failed: %s", e.getMessage());
        }

        SimulationLogger.severe("❌ Config not found or unreadable: %s", configPath);
        return null;
    }

    
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

        // Layer definitions (data-driven)
        defaultConfig.layers = new ArrayList<>();
        
        // Static rasters
        addLayerDefinition(defaultConfig.layers, "Elevation", defaultConfig.files.elevation, "raster");
        addLayerDefinition(defaultConfig.layers, "Buildings", defaultConfig.files.buildings, "raster");
        addLayerDefinition(defaultConfig.layers, "Population", defaultConfig.files.population, "raster");
        
        // Climate layers - multiple variables from NetCDF
        SimulationConfig.LayerDefinition climateDef = new SimulationConfig.LayerDefinition();
        climateDef.name = "Climate";
        climateDef.filePath = defaultConfig.files.climateNetCDF;
        climateDef.type = "timeseries";
        climateDef.variables = new ArrayList<>();
        climateDef.variables.add("t2m");
        climateDef.variables.add("tp");
        climateDef.active = true;
        defaultConfig.layers.add(climateDef);

        // Grid size
        defaultConfig.gridCellSizeDegrees = 0.001;

        // Species parameters
        defaultConfig.species = new SimulationConfig.SpeciesParameters();
        defaultConfig.species.egg_dev_rho = 0.005;
        defaultConfig.species.egg_dev_k = 39.2084;
        defaultConfig.species.egg_dev_Delta = 2.0;
        defaultConfig.species.egg_dev_lambda = -0.8549;
        defaultConfig.species.larva_dev_a = 2.705e-5;
        defaultConfig.species.larva_dev_Tmin = 5.123;
        defaultConfig.species.larva_dev_Tmax = 45.0;
        defaultConfig.species.larva_dev_m = 1.663;
        defaultConfig.species.pupa_dev_rho = 0.0051;
        defaultConfig.species.pupa_dev_k = 39.94;
        defaultConfig.species.pupa_dev_Delta = 2.0;
        defaultConfig.species.pupa_dev_lambda = -0.9082;
        defaultConfig.species.egg_mort_b1 = 3.5729;
        defaultConfig.species.egg_mort_b2 = -0.3235;
        defaultConfig.species.egg_mort_b3 = 0.00494;
        defaultConfig.species.larva_mort_b1 = 2.0;
        defaultConfig.species.larva_mort_b2 = -0.7395;
        defaultConfig.species.larva_mort_b3 = 0.01749;
        defaultConfig.species.pupa_mort_b1 = 5.8826;
        defaultConfig.species.pupa_mort_b2 = -0.5785;
        defaultConfig.species.pupa_mort_b3 = 0.00946;
        defaultConfig.species.fecundity_rmax = 1.6022;
        defaultConfig.species.fecundity_Topt = 30.634;
        defaultConfig.species.fecundity_c = -0.00527;
        defaultConfig.species.adult_mort_b1 = -1.4775;
        defaultConfig.species.adult_mort_b2 = -0.1377;
        defaultConfig.species.adult_mort_b3 = 0.00391;
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
        defaultConfig.tokens.add(createToken("temperature", "t2m"));
        defaultConfig.tokens.add(createToken("precipitation", "tp"));
        defaultConfig.tokens.add(createToken("population", "Population"));
        defaultConfig.tokens.add(createToken("building_density", "Buildings"));
        defaultConfig.tokens.add(createToken("elevation", "Elevation"));

        // Agent layers
        defaultConfig.agentLayers = new ArrayList<>();
        defaultConfig.agentLayers.add(createAgentLayer("Mosquitoes"));
        defaultConfig.agentLayers.add(createAgentLayer("WaterTanks"));

        return defaultConfig;
    }

    private static void addLayerDefinition(List<SimulationConfig.LayerDefinition> layers, 
                                           String name, String filePath, String type) {
        SimulationConfig.LayerDefinition def = new SimulationConfig.LayerDefinition();
        def.name = name;
        def.filePath = filePath;
        def.type = type;
        def.active = true;
        layers.add(def);
    }

    private static SimulationConfig.TokenMapping createToken(String token, String layer) {
        SimulationConfig.TokenMapping tm = new SimulationConfig.TokenMapping();
        tm.token = token;
        tm.layer = layer;
        return tm;
    }

    private static SimulationConfig.AgentLayerConfig createAgentLayer(String name) {
        SimulationConfig.AgentLayerConfig alc = new SimulationConfig.AgentLayerConfig();
        alc.name = name;
        alc.rules = new ArrayList<>();
        return alc;
    }

    // ======================================================================
    // WORLD BOUNDS
    // ======================================================================

    private static Rectangle2D getWorldBoundsFromStudySite(String studySitePath) {
        try {
            if (studySitePath.toLowerCase().endsWith(".shp")) {
                return VectorBoundsLoader.getBoundsFromShapefile(studySitePath);
            } else if (studySitePath.toLowerCase().endsWith(".qgz") || 
                       studySitePath.toLowerCase().endsWith(".qgs")) {
                String shpPath = studySitePath.replaceAll("\\.qgz$", ".shp")
                                               .replaceAll("\\.qgs$", ".shp");
                File shpFile = new File(shpPath);
                if (shpFile.exists()) {
                    return VectorBoundsLoader.getBoundsFromShapefile(shpPath);
                }
                throw new IOException("No shapefile found for QGIS project.");
            }
            throw new IOException("Unsupported file type: " + studySitePath);
        } catch (Exception e) {
            SimulationLogger.warning("Could not read study site: " + e.getMessage());
            SimulationLogger.info("Using fallback bounds (Dire Dawa centroid + 5 km buffer).");
            return VectorBoundsLoader.createFallbackBounds(41.8562, 9.6041, 5.0);
        }
    }

    // ======================================================================
    // SIMULATION CONFIGURATION
    // ======================================================================
    
    private static void configureSimulation(Project project,
                                        SpatialRegistry spatialRegistry,
                                        TimeManager timeManager,
                                        Rectangle2D worldBounds,
                                        SimulationConfig config,
                                        Geometry studyAreaGeometry) {
        SimulationLogger.info("Configuring project from YAML");

        try {
            // ---- Project defaults ----
            project.setDefaultAgentSearchRadius(config.project.defaultAgentSearchRadius);
            project.setDefaultAgentStep(config.project.defaultAgentStep);
            project.setDefaultMaxAgentAge(config.project.defaultMaxAgentAge);
            project.setMaxAgeMultipliers(
                config.project.maxAgeMultiplierEgg,
                config.project.maxAgeMultiplierLarva,
                config.project.maxAgeMultiplierPupa,
                config.project.maxAgeMultiplierAdult);

            // ---- Species parameters and lifecycle model ----
            SpeciesParameters params = new SpeciesParameters();
            copySpeciesParams(params, config.species);
            LifecycleModel lifecycleModel = new LifecycleModel(params, config.time.tickMinutes);
            project.setLifecycleModel(lifecycleModel);

            // ---- Layer Factory ----
            LayerFactory layerFactory = new LayerFactory(timeManager);

            // ============================================================
            // DATA-DRIVEN LAYER LOADING
            // ============================================================
            if (config.layers != null && !config.layers.isEmpty()) {
                SimulationLogger.info("Loading layers from data-driven configuration...");
                loadDataDrivenLayers(project, layerFactory, config.layers);
            }

            // ---- Set spatial registry ----
            project.setSpatialRegistry(spatialRegistry);

            // ---- Rule engine & lifecycle manager ----
            RuleEngine ruleEngine = new RuleEngine();
            AgentLifeCycleManager lifecycleManager = new AgentLifeCycleManager(spatialRegistry);

            // ---- Agent layers ----
            for (var layerConfig : config.agentLayers) {
                AgentLayer layer = new AgentLayer(layerConfig.name, ruleEngine, lifecycleManager);
                layer.setLifecycleManager(lifecycleManager);
                layer.setLifecycleModel(lifecycleModel);
                layer.setTimeManager(timeManager);
                layer.setProject(project);
                for (var rule : layerConfig.rules) {
                    layer.addRule(rule.condition, rule.action, rule.priority);
                }
                project.addLayer(layer);
                SimulationLogger.info("Added agent layer '%s' with %d rules",
                    layerConfig.name, layerConfig.rules.size());
            }

            // ---- Tokens for rule engine ----
            List<String> tokens = new ArrayList<>();
            List<String> layerNames = new ArrayList<>();
            if (config.tokens != null) {
                for (var tokenMapping : config.tokens) {
                    tokens.add(tokenMapping.token);
                    layerNames.add(tokenMapping.layer);
                }
            }
            project.setTokens(tokens);
            project.setLayerNames(layerNames);
            SimulationLogger.info("Loaded %d tokens: %s", tokens.size(), tokens);

            // ============================================================
            // BOUNDS: DO NOT override with the study-site extent.
            // Each RasterLayer already carries its own geotransform from
            // loadRasterData(). We only VALIDATE + LOG here.
            // ============================================================
            for (Layer layer : project.getLayers()) {
                if (layer instanceof RasterLayer rl) {
                    double lMinLon = rl.getMinLon(), lMaxLon = rl.getMaxLon();
                    double lMinLat = rl.getMinLat(), lMaxLat = rl.getMaxLat();
                    if (lMaxLon <= lMinLon || lMaxLat <= lMinLat) {
                        SimulationLogger.warning(
                            "Raster '%s' has invalid bounds (%.4f,%.4f)–(%.4f,%.4f) — "
                            + "falling back to study-site extent.",
                            layer.getName(), lMinLon, lMinLat, lMaxLon, lMaxLat);
                        rl.setBounds(worldBounds.getMinX(), worldBounds.getMaxX(),
                                     worldBounds.getMinY(), worldBounds.getMaxY());
                        continue;
                    }
                    // Coverage sanity check
                    double ovW = Math.min(lMaxLon, worldBounds.getMaxX())
                               - Math.max(lMinLon, worldBounds.getMinX());
                    double ovH = Math.min(lMaxLat, worldBounds.getMaxY())
                               - Math.max(lMinLat, worldBounds.getMinY());
                    double frac = (ovW > 0 && ovH > 0)
                        ? (ovW * ovH) / (worldBounds.getWidth() * worldBounds.getHeight())
                        : 0.0;
                    SimulationLogger.info(
                        "Raster '%s' bounds=(%.4f,%.4f)–(%.4f,%.4f), overlap with study site=%.1f%%",
                        layer.getName(), lMinLon, lMinLat, lMaxLon, lMaxLat, frac * 100);
                    if (frac < 0.5) {
                        SimulationLogger.warning(
                            "   ↳ Raster '%s' covers less than half the study site. "
                            + "Check CRS / extent.", layer.getName());
                    }
                } else if (layer instanceof InterpolatedRasterLayer irl) {
                    SimulationLogger.info(
                        "Climate layer '%s' bounds: lat [%.4f, %.4f], lon [%.4f, %.4f]",
                        layer.getName(), irl.getMinLat(), irl.getMaxLat(),
                        irl.getMinLon(), irl.getMaxLon());
                }
            }

            // ---- Seed initial population ----
            SimulationLogger.info("Seeding initial agents...");
            RasterLayer buildings  = (RasterLayer) project.getLayerByName("Buildings");
            RasterLayer population = (RasterLayer) project.getLayerByName("Population");

            if (buildings == null || population == null) {
                SimulationLogger.warning(
                    "Buildings or Population layer not found. Available: %s",
                    project.getLayers().stream().map(Layer::getName).toList());
                buildings  = (RasterLayer) project.getLayerByName("building_density");
                population = (RasterLayer) project.getLayerByName("Population");
            }

            if (buildings == null) {
                SimulationLogger.warning("Creating fallback Buildings layer");
                buildings = new MemoryMappedRasterLayer("Buildings", 100, 100, 1);
                buildings.setBounds(worldBounds.getMinX(), worldBounds.getMaxX(),
                                    worldBounds.getMinY(), worldBounds.getMaxY());
                project.addLayer(buildings);
            }
            if (population == null) {
                SimulationLogger.warning("Creating fallback Population layer");
                population = new MemoryMappedRasterLayer("Population", 100, 100, 1);
                population.setBounds(worldBounds.getMinX(), worldBounds.getMaxX(),
                                     worldBounds.getMinY(), worldBounds.getMaxY());
                project.addLayer(population);
            }

            seedInitialPopulation(project, config, spatialRegistry, worldBounds, studyAreaGeometry);

            // ---- Print summary ----
            int totalTanks = project.getAgentLayers().stream()
                .filter(l -> l.getName().equals("WaterTanks"))
                .findFirst().map(l -> l.getAgents().size()).orElse(0);
            int totalMosquitoes = project.getAgentLayers().stream()
                .filter(l -> l.getName().equals("Mosquitoes"))
                .findFirst().map(l -> l.getAgents().size()).orElse(0);
            SimulationLogger.info("Initial agents: %d tanks, %d mosquitoes",
                totalTanks, totalMosquitoes);
            SimulationLogger.info("All layers in project: %s",
                project.getLayers().stream().map(Layer::getName).toList());

        } catch (Exception e) {
            SimulationLogger.severe("Error configuring simulation: " + e.getMessage());
            e.printStackTrace();
        }
    }
   
    // ============================================================
    // DATA-DRIVEN LAYER LOADING
    // ============================================================

    private static void loadDataDrivenLayers(Project project, LayerFactory layerFactory,
                                             List<SimulationConfig.LayerDefinition> layerDefs) {
        for (SimulationConfig.LayerDefinition layerDef : layerDefs) {
            try {
                if (!layerDef.active) {
                    SimulationLogger.info("Skipping inactive layer: %s", layerDef.name);
                    continue;
                }

                // Handle NetCDF with multiple variables
                if (layerDef.variables != null && !layerDef.variables.isEmpty()) {
                    for (String varName : layerDef.variables) {
                        String subLayerName = layerDef.name + "_" + varName;
                        Layer layer = layerFactory.createLayer(layerDef.filePath, subLayerName, varName);
                        project.addLayer(layer);
                        SimulationLogger.info("  Added layer: %s (%s)", subLayerName, varName);
                    }
                    continue;
                }

                // Single layer from file
                Layer layer = layerFactory.createLayer(layerDef.filePath, layerDef.name, layerDef.variable);
                project.addLayer(layer);
                SimulationLogger.info("  Added layer: %s", layerDef.name);

            } catch (Exception e) {
                SimulationLogger.severe("Failed to load layer '%s': %s", layerDef.name, e.getMessage());
            }
        }
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private static void copySpeciesParams(SpeciesParameters target,
                                      SimulationConfig.SpeciesParameters source) {
        target.eggDev_rho = source.egg_dev_rho;
        target.eggDev_k = source.egg_dev_k;
        target.eggDev_Delta = source.egg_dev_Delta;
        target.eggDev_lambda = source.egg_dev_lambda;
        target.larvaDev_a = source.larva_dev_a;
        target.larvaDev_Tmin = source.larva_dev_Tmin;
        target.larvaDev_Tmax = source.larva_dev_Tmax;
        target.larvaDev_m = source.larva_dev_m;
        target.pupaDev_rho = source.pupa_dev_rho;
        target.pupaDev_k = source.pupa_dev_k;
        target.pupaDev_Delta = source.pupa_dev_Delta;
        target.pupaDev_lambda = source.pupa_dev_lambda;
        target.eggMort_b1 = source.egg_mort_b1;
        target.eggMort_b2 = source.egg_mort_b2;
        target.eggMort_b3 = source.egg_mort_b3;
        target.larvaMort_b1 = source.larva_mort_b1;
        target.larvaMort_b2 = source.larva_mort_b2;
        target.larvaMort_b3 = source.larva_mort_b3;
        target.pupaMort_b1 = source.pupa_mort_b1;
        target.pupaMort_b2 = source.pupa_mort_b2;
        target.pupaMort_b3 = source.pupa_mort_b3;
        target.fecundity_rmax = source.fecundity_rmax;
        target.fecundity_Topt = source.fecundity_Topt;
        target.fecundity_c = source.fecundity_c;
        target.fecundityScale = source.fecundity_scale;      // <-- NEW
        target.adultMortalityPerDay = source.adult_mortality_per_day;
        target.adultMort_b1 = source.adult_mort_b1;
        target.adultMort_b2 = source.adult_mort_b2;
        target.adultMort_b3 = source.adult_mort_b3;
        target.sexRatio = source.sex_ratio;
        target.hostCarryingCapacityBase = source.host_carrying_capacity_base;
    }


    // ============================================================
    // SEEDING
    // ============================================================

    private static void seedInitialPopulation(Project project,
                                          SimulationConfig config,
                                          SpatialRegistry spatialRegistry,
                                          Rectangle2D worldBounds,
                                          Geometry studyAreaGeometry) {
        SimulationConfig.SeedingConfig seeding = config.seeding;

        AgentLayer habitatLayer = project.getAgentLayers().stream()
            .filter(l -> l.getName().equals("WaterTanks"))
            .findFirst().orElse(null);

        AgentLayer mosquitoLayer = project.getAgentLayers().stream()
            .filter(l -> l.getName().equals("Mosquitoes"))
            .findFirst().orElse(null);

        if (habitatLayer == null || mosquitoLayer == null) {
            SimulationLogger.severe("ERROR: Cannot find agent layers for seeding");
            return;
        }

        // Load occurrence points if configured
        List<OccurrenceLoader.OccurrencePoint> occurrencePoints = new ArrayList<>();
        boolean useOccurrences = seeding.useOccurrencePoints &&
            seeding.occurrenceFilePath != null &&
            !seeding.occurrenceFilePath.isEmpty();

        if (useOccurrences) {
            try {
                occurrencePoints = OccurrenceLoader.loadOccurrences(seeding.occurrenceFilePath);
                occurrencePoints = OccurrenceLoader.filterByYear(occurrencePoints,
                    seeding.occurrenceYearStart, seeding.occurrenceYearEnd);
                if (occurrencePoints.isEmpty()) {
                    SimulationLogger.warning("No occurrence points found. Using uniform seeding.");
                    useOccurrences = false;
                } else {
                    SimulationLogger.info("Loaded %d occurrence points", occurrencePoints.size());
                }
            } catch (Exception e) {
                SimulationLogger.severe("Failed to load occurrences: " + e.getMessage());
                useOccurrences = false;
            }
        }

        if (seeding.seedAcrossFullStudySite || !useOccurrences) {
            seedUniformly(project, config, spatialRegistry, worldBounds,
                          studyAreaGeometry, habitatLayer, mosquitoLayer);
            return;
        }

        seedFromOccurrences(project, config, spatialRegistry, worldBounds,
                            studyAreaGeometry, habitatLayer, mosquitoLayer, occurrencePoints);
    }
    
    
    /**
    * Assemble a {@link WeightedRasterSuitability} from the tankSuitability
    * section of the config. Missing layers are dropped with a warning;
    * if the resulting spec list is empty, a uniform-in-bounds scorer is
    * produced (floor only) so seeding still works.
    */
    private static HabitatSuitabilityScorer buildHabitatScorer(
            Project project,
            SimulationConfig config,
            Rectangle2D worldBounds,
            Geometry studyAreaGeometry) {

        SimulationConfig.TankSuitabilityConfig sc = config.seeding.tankSuitability;
        if (sc == null) {
            SimulationLogger.warning("[Suitability] No tankSuitability block in config; "
                + "falling back to uniform-with-floor sampling.");
            sc = new SimulationConfig.TankSuitabilityConfig();
        }

        List<WeightedRasterSuitability.LayerSpec> specs = new ArrayList<>();
        for (SimulationConfig.SuitabilityLayer sl : sc.layers) {
            if (sl == null || sl.name == null) continue;
            if (sl.weight == 0.0) {
                SimulationLogger.info("[Suitability] Layer '%s' disabled (weight=0).", sl.name);
                continue;
            }
            Layer l = project.getLayerByName(sl.name);
            if (l == null) {
                SimulationLogger.warning("[Suitability] Layer '%s' referenced in config "
                    + "but not found in project; skipping.", sl.name);
                continue;
            }
            specs.add(new WeightedRasterSuitability.LayerSpec(l, sl.weight, sl.cap, sl.power));
            SimulationLogger.info("[Suitability] Layer '%s' enabled: weight=%.3f, cap=%.3g, power=%.3f",
                sl.name, sl.weight, sl.cap, sl.power);
        }

        int gx = Math.max(8, config.seeding.habitatGridSizeX);
        int gy = Math.max(8, config.seeding.habitatGridSizeY);

        // Deterministic per-run jitter: same seed → same jitter pattern.
        long jitterSeed = SeedManager.getCurrentSeed() * 31L + 17L;

        WeightedRasterSuitability scorer = new WeightedRasterSuitability(
            specs, worldBounds, studyAreaGeometry,
            gx, gy,
            sc.floorSuitability,
            sc.stochasticJitter,
            jitterSeed);

        // Force grid build now so we log stats before seeding (and any
        // errors surface before we start placing agents).
        scorer.sample(SeedManager.getRandom());
        SimulationLogger.info("[Suitability] Scorer ready: %s", scorer.getStats());

        return scorer;
    }

    private static void seedUniformly(Project project,
                                  SimulationConfig config,
                                  SpatialRegistry spatialRegistry,
                                  Rectangle2D worldBounds,
                                  Geometry studyAreaGeometry,
                                  AgentLayer habitatLayer,
                                  AgentLayer mosquitoLayer) {

        SimulationConfig.SeedingConfig seeding = config.seeding;
        Random rand = SeedManager.getRandom();

        SimulationLogger.info("Seeding (population-density-weighted): "
            + "%d tanks, %d mosquitoes",
            seeding.tanksToSeed, seeding.mosquitoesToSeed);

        // Single scorer for both tanks and mosquitoes. Both agents
        // preferentially get placed where the suitability field is high.
        HabitatSuitabilityScorer scorer = buildHabitatScorer(
            project, config, worldBounds, studyAreaGeometry);

        SimulationConfig.InertAgentParams inertParams = config.inert_agents;
        if (inertParams == null) inertParams = new SimulationConfig.InertAgentParams();

        // ---- Tanks --------------------------------------------------------
        int tanksPlaced = 0;
        int maxTankAttempts = Math.max(seeding.tanksToSeed * 3, 100);
        for (int attempt = 0; attempt < maxTankAttempts && tanksPlaced < seeding.tanksToSeed; attempt++) {
            double[] pt = scorer.sample(rand);
            double rx = pt[0], ry = pt[1];

            InertAgent tank = new InertAgent(rx, ry, inertParams);
            tank.setWaterVolume(70 + rand.nextDouble() * 30);
            tank.setLarvalCount(rand.nextInt(30) + 10);
            tank.setEggCount(rand.nextInt(80) + 20);
            tank.updateCapacityFromVolume();
            habitatLayer.addAgent(tank);
            spatialRegistry.registerAgent(tank);
            tanksPlaced++;
        }
        SimulationLogger.info("Seeded %d water tanks", tanksPlaced);

        // ---- Mosquitoes ---------------------------------------------------
        int mosquitoesPlaced = 0;
        int maxMosquitoAttempts = Math.max(seeding.mosquitoesToSeed * 2, 200);
        for (int attempt = 0; attempt < maxMosquitoAttempts
                && mosquitoesPlaced < seeding.mosquitoesToSeed; attempt++) {

            double[] pt = scorer.sample(rand);
            double rx = pt[0], ry = pt[1];

            LivingAgent mosquito = createMosquito(project, rand, rx, ry);
            mosquitoLayer.addAgent(mosquito);
            spatialRegistry.registerAgent(mosquito);
            mosquitoesPlaced++;
        }
        SimulationLogger.info("Seeded %d mosquitoes", mosquitoesPlaced);
    }


    private static void seedFromOccurrences(Project project,
                                        SimulationConfig config,
                                        SpatialRegistry spatialRegistry,
                                        Rectangle2D worldBounds,
                                        Geometry studyAreaGeometry,
                                        AgentLayer habitatLayer,
                                        AgentLayer mosquitoLayer,
                                        List<OccurrenceLoader.OccurrencePoint> occurrencePoints) {

        SimulationConfig.SeedingConfig seeding = config.seeding;
        Random rand = SeedManager.getRandom();
        double bufferDegrees = seeding.occurrenceBufferKm / 111.32;

        SimulationLogger.info("Occurrence-based seeding: %d points, buffer %.2f km",
            occurrencePoints.size(), seeding.occurrenceBufferKm);

        // Reuse the suitability scorer for placement (points still filtered
        // by proximity to occurrences). This keeps the two seeding paths
        // behaviorally consistent and drops the old hardcoded thresholds.
        HabitatSuitabilityScorer scorer = buildHabitatScorer(
            project, config, worldBounds, studyAreaGeometry);

        // Precompute occurrence coordinates
        List<double[]> occCoords = new ArrayList<>(occurrencePoints.size());
        for (OccurrenceLoader.OccurrencePoint p : occurrencePoints) {
            occCoords.add(new double[]{p.longitude, p.latitude});
        }

        SimulationConfig.InertAgentParams inertParams = config.inert_agents;
        if (inertParams == null) inertParams = new SimulationConfig.InertAgentParams();

        // ---- Tanks --------------------------------------------------------
        int tanksPlaced = 0;
        int maxAttempts = Math.max(seeding.tanksToSeed * 20, 2000);
        for (int attempt = 0; attempt < maxAttempts
                && tanksPlaced < seeding.tanksToSeed; attempt++) {

            double[] pt = scorer.sample(rand);
            double rx = pt[0], ry = pt[1];

            if (!withinBuffer(rx, ry, occCoords, bufferDegrees)) {
                // If we've tried a lot and still placed nothing, relax to the
                // whole study area so the run doesn't start empty.
                if (attempt < maxAttempts / 2) continue;
            }

            InertAgent tank = new InertAgent(rx, ry, inertParams);
            tank.setWaterVolume(70 + rand.nextDouble() * 30);
            tank.setLarvalCount(rand.nextInt(30) + 10);
            tank.setEggCount(rand.nextInt(80) + 20);
            tank.updateCapacityFromVolume();
            habitatLayer.addAgent(tank);
            spatialRegistry.registerAgent(tank);
            tanksPlaced++;
        }
        SimulationLogger.info("Seeded %d water tanks near occurrences", tanksPlaced);

        // ---- Mosquitoes ---------------------------------------------------
        int mosquitoesPlaced = 0;
        int perPoint = Math.max(1, seeding.mosquitoesToSeed / Math.max(1, occurrencePoints.size()));
        for (OccurrenceLoader.OccurrencePoint occ : occurrencePoints) {
            if (mosquitoesPlaced >= seeding.mosquitoesToSeed) break;
            int count = Math.min(perPoint, seeding.mosquitoesToSeed - mosquitoesPlaced);
            for (int i = 0; i < count; i++) {
                double lon = occ.longitude + (rand.nextDouble() - 0.5) * bufferDegrees;
                double lat = occ.latitude  + (rand.nextDouble() - 0.5) * bufferDegrees;
                LivingAgent mosquito = createMosquito(project, rand, lon, lat);
                mosquitoLayer.addAgent(mosquito);
                spatialRegistry.registerAgent(mosquito);
                mosquitoesPlaced++;
            }
        }
        SimulationLogger.info("Seeded %d mosquitoes at occurrences", mosquitoesPlaced);
    }
    

    private static boolean withinBuffer(double x, double y,
                                        List<double[]> coords,
                                        double bufferDeg) {
        double b2 = bufferDeg * bufferDeg;
        for (double[] c : coords) {
            double dx = c[0] - x, dy = c[1] - y;
            if (dx * dx + dy * dy <= b2) return true;
        }
        return false;
    }    
    
    
    private static LivingAgent createMosquito(Project project, Random rand, double x, double y) {
        LivingAgent mosquito = new LivingAgent(x, y);
        if (project != null) {
            mosquito.setGlobalMaxAge(project.getDefaultMaxAgentAge());
            mosquito.setAgeMultipliers(
                project.getMaxAgeMultiplierEgg(),
                project.getMaxAgeMultiplierLarva(),
                project.getMaxAgeMultiplierPupa(),
                project.getMaxAgeMultiplierAdult());
        }
        double r = rand.nextDouble();
        LifecycleStage stage = r < 0.6 ? LifecycleStage.ADULT :
                               (r < 0.85 ? LifecycleStage.LARVA : LifecycleStage.PUPA);
        mosquito.setStage(stage);

        switch (stage) {
            case ADULT:
                mosquito.setAge(rand.nextInt(10 * 24 * 4));
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
        return mosquito;
    }

    // ============================================================
    // SIMULATION CONTROL
    // ============================================================

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

    private static void monitorSimulation(SimulationEngine engine, Project project) {
        while (engine != null) {
            try {
                Thread.sleep(5000);
                Map<String, Object> state = engine.getState();
                boolean running = (Boolean) state.get("running");
                long tick = (Long) state.get("tick");

                if (!running || tick >= timeManager.getTotalTicks()) {
                    if (tick >= timeManager.getTotalTicks()) {
                        SimulationLogger.info("Simulation reached total ticks, stopping...");
                        engine.stop();
                    }
                    break;
                }

                int totalAgents = (Integer) state.getOrDefault("totalAgents", 0);
                double avgTickTime = (Double) state.getOrDefault("avgTickTime", 0.0);
                SimulationLogger.info("[Monitor] Tick: %d | Agents: %d | Avg Tick Time: %.2f ms",
                    tick, totalAgents, avgTickTime);

                if (avgTickTime > 10000) {
                    SimulationLogger.severe("CRITICAL: Tick time too slow (" + avgTickTime + "ms)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                SimulationLogger.severe("Error in monitor: " + e.getMessage());
                try { Thread.sleep(10000); } catch (InterruptedException ie) { break; }
            }
        }
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
                    Thread.sleep(30000);
                    try {
                        Map<String, Object> state = engine.getState();
                        long currentTick = (Long) state.get("tick");
                        long currentTime = System.currentTimeMillis();

                        if (currentTick == lastTick) {
                            stuckCount++;
                            if (stuckCount > 20) {
                                SimulationLogger.severe("CRITICAL: Simulation stuck, forcing shutdown");
                                saveFinalStatisticsToFile(project, spatialRegistry, engine, outputDir);
                                SnapshotMerger.mergeAfterSimulation();
                                engine.stop();
                                simulationThread.interrupt();
                                Thread.sleep(5000);
                                if (simulationThread.isAlive()) {
                                    SimulationLogger.severe("Thread still alive, forcing termination");
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
                        SimulationLogger.severe("Watchdog error: " + e.getMessage());
                        if (stuckCount++ > 5) {
                            SimulationLogger.severe("Cannot retrieve state, forcing shutdown");
                            saveFinalStatisticsToFile(project, spatialRegistry, engine, outputDir);
                            simulationThread.interrupt();
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        watchdog.setDaemon(true);
        watchdog.setName("Simulation-Watchdog");
        watchdog.setPriority(Thread.MIN_PRIORITY);
        watchdog.start();
        SimulationLogger.info("Watchdog thread started");
    }

    // ============================================================
    // STATISTICS
    // ============================================================

    private static void saveFinalStatisticsToFile(Project project,
                                                  SpatialRegistry spatialRegistry,
                                                  SimulationEngine engine,
                                                  String outputDir) {
        if (project == null || spatialRegistry == null) return;

        try {
            File resultsDir = new File(outputDir);
            if (!resultsDir.exists()) resultsDir.mkdirs();

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
                writer.printf("Timestamp: %s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                writer.printf("Total Runtime: %.2f seconds%n",
                    engine != null ? (System.currentTimeMillis() - simulationStartTime) / 1000.0 : 0);
                writer.println();

                writer.println("AGENT STATISTICS");
                writer.println("-".repeat(40));
                int totalAgents = 0;
                for (AgentLayer layer : project.getAgentLayers()) {
                    int count = layer.getAgents().size();
                    writer.printf("%-20s: %,9d agents%n", layer.getName(), count);
                    totalAgents += count;
                }
                writer.printf("%-20s: %,9d agents%n", "TOTAL", totalAgents);
                writer.println();

                writer.println("SPATIAL REGISTRY STATISTICS");
                writer.println("-".repeat(40));
                Map<String, Object> spatialStats = spatialRegistry.getStatistics();
                for (Map.Entry<String, Object> entry : spatialStats.entrySet()) {
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
                    Map<String, Object> state = engine.getState();
                    writer.printf("Final Tick: %d%n", state.get("tick"));
                    writer.printf("Average Tick Time: %.2f ms%n", state.get("avgTickTime"));
                }

                writer.println("=".repeat(80));
                SimulationLogger.info("Final statistics saved to: " + filename);
            }
        } catch (Exception e) {
            SimulationLogger.severe("Error saving statistics: " + e.getMessage());
        }
    }

}