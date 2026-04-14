package com.monadvsim.app.models.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class SimulationConfig {

    // Simulation time
    public SimulationTime time;
    // World bounds
    public WorldBounds world;
    // File paths
    public FilePaths files;
    // Species parameters (merged)
    public SpeciesParameters species;
    // Agent layers and rules
    public List<AgentLayerConfig> agentLayers;
    // Seeding parameters
    public SeedingConfig seeding;
    // Project defaults
    public ProjectDefaults project;

    public static class SimulationTime {
        public String startDateTime; // ISO format, e.g. "2025-09-01T00:00:00"
        public int totalTicks;       // e.g. 5760 (30 days * 24 * 4)
        public int tickMinutes;      // e.g. 15
    }

    public static class WorldBounds {
        public double centerLat;
        public double centerLon;
        public double bufferKm;
        public double cellSize;      // for spatial registry
    }

    public static class FilePaths {
        public String elevation;
        public String buildings;
        public String population;
        public String climateNetCDF;
    }

    public static class SpeciesParameters {
        // same fields as your existing SpeciesParameters class
        public double fecundity_a, fecundity_b, fecundity_Tmax, fecundity_c;
        public double egg_dev_a, egg_dev_b, egg_dev_c;
        public double larva_dev_a, larva_dev_b, larva_dev_c;
        public double pupa_dev_a, pupa_dev_b, pupa_dev_c;
        public double egg_survival_amp, egg_survival_mean, egg_survival_sigma;
        public double larva_survival_amp, larva_survival_mean, larva_survival_sigma;
        public double pupa_survival_amp, pupa_survival_mean, pupa_survival_sigma;
        public double adult_mort_a, adult_mort_b, adult_mort_c;
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
    }

    public static class ProjectDefaults {
        public double defaultAgentSearchRadius;
        public double defaultAgentStep;
        public int defaultMaxAgentAge; // in ticks
    }
}