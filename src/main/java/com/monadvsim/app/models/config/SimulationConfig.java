package com.monadvsim.app.models.config;
import com.monadvsim.app.models.utils.SimulationLogger;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class SimulationConfig {

    // Simulation time
    public SimulationTime time;
    // File paths (including study site)
    public FilePaths files;
    // Species parameters (merged)
    public SpeciesParameters species;
    // Agent layers and rules
    public List<AgentLayerConfig> agentLayers;
    // Seeding parameters
    public SeedingConfig seeding;
    // Project defaults
    public ProjectDefaults project;
    
    // Layer definitions - data-driven layer creation
    public List<LayerDefinition> layers;
    
    // Spatial registry grid cell size (degrees)
    public double gridCellSizeDegrees;
    
    // Tokens for rule engine (maps token name to layer name)
    public List<TokenMapping> tokens;
    
    public double maxAgeMultiplierEgg = 0.05;
    public double maxAgeMultiplierLarva = 0.45;
    public double maxAgeMultiplierPupa = 0.15;
    public double maxAgeMultiplierAdult = 0.35;

    public static class SimulationTime {
        public String startDateTime; // ISO format, e.g. "2025-09-01T00:00:00"
        public int totalTicks;       // e.g. 5760 (30 days * 24 * 4)
        public int tickMinutes;      // e.g. 15
    }

    public static class FilePaths {
        public String studySite;      // path to shapefile or QGIS project for bounds
        public String elevation;
        public String buildings;
        public String population;
        public String climateNetCDF; // single .nc file
        public List<String> climateFiles; // in case of multiple Multiple NetCDF files
    }
    
    
    public static class LayerDefinition {
        public String name;
        public String filePath;
        public String type;  // "raster", "timeseries", "vector", "occurrence"
        public String variable;  // For NetCDF: variable name to extract
        public boolean active = true;
        
        // For NetCDF files with multiple variables, you can specify which to load
        public List<String> variables;
    }

    
    public static class SpeciesParameters {
        // Egg development (exponential form)
        public double egg_dev_rho, egg_dev_k, egg_dev_Delta, egg_dev_lambda;

        // Larva development (Brière)
        public double larva_dev_a, larva_dev_Tmin, larva_dev_Tmax, larva_dev_m;

        // Pupa development (exponential form)
        public double pupa_dev_rho, pupa_dev_k, pupa_dev_Delta, pupa_dev_lambda;

        // Egg mortality (exp‑quadratic)
        public double egg_mort_b1, egg_mort_b2, egg_mort_b3;

        // Larva mortality (exp‑quadratic)
        public double larva_mort_b1, larva_mort_b2, larva_mort_b3;

        // Pupa mortality (exp‑quadratic)
        public double pupa_mort_b1, pupa_mort_b2, pupa_mort_b3;

        // Fecundity (Gaussian‑on‑log)
        public double fecundity_rmax, fecundity_Topt, fecundity_c;

        // Adult mortality (constant per day)
        public double adult_mortality_per_day;
        public double adult_mort_b1;
        public double adult_mort_b2;
        public double adult_mort_b3;

        // Sex ratio (proportion females)
        public double sex_ratio;
        public double hostCarryingCapacityBase = 1.0;
    }

    
    public static class AgentLayerConfig {
        public String name;
        public List<RuleConfig> rules;
    }

    public static class RuleConfig {
        public String condition;
        public String action;
        public int priority;
    }

    public static class SeedingConfig {
        public int tanksToSeed;
        public int mosquitoesToSeed;
        public int habitatGridSizeX;
        public int habitatGridSizeY;
        public double tankBuildingThreshold;
        public double tankPopulationThreshold;
        public double mosquitoBuildingThreshold;
        public double mosquitoPopulationThreshold;
        public boolean seedAcrossFullStudySite = false;
        public boolean useOccurrencePoints = false;
        public String occurrenceFilePath = "";
        public int occurrenceYearStart = 0;
        public int occurrenceYearEnd = 9999;
        public double occurrenceBufferKm = 5.0;  // Buffer around each point
    }

    public static class ProjectDefaults {
        public double defaultAgentSearchRadius;
        public double defaultAgentStep;
        public int defaultMaxAgentAge; // in ticks
    }

    public static class TokenMapping {
        public String token;
        public String layer;
    }
}