#!/usr/bin/env python3
# run_monte_carlo.py
# Monte Carlo simulation runner for mosquito ABM with parameter variation
#
# Usage:
#   python run_monte_carlo.py [--runs N] [--seed S] [--output DIR]

import os
import subprocess
import glob
import time
import argparse
import sys
import json
import csv
import yaml
from datetime import datetime
from pathlib import Path
import re
import threading
import random
import numpy as np
from typing import Dict, List, Optional, Tuple, Any

# ======================================================================
# CONFIGURATION
# ======================================================================

DEFAULT_OUTPUT_DIR = "/mnt/monadworld/projects/phd/monte_carlo_results"
DEFAULT_BASE_SEED = 42
DEFAULT_RUNS = 30
TIMEOUT_SECONDS = 7200  # 2 hours per run

JAR_PATHS = [
    "target/monadvsim-1.0-SNAPSHOT.jar",
    "target/monadvsim-1.0-SNAPSHOT-shaded.jar",
    "target/monadvsim.jar",
    "monadvsim-1.0-SNAPSHOT.jar",
    "monadvsim.jar"
]

# ======================================================================
# BASE YAML CONFIGURATION - MATCHING YOUR simulation.yaml
# ======================================================================

BASE_YAML_TEMPLATE = """# ================================================================
# SIMULATION CONFIGURATION - Monte Carlo Run {run_id}
# Seed: {seed}
# ================================================================

time:
  startDateTime: "2020-01-01T00:00:00"
  totalTicks: {total_ticks}
  tickMinutes: 15

files:
  studySite: "prepared_data/somali/somali.shp"

gridCellSizeDegrees: {grid_cell_size}

# ================================================================
# SPECIES PARAMETERS
# ================================================================
species:
  egg_dev_rho: {egg_dev_rho}
  egg_dev_k: {egg_dev_k}
  egg_dev_Delta: {egg_dev_Delta}
  egg_dev_lambda: {egg_dev_lambda}
  larva_dev_a: {larva_dev_a}
  larva_dev_Tmin: {larva_dev_Tmin}
  larva_dev_Tmax: {larva_dev_Tmax}
  larva_dev_m: {larva_dev_m}
  pupa_dev_rho: {pupa_dev_rho}
  pupa_dev_k: {pupa_dev_k}
  pupa_dev_Delta: {pupa_dev_Delta}
  pupa_dev_lambda: {pupa_dev_lambda}
  egg_mort_b1: {egg_mort_b1}
  egg_mort_b2: {egg_mort_b2}
  egg_mort_b3: {egg_mort_b3}
  larva_mort_b1: {larva_mort_b1}
  larva_mort_b2: {larva_mort_b2}
  larva_mort_b3: {larva_mort_b3}
  pupa_mort_b1: {pupa_mort_b1}
  pupa_mort_b2: {pupa_mort_b2}
  pupa_mort_b3: {pupa_mort_b3}
  fecundity_rmax: {fecundity_rmax}
  fecundity_Topt: {fecundity_Topt}
  fecundity_c: {fecundity_c}
  adult_mort_b1: {adult_mort_b1}
  adult_mort_b2: {adult_mort_b2}
  adult_mort_b3: {adult_mort_b3}
  adult_mortality_per_day: {adult_mortality_per_day}
  sex_ratio: {sex_ratio}
  host_carrying_capacity_base: {host_carrying_capacity_base}

# ================================================================
# DATA LAYER DEFINITIONS
# ================================================================
layers:
  - name: Elevation
    filePath: prepared_data/somali/Small_Somali_Elevation_10m.tif
    type: raster

  - name: Buildings
    filePath: prepared_data/somali/Small_Somali_Building_Density_10m.tif
    type: raster

  - name: Population
    filePath: prepared_data/somali/population_2020_1km.tif
    type: raster

  - name: t2m
    filePath: prepared_data/somali/merged_2020_2023.nc
    type: timeseries
    variable: t2m

  - name: tp
    filePath: prepared_data/somali/merged_2020_2023.nc
    type: timeseries
    variable: tp

# ================================================================
# TOKENS FOR RULE ENGINE
# ================================================================
tokens:
  - token: "temperature"
    layer: "t2m"
  - token: "precipitation"
    layer: "tp"
  - token: "population"
    layer: "Population"
  - token: "building_density"
    layer: "Buildings"
  - token: "elevation"
    layer: "Elevation"

# ================================================================
# AGENT LAYERS WITH ENHANCED RULES
# ================================================================
agentLayers:
  - name: "Mosquitoes"
    rules:
      - condition: "stage == 'LARVA' && stageAgeTick > 200 && energy > 0.4"
        action: "pupate"
        priority: 5

      - condition: "stage == 'PUPA' && stageAgeTick > 150"
        action: "emerge"
        priority: 5

      - condition: "stage == 'LARVA' && getTotalLarvae > 15000 && stageAgeTick > 100"
        action: "pupate"
        priority: 6

      - condition: "stage == 'ADULT' && energy < {feeding_energy_threshold} && !resting"
        action: "feed"
        priority: 9

      - condition: "stage == 'ADULT' && energy < {resting_energy_threshold} && resting"
        action: "rest"
        priority: 8

      - condition: "stage == 'ADULT' && gravid && energy < 0.8"
        action: "feed"
        priority: 8

      - condition: "stage == 'ADULT' && energy > {gravid_energy_threshold} && !gravid"
        action: "get_gravid"
        priority: 8

      - condition: "gravid == true && temperature > {egg_laying_temp_min} && getTotalLarvae < 30000"
        action: "lay_eggs"
        priority: 7

      - condition: "temperature < {temp_min_survival} || temperature > {temp_max_survival}"
        action: "die"
        priority: 10

      - condition: "(precipitation > {rain_threshold} || temperature < {shelter_temp_threshold}) && building_density > {building_density_threshold} && !resting"
        action: "rest_in_building"
        priority: 6

      - condition: "stage == 'ADULT' && !resting && stageAgeTick < 2000"
        action: "move_random"
        priority: 3

      - condition: "true"
        action: "rest"
        priority: 2

      - condition: "agent.gravid && agent.alive"
        action: "seek_tank"
        priority: 90

      - condition: "agent.gravid && agent.alive"
        action: "lay_eggs"
        priority: 80

  - name: "WaterTanks"
    rules:
      - condition: "true"
        action: "evaporate"
        priority: 1

      - condition: "larvalCount > capacity * 0.7 && waterVolume > 0"
        action: "thin_larvae"
        priority: 5

      - condition: "eggCount > 150 && waterVolume > 15"
        action: "hatch_eggs"
        priority: 6

      - condition: "waterVolume <= {dry_out_threshold} && waterVolume > 0"
        action: "dry_out"
        priority: 4

      - condition: "temperature < {freeze_temp} && waterVolume > 0"
        action: "freeze"
        priority: 3

      - condition: "waterVolume == 0 && eggCount < 10 && larvalCount < 10"
        action: "die"
        priority: 4

# ================================================================
# InertAgent (Water Tank) Configuration
# ================================================================
inert_agents:
  max_larvae_capacity: {max_larvae_capacity}
  mortality_intensity: {mortality_intensity}
  min_larvae_retain: {min_larvae_retain}
  max_water_volume: 100.0
  hatch_fraction_min: {hatch_fraction_min}
  hatch_fraction_max: {hatch_fraction_max}

# ================================================================
# SEEDING CONFIGURATION
# ================================================================
seeding:
  seedAcrossFullStudySite: {seed_across_full_study_site}
  useOccurrencePoints: {use_occurrence_points}
  occurrenceFilePath: "prepared_data/ethiopia_occurrence/merged_observations.csv"
  occurrenceYearStart: {occurrence_year_start}
  occurrenceYearEnd: {occurrence_year_end}
  occurrenceBufferKm: {occurrence_buffer_km}
  tanksToSeed: {tanks_to_seed}
  mosquitoesToSeed: {mosquitoes_to_seed}
  habitatGridSizeX: {habitat_grid_x}
  habitatGridSizeY: {habitat_grid_y}
  tankBuildingThreshold: {tank_building_threshold}
  tankPopulationThreshold: {tank_population_threshold}
  mosquitoBuildingThreshold: {mosquito_building_threshold}
  mosquitoPopulationThreshold: {mosquito_population_threshold}

# ================================================================
# PROJECT DEFAULTS
# ================================================================
project:
  defaultAgentSearchRadius: {agent_search_radius}
  defaultAgentStep: {agent_step}
  defaultMaxAgentAge: {max_agent_age}
  maxAgeMultiplierEgg: 0.05
  maxAgeMultiplierLarva: 0.45
  maxAgeMultiplierPupa: 0.15
  maxAgeMultiplierAdult: 0.35
"""


# ======================================================================
# PARAMETER RANGES - Matches your species parameters
# ======================================================================

PARAMETER_RANGES = {
    # --- Grid & Spatial ---
    'grid_cell_size': {
        'type': 'uniform',
        'min': 0.0005,
        'max': 0.002,
        'base': 0.001,
        'description': 'Grid cell size in degrees',
        'category': 'spatial'
    },
    
    # --- Egg Development ---
    'egg_dev_rho': {
        'type': 'uniform',
        'min': 0.0045,
        'max': 0.0055,
        'base': 0.0050,
        'description': 'Egg development rate parameter',
        'category': 'development'
    },
    'egg_dev_k': {
        'type': 'uniform',
        'min': 38.0,
        'max': 40.5,
        'base': 39.208,
        'description': 'Egg development thermal constant',
        'category': 'development'
    },
    'egg_dev_Delta': {
        'type': 'uniform',
        'min': 1.8,
        'max': 2.2,
        'base': 2.0,
        'description': 'Egg development temperature sensitivity',
        'category': 'development'
    },
    'egg_dev_lambda': {
        'type': 'uniform',
        'min': -0.95,
        'max': -0.75,
        'base': -0.855,
        'description': 'Egg development minimum rate',
        'category': 'development'
    },
    
    # --- Larval Development ---
    'larva_dev_a': {
        'type': 'uniform',
        'min': 2.5e-5,
        'max': 2.9e-5,
        'base': 2.705e-5,
        'description': 'Larval development rate coefficient',
        'category': 'development'
    },
    'larva_dev_Tmin': {
        'type': 'uniform',
        'min': 4.5,
        'max': 5.8,
        'base': 5.123,
        'description': 'Larval development minimum temperature (°C)',
        'category': 'development'
    },
    'larva_dev_Tmax': {
        'type': 'uniform',
        'min': 43.0,
        'max': 46.0,
        'base': 45.0,
        'description': 'Larval development maximum temperature (°C)',
        'category': 'development'
    },
    'larva_dev_m': {
        'type': 'uniform',
        'min': 1.5,
        'max': 1.8,
        'base': 1.663,
        'description': 'Larval development shape parameter',
        'category': 'development'
    },
    
    # --- Pupal Development ---
    'pupa_dev_rho': {
        'type': 'uniform',
        'min': 0.0045,
        'max': 0.0057,
        'base': 0.005115,
        'description': 'Pupal development rate parameter',
        'category': 'development'
    },
    'pupa_dev_k': {
        'type': 'uniform',
        'min': 38.5,
        'max': 41.0,
        'base': 39.94,
        'description': 'Pupal development thermal constant',
        'category': 'development'
    },
    'pupa_dev_Delta': {
        'type': 'uniform',
        'min': 1.8,
        'max': 2.2,
        'base': 2.0,
        'description': 'Pupal development temperature sensitivity',
        'category': 'development'
    },
    'pupa_dev_lambda': {
        'type': 'uniform',
        'min': -1.0,
        'max': -0.8,
        'base': -0.908,
        'description': 'Pupal development minimum rate',
        'category': 'development'
    },
    
    # --- Egg Mortality ---
    'egg_mort_b1': {
        'type': 'uniform',
        'min': 3.2,
        'max': 3.9,
        'base': 3.573,
        'description': 'Egg mortality intercept',
        'category': 'mortality'
    },
    'egg_mort_b2': {
        'type': 'uniform',
        'min': -0.36,
        'max': -0.28,
        'base': -0.323,
        'description': 'Egg mortality temperature coefficient',
        'category': 'mortality'
    },
    'egg_mort_b3': {
        'type': 'uniform',
        'min': 0.0045,
        'max': 0.0054,
        'base': 0.00494,
        'description': 'Egg mortality quadratic term',
        'category': 'mortality'
    },
    
    # --- Larval Mortality ---
    'larva_mort_b1': {
        'type': 'uniform',
        'min': 1.7,
        'max': 2.3,
        'base': 2.0,
        'description': 'Larval mortality intercept',
        'category': 'mortality'
    },
    'larva_mort_b2': {
        'type': 'uniform',
        'min': -0.85,
        'max': -0.65,
        'base': -0.7395,
        'description': 'Larval mortality temperature coefficient',
        'category': 'mortality'
    },
    'larva_mort_b3': {
        'type': 'uniform',
        'min': 0.015,
        'max': 0.020,
        'base': 0.01749,
        'description': 'Larval mortality quadratic term',
        'category': 'mortality'
    },
    
    # --- Pupal Mortality ---
    'pupa_mort_b1': {
        'type': 'uniform',
        'min': 5.2,
        'max': 6.5,
        'base': 5.883,
        'description': 'Pupal mortality intercept',
        'category': 'mortality'
    },
    'pupa_mort_b2': {
        'type': 'uniform',
        'min': -0.64,
        'max': -0.52,
        'base': -0.579,
        'description': 'Pupal mortality temperature coefficient',
        'category': 'mortality'
    },
    'pupa_mort_b3': {
        'type': 'uniform',
        'min': 0.0085,
        'max': 0.0105,
        'base': 0.00946,
        'description': 'Pupal mortality quadratic term',
        'category': 'mortality'
    },
    
    # --- Adult Mortality ---
    'adult_mort_b1': {
        'type': 'uniform',
        'min': -1.7,
        'max': -1.3,
        'base': -1.478,
        'description': 'Adult mortality intercept',
        'category': 'mortality'
    },
    'adult_mort_b2': {
        'type': 'uniform',
        'min': -0.16,
        'max': -0.11,
        'base': -0.138,
        'description': 'Adult mortality temperature coefficient',
        'category': 'mortality'
    },
    'adult_mort_b3': {
        'type': 'uniform',
        'min': 0.0035,
        'max': 0.0043,
        'base': 0.00391,
        'description': 'Adult mortality quadratic term',
        'category': 'mortality'
    },
    'adult_mortality_per_day': {
        'type': 'uniform',
        'min': 0.08,
        'max': 0.16,
        'base': 0.12,
        'description': 'Adult base daily mortality rate',
        'category': 'mortality'
    },
    
    # --- Host Carrying Capacity ---
    'host_carrying_capacity_base': {
        'type': 'uniform',
        'min': 0.5,
        'max': 3.0,
        'base': 2.0,
        'description': 'Base host carrying capacity',
        'category': 'fecundity'
    },
    
    # --- Fecundity ---
    'fecundity_rmax': {
        'type': 'uniform',
        'min': 1.4,
        'max': 1.8,
        'base': 1.602,
        'description': 'Maximum fecundity rate',
        'category': 'fecundity'
    },
    'fecundity_Topt': {
        'type': 'uniform',
        'min': 29.0,
        'max': 32.0,
        'base': 30.634,
        'description': 'Optimal temperature for fecundity (°C)',
        'category': 'fecundity'
    },
    'fecundity_c': {
        'type': 'uniform',
        'min': -0.006,
        'max': -0.0045,
        'base': -0.00527,
        'description': 'Fecundity temperature sensitivity',
        'category': 'fecundity'
    },
    'sex_ratio': {
        'type': 'uniform',
        'min': 0.45,
        'max': 0.55,
        'base': 0.5,
        'description': 'Sex ratio (proportion female)',
        'category': 'fecundity'
    },
    
    # --- Behavioral Thresholds ---
    'feeding_energy_threshold': {
        'type': 'uniform',
        'min': 0.5,
        'max': 0.7,
        'base': 0.6,
        'description': 'Energy threshold for feeding',
        'category': 'behavior'
    },
    'resting_energy_threshold': {
        'type': 'uniform',
        'min': 0.7,
        'max': 0.9,
        'base': 0.8,
        'description': 'Energy threshold for resting while resting',
        'category': 'behavior'
    },
    'gravid_energy_threshold': {
        'type': 'uniform',
        'min': 0.4,
        'max': 0.7,
        'base': 0.5,
        'description': 'Energy threshold for becoming gravid',
        'category': 'behavior'
    },
    'egg_laying_temp_min': {
        'type': 'uniform',
        'min': 278.0,
        'max': 285.0,
        'base': 280.15,
        'description': 'Minimum temperature for egg laying (K)',
        'category': 'behavior'
    },
    'temp_min_survival': {
        'type': 'uniform',
        'min': 270.0,
        'max': 278.0,
        'base': 273.15,
        'description': 'Minimum temperature for survival (K)',
        'category': 'behavior'
    },
    'temp_max_survival': {
        'type': 'uniform',
        'min': 310.0,
        'max': 322.0,
        'base': 318.15,
        'description': 'Maximum temperature for survival (K)',
        'category': 'behavior'
    },
    'rain_threshold': {
        'type': 'uniform',
        'min': 0.005,
        'max': 0.015,
        'base': 0.008,
        'description': 'Rain threshold for seeking shelter',
        'category': 'behavior'
    },
    'shelter_temp_threshold': {
        'type': 'uniform',
        'min': 283.0,
        'max': 288.0,
        'base': 285.15,
        'description': 'Temperature threshold for seeking shelter (K)',
        'category': 'behavior'
    },
    'building_density_threshold': {
        'type': 'uniform',
        'min': 0.05,
        'max': 0.2,
        'base': 0.1,
        'description': 'Building density threshold for shelter',
        'category': 'behavior'
    },
    
    # --- Water Tank Rules ---
    'dry_out_threshold': {
        'type': 'uniform',
        'min': 2.0,
        'max': 8.0,
        'base': 5.0,
        'description': 'Water volume threshold for drying out',
        'category': 'water'
    },
    'freeze_temp': {
        'type': 'uniform',
        'min': 268.0,
        'max': 275.0,
        'base': 273.15,
        'description': 'Temperature for freezing (K)',
        'category': 'water'
    },
    
    # --- InertAgent Parameters ---
    'max_larvae_capacity': {
        'type': 'int_uniform',
        'min': 500,
        'max': 2000,
        'base': 1000,
        'description': 'Maximum larvae capacity per tank',
        'category': 'water'
    },
    'mortality_intensity': {
        'type': 'uniform',
        'min': 0.2,
        'max': 0.6,
        'base': 0.4,
        'description': 'Density-dependent mortality intensity',
        'category': 'water'
    },
    'min_larvae_retain': {
        'type': 'int_uniform',
        'min': 10,
        'max': 50,
        'base': 20,
        'description': 'Minimum larvae to retain after density mortality',
        'category': 'water'
    },
    'hatch_fraction_min': {
        'type': 'uniform',
        'min': 0.05,
        'max': 0.2,
        'base': 0.1,
        'description': 'Minimum hatch fraction per tick',
        'category': 'water'
    },
    'hatch_fraction_max': {
        'type': 'uniform',
        'min': 0.4,
        'max': 0.8,
        'base': 0.6,
        'description': 'Maximum hatch fraction per tick',
        'category': 'water'
    },
    
    # --- Simulation Duration ---
    'total_ticks': {
        'type': 'int_uniform',
        'min': 1440,   # 15 days
        'max': 4320,   # 45 days
        'base': 2880,  # 30 days
        'description': 'Total simulation ticks (15-min ticks)',
        'category': 'simulation'
    },
    
    # --- Seeding ---
    'seed_across_full_study_site': {
        'type': 'choice',
        'options': [False, True],
        'base': False,
        'description': 'Seed across full study site or use weighted habitat',
        'category': 'seeding'
    },
    'use_occurrence_points': {
        'type': 'choice',
        'options': [False, True],
        'base': True,
        'description': 'Use occurrence points for seeding',
        'category': 'seeding'
    },
    'tanks_to_seed': {
        'type': 'int_uniform',
        'min': 2000,
        'max': 8000,
        'base': 5000,
        'description': 'Number of tanks to seed',
        'category': 'seeding'
    },
    'mosquitoes_to_seed': {
        'type': 'int_uniform',
        'min': 10000,
        'max': 30000,
        'base': 20000,
        'description': 'Number of mosquitoes to seed',
        'category': 'seeding'
    },
    'habitat_grid_x': {
        'type': 'int_uniform',
        'min': 50,
        'max': 150,
        'base': 100,
        'description': 'Habitat grid X dimension',
        'category': 'seeding'
    },
    'habitat_grid_y': {
        'type': 'int_uniform',
        'min': 50,
        'max': 150,
        'base': 100,
        'description': 'Habitat grid Y dimension',
        'category': 'seeding'
    },
    'tank_building_threshold': {
        'type': 'uniform',
        'min': 0.05,
        'max': 0.25,
        'base': 0.15,
        'description': 'Building threshold for tank seeding',
        'category': 'seeding'
    },
    'tank_population_threshold': {
        'type': 'uniform',
        'min': 0.01,
        'max': 0.1,
        'base': 0.05,
        'description': 'Population threshold for tank seeding',
        'category': 'seeding'
    },
    'mosquito_building_threshold': {
        'type': 'uniform',
        'min': 0.01,
        'max': 0.1,
        'base': 0.05,
        'description': 'Building threshold for mosquito seeding',
        'category': 'seeding'
    },
    'mosquito_population_threshold': {
        'type': 'uniform',
        'min': 0.005,
        'max': 0.05,
        'base': 0.02,
        'description': 'Population threshold for mosquito seeding',
        'category': 'seeding'
    },
    'occurrence_year_start': {
        'type': 'int_uniform',
        'min': 2016,
        'max': 2019,
        'base': 2016,
        'description': 'Earliest year to include from occurrence data',
        'category': 'seeding'
    },
    'occurrence_year_end': {
        'type': 'int_uniform',
        'min': 2019,
        'max': 2026,
        'base': 2023,
        'description': 'Latest year to include from occurrence data',
        'category': 'seeding'
    },
    'occurrence_buffer_km': {
        'type': 'uniform',
        'min': 0.01,
        'max': 0.1,
        'base': 0.05,
        'description': 'Buffer radius around occurrence points (km)',
        'category': 'seeding'
    },
    
    # --- Project Defaults ---
    'agent_search_radius': {
        'type': 'uniform',
        'min': 0.005,
        'max': 0.04,
        'base': 0.02,
        'description': 'Agent search radius (degrees)',
        'category': 'project'
    },
    'agent_step': {
        'type': 'uniform',
        'min': 0.0001,
        'max': 0.001,
        'base': 0.0005,
        'description': 'Agent step size (degrees)',
        'category': 'project'
    },
    'max_agent_age': {
        'type': 'int_uniform',
        'min': 2000,
        'max': 4000,
        'base': 2880,
        'description': 'Maximum agent age (ticks)',
        'category': 'project'
    },
}


# ======================================================================
# PARAMETER GENERATION FUNCTIONS
# ======================================================================

def generate_parameter_value(param_name: str, param_spec: Dict, seed_offset: int = 0) -> Any:
    """Generate a parameter value within the specified range."""
    
    rng = random.Random(seed_offset + hash(param_name) % 1000000)
    
    param_type = param_spec.get('type', 'base')
    
    if param_type == 'uniform':
        return rng.uniform(param_spec['min'], param_spec['max'])
    elif param_type == 'int_uniform':
        return rng.randint(param_spec['min'], param_spec['max'])
    elif param_type == 'choice':
        return rng.choice(param_spec['options'])
    else:
        return param_spec['base']


def generate_parameter_set(seed: int, param_ranges: Dict = PARAMETER_RANGES) -> Dict:
    """Generate a complete set of parameters for a simulation run."""
    
    params = {}
    
    for param_name, param_spec in param_ranges.items():
        params[param_name] = generate_parameter_value(
            param_name, param_spec, seed
        )
    
    # Ensure correlated parameters make sense
    # Tmin < Tmax for larval development
    if 'larva_dev_Tmin' in params and 'larva_dev_Tmax' in params:
        if params['larva_dev_Tmin'] >= params['larva_dev_Tmax']:
            params['larva_dev_Tmin'] = params['larva_dev_Tmax'] - 2.0
    
    # Topt should be between Tmin and Tmax for fecundity
    if all(k in params for k in ['fecundity_Topt', 'larva_dev_Tmin', 'larva_dev_Tmax']):
        if not (params['larva_dev_Tmin'] < params['fecundity_Topt'] < params['larva_dev_Tmax']):
            params['fecundity_Topt'] = (params['larva_dev_Tmin'] + params['larva_dev_Tmax']) / 2
    
    # Temperature thresholds should be consistent
    if 'egg_laying_temp_min' in params and 'temp_min_survival' in params:
        if params['egg_laying_temp_min'] < params['temp_min_survival']:
            params['egg_laying_temp_min'] = params['temp_min_survival'] + 3.0
    
    if 'temp_max_survival' in params and 'temp_min_survival' in params:
        if params['temp_max_survival'] <= params['temp_min_survival']:
            params['temp_max_survival'] = params['temp_min_survival'] + 20.0
    
    return params


def generate_yaml_config(seed: int, params: Dict, output_dir: str, run_id: int) -> str:
    """Generate a YAML configuration string from parameters."""
    
    # Get all template keys
    import re as regex
    template_keys = regex.findall(r'\{([^}]+)\}', BASE_YAML_TEMPLATE)
    
    # Build the format dict with only keys that exist in the template
    format_dict = {
        'run_id': run_id,
        'seed': seed,
    }
    for key in template_keys:
        if key in params:
            format_dict[key] = params[key]
        else:
            # Use a default value if parameter is not in params
            print(f"Warning: Parameter '{key}' not found in params, using 0.0")
            format_dict[key] = 0.0
    
    return BASE_YAML_TEMPLATE.format(**format_dict)


def save_parameter_summary(seed: int, params: Dict, output_dir: str) -> str:
    """Save a summary of parameters used for a run."""
    
    seed_dir = os.path.join(output_dir, f"seed_{seed}")
    os.makedirs(seed_dir, exist_ok=True)
    
    summary_file = os.path.join(seed_dir, "parameters_used.yaml")
    with open(summary_file, 'w') as f:
        yaml.dump(params, f, default_flow_style=False)
    
    # Also save as CSV for easy comparison
    csv_file = os.path.join(seed_dir, "parameters_used.csv")
    with open(csv_file, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['parameter', 'value', 'category'])
        for key, value in sorted(params.items()):
            category = PARAMETER_RANGES.get(key, {}).get('category', 'unknown')
            writer.writerow([key, value, category])
    
    return summary_file


def get_parameter_descriptions() -> Dict:
    """Get descriptions of all parameters for documentation."""
    
    descriptions = {}
    for param_name, param_spec in PARAMETER_RANGES.items():
        descriptions[param_name] = {
            'description': param_spec.get('description', ''),
            'range': f"{param_spec.get('min', 'N/A')} - {param_spec.get('max', 'N/A')}",
            'base': param_spec.get('base', 'N/A'),
            'category': param_spec.get('category', 'unknown')
        }
    return descriptions


# ======================================================================
# FILE MONITORING
# ======================================================================

def monitor_output_files(seed_dir: str, stop_event: threading.Event, check_interval: int = 10):
    """Monitor output files being created in real-time."""
    
    seen_files = set()
    print(f"\n[Monitor] Starting file watcher for {seed_dir}")
    
    while not stop_event.is_set():
        try:
            if os.path.exists(seed_dir):
                current_files = set(os.listdir(seed_dir))
                new_files = current_files - seen_files
                
                if new_files:
                    for f in new_files:
                        if f.endswith('.csv') or f.endswith('.log') or f.endswith('.txt'):
                            fpath = os.path.join(seed_dir, f)
                            size = os.path.getsize(fpath) if os.path.exists(fpath) else 0
                            print(f"[Monitor] New file: {f} ({size/1024:.1f} KB)")
                    
                    snapshots = [f for f in new_files if f.startswith('snapshot_tick_') and f.endswith('.csv')]
                    if snapshots:
                        print(f"[Monitor] 📸 Found {len(snapshots)} new snapshot(s): {', '.join(snapshots[:5])}")
                    
                    merged = [f for f in new_files if f.startswith('merged_snapshots_') and f.endswith('.csv')]
                    if merged:
                        print(f"[Monitor] 📊 Found merged snapshot: {merged[0]}")
                    
                    seen_files.update(current_files)
            
            time.sleep(check_interval)
        except Exception as e:
            print(f"[Monitor] Error: {e}")
            time.sleep(check_interval)


# ======================================================================
# SIMULATION RUNNER
# ======================================================================

def find_jar() -> str:
    """Find the fat JAR file."""
    
    for path in JAR_PATHS:
        if os.path.exists(path):
            return os.path.abspath(path)
    
    if os.path.exists("pom.xml"):
        print("No JAR found. Building with Maven...")
        try:
            result = subprocess.run(
                ["mvn", "clean", "package", "-DskipTests"], 
                check=True, capture_output=True, text=True
            )
            print(result.stdout)
            for path in JAR_PATHS:
                if os.path.exists(path):
                    return os.path.abspath(path)
        except subprocess.CalledProcessError as e:
            print(f"Build failed: {e.stderr if e.stderr else 'Unknown error'}")
        except Exception as e:
            print(f"Build error: {e}")
    
    jar_files = glob.glob("*.jar") + glob.glob("target/*.jar")
    if jar_files:
        return os.path.abspath(jar_files[0])
    
    print("ERROR: No JAR file found!")
    print("Please build your project first:")
    print("  mvn clean package")
    sys.exit(1)


def run_simulation(seed: int, output_dir: str, jar_path: Optional[str] = None, 
                   timeout: int = TIMEOUT_SECONDS, verbose: bool = True,
                   params: Optional[Dict] = None, config_file: Optional[str] = None,
                   run_id: Optional[int] = None) -> Optional[str]:
    """
    Run a single simulation with the given seed and parameters.
    
    Args:
        seed: Random seed for the simulation
        output_dir: Base output directory
        jar_path: Path to the JAR file (auto-detected if None)
        timeout: Timeout in seconds
        verbose: Print verbose output
        params: Parameter dictionary (generated if None)
        config_file: Path to config file (generated if None)
        run_id: Run identifier (used in config file)
    
    Returns:
        Path to the seed output directory if successful, None otherwise
    """
    
    if jar_path is None:
        jar_path = find_jar()
    
    output_dir = os.path.abspath(output_dir)
    seed_dir = os.path.join(output_dir, f"seed_{seed}")
    os.makedirs(seed_dir, exist_ok=True)
    
    jar_path = os.path.abspath(jar_path)
    
    # Generate config if not provided
    if config_file is None:
        if params is None:
            params = generate_parameter_set(seed)
        
        if run_id is None:
            run_id = seed
        
        # Save parameters
        save_parameter_summary(seed, params, output_dir)
        
        # Generate YAML config
        config_content = generate_yaml_config(seed, params, seed_dir, run_id)
        config_file = os.path.join(seed_dir, "simulation_config.yaml")
        with open(config_file, 'w') as f:
            f.write(config_content)
    
    # Build command - JAR expects: seed outputDir configPath
    cmd = [
        "java",
        "-jar", jar_path,
        str(seed),
        seed_dir,
        config_file
    ]
    
    if verbose:
        print(f"\n{'='*60}")
        print(f"Running simulation with seed {seed}")
        print(f"Output: {seed_dir}")
        print(f"Config: {config_file}")
        print(f"Command: {' '.join(cmd)}")
        print(f"{'='*60}")
    
    start_time = time.time()
    
    stop_monitor = threading.Event()
    monitor_thread = None
    
    if verbose:
        monitor_thread = threading.Thread(
            target=monitor_output_files, 
            args=(seed_dir, stop_monitor, 5)
        )
        monitor_thread.daemon = True
        monitor_thread.start()
    
    try:
        env = os.environ.copy()
        env["SIMULATION_SEED"] = str(seed)
        
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env
        )
        
        elapsed = time.time() - start_time
        
        if result.returncode != 0:
            print(f"ERROR: Simulation with seed {seed} failed with code {result.returncode}")
            if result.stderr:
                print(f"STDERR: {result.stderr[:500]}")
            if result.stdout:
                print(f"STDOUT (last 1000 chars):\n{result.stdout[-1000:]}")
            stop_monitor.set()
            if monitor_thread:
                monitor_thread.join(timeout=2)
            return None
        
        print(f"✓ Simulation with seed {seed} completed in {elapsed/60:.1f} minutes")
        
        stop_monitor.set()
        if monitor_thread:
            monitor_thread.join(timeout=2)
        
        time.sleep(2)
        
        if result.stdout:
            print("\nLast lines of simulation output:")
            for line in result.stdout.split('\n')[-10:]:
                if line.strip():
                    print(f"  {line.strip()}")
        
        return seed_dir
        
    except subprocess.TimeoutExpired:
        print(f"ERROR: Simulation with seed {seed} timed out after {timeout/60:.1f} minutes")
        stop_monitor.set()
        if monitor_thread:
            monitor_thread.join(timeout=2)
        return None
    except Exception as e:
        print(f"ERROR: {e}")
        import traceback
        traceback.print_exc()
        stop_monitor.set()
        if monitor_thread:
            monitor_thread.join(timeout=2)
        return None


# ======================================================================
# MONTE CARLO RUNNER
# ======================================================================

def run_monte_carlo(n_runs: int, base_seed: Optional[int] = None, 
                   output_dir: Optional[str] = None, 
                   jar_path: Optional[str] = None, 
                   seed_increment: int = 1, 
                   timeout: int = TIMEOUT_SECONDS,
                   verbose: bool = True, 
                   resume: bool = False, 
                   vary_params: bool = True,
                   parameter_ranges: Optional[Dict] = None) -> Tuple[List, List]:
    """
    Run Monte Carlo simulation with parameter variation.
    
    Args:
        n_runs: Number of runs to execute
        base_seed: Base seed (incremented for each run)
        output_dir: Output directory
        jar_path: Path to JAR file
        seed_increment: Increment between seeds
        timeout: Timeout per run in seconds
        verbose: Print verbose output
        resume: Skip already completed runs
        vary_params: Vary parameters or use base values
        parameter_ranges: Custom parameter ranges
    
    Returns:
        Tuple of (successful_runs, failed_runs)
    """
    
    if output_dir is None:
        output_dir = DEFAULT_OUTPUT_DIR
    if base_seed is None:
        base_seed = DEFAULT_BASE_SEED
    if jar_path is None:
        jar_path = find_jar()
    
    if parameter_ranges is None:
        parameter_ranges = PARAMETER_RANGES
    
    output_dir = os.path.abspath(output_dir)
    jar_path = os.path.abspath(jar_path)
    
    print(f"{'='*60}")
    print(f"MONTE CARLO SIMULATION WITH PARAMETER VARIATION")
    print(f"{'='*60}")
    print(f"JAR: {jar_path}")
    print(f"Output directory: {output_dir}")
    print(f"Number of runs: {n_runs}")
    print(f"Base seed: {base_seed}")
    print(f"Seed increment: {seed_increment}")
    print(f"Timeout: {timeout/60:.1f} minutes per run")
    print(f"Resume mode: {'ON' if resume else 'OFF'}")
    print(f"Parameter variation: {'ON' if vary_params else 'OFF'}")
    print(f"Number of parameters varied: {len(parameter_ranges)}")
    print(f"{'='*60}\n")
    
    os.makedirs(output_dir, exist_ok=True)
    
    # Save parameter descriptions for documentation
    desc_file = os.path.join(output_dir, "parameter_descriptions.yaml")
    with open(desc_file, 'w') as f:
        yaml.dump(get_parameter_descriptions(), f, default_flow_style=False)
    
    # Check for existing runs if resuming
    existing_seeds = set()
    if resume:
        seed_dirs = glob.glob(os.path.join(output_dir, "seed_*"))
        for seed_dir in seed_dirs:
            try:
                seed = int(os.path.basename(seed_dir).split('_')[1])
                merged_files = glob.glob(os.path.join(seed_dir, "merged_*.csv"))
                if merged_files:
                    existing_seeds.add(seed)
            except:
                pass
        if existing_seeds:
            print(f"Resuming: Found {len(existing_seeds)} existing successful runs")
            print(f"Existing seeds: {sorted(existing_seeds)}\n")
    
    # Create metadata
    metadata = {
        "started": datetime.now().isoformat(),
        "n_runs": n_runs,
        "base_seed": base_seed,
        "seed_increment": seed_increment,
        "output_dir": output_dir,
        "jar_path": jar_path,
        "timeout_seconds": timeout,
        "command": " ".join(sys.argv),
        "hostname": os.uname().nodename if hasattr(os, 'uname') else "unknown",
        "resume": resume,
        "vary_params": vary_params,
        "n_parameters_varied": len(parameter_ranges),
        "parameter_categories": {}
    }
    
    for param_name, param_spec in parameter_ranges.items():
        cat = param_spec.get('category', 'other')
        if cat not in metadata["parameter_categories"]:
            metadata["parameter_categories"][cat] = []
        metadata["parameter_categories"][cat].append(param_name)
    
    with open(os.path.join(output_dir, "metadata.json"), 'w') as f:
        json.dump(metadata, f, indent=2)
    
    first_seed = base_seed
    last_seed = base_seed + (n_runs - 1) * seed_increment
    print(f"Seed range: {first_seed} to {last_seed}\n")
    
    successful_runs = []
    failed_runs = []
    run_times = []
    skipped_runs = []
    parameter_sets = []
    
    progress_file = os.path.join(output_dir, "progress.csv")
    if not os.path.exists(progress_file):
        with open(progress_file, 'w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(['run', 'seed', 'status', 'time_seconds', 'output_dir', 'param_count'])
    
    for i in range(n_runs):
        seed = base_seed + (i * seed_increment)
        run_id = i + 1
        
        if resume and seed in existing_seeds:
            print(f"[{run_id}/{n_runs}] ⏭️  Seed {seed} - SKIPPED (already completed)")
            skipped_runs.append(seed)
            continue
        
        run_start = time.time()
        
        if vary_params:
            params = generate_parameter_set(seed, parameter_ranges)
            parameter_sets.append({'seed': seed, 'params': params})
            
            if verbose:
                print(f"\nParameters for seed {seed}:")
                categories = {}
                for k, v in params.items():
                    cat = PARAMETER_RANGES.get(k, {}).get('category', 'other')
                    categories.setdefault(cat, []).append((k, v))
                
                for cat, items in categories.items():
                    if cat != 'other':
                        print(f"  [{cat}]")
                        for k, v in items[:3]:
                            print(f"    {k}: {v:.4f}" if isinstance(v, float) else f"    {k}: {v}")
        else:
            params = {k: v['base'] for k, v in parameter_ranges.items()}
        
        result = run_simulation(seed, output_dir, jar_path, timeout, verbose, params, run_id=run_id)
        
        run_time = time.time() - run_start
        run_times.append(run_time)
        
        if result:
            successful_runs.append({
                'seed': seed,
                'output_dir': result,
                'time': run_time,
                'params': params if vary_params else None
            })
            status = "SUCCESS"
            print(f"\n[{run_id}/{n_runs}] ✓ Seed {seed} - {run_time/60:.1f} min")
        else:
            failed_runs.append(seed)
            status = "FAILED"
            print(f"\n[{run_id}/{n_runs}] ✗ Seed {seed} - FAILED")
        
        with open(progress_file, 'a', newline='') as f:
            writer = csv.writer(f)
            writer.writerow([run_id, seed, status, f"{run_time:.1f}", result or "", 
                            len(params) if params else 0])
        
        time.sleep(1)
    
    # ======================================================================
    # FINAL SUMMARY
    # ======================================================================
    
    print(f"\n{'='*60}")
    print("MONTE CARLO SIMULATION COMPLETE")
    print(f"{'='*60}")
    
    successful_count = len(successful_runs)
    failed_count = len(failed_runs)
    skipped_count = len(skipped_runs)
    total_count = successful_count + failed_count + skipped_count
    
    print(f"\nTotal runs: {total_count}")
    print(f"Successful: {successful_count} ({successful_count/total_count*100:.1f}%)")
    print(f"Failed: {failed_count} ({failed_count/total_count*100:.1f}%)")
    if skipped_count:
        print(f"Skipped: {skipped_count} ({skipped_count/total_count*100:.1f}%)")
    
    if failed_runs:
        print(f"Failed seeds: {failed_runs}")
    if skipped_runs:
        print(f"Skipped seeds: {skipped_runs}")
    
    if run_times:
        avg_time = sum(run_times) / len(run_times)
        print(f"\nRun times:")
        print(f"  Average: {avg_time/60:.1f} minutes")
        print(f"  Min: {min(run_times)/60:.1f} minutes")
        print(f"  Max: {max(run_times)/60:.1f} minutes")
        print(f"  Total: {sum(run_times)/60:.1f} minutes ({sum(run_times)/3600:.1f} hours)")
    
    # Save summary
    summary = {
        "completed": datetime.now().isoformat(),
        "total_runs": total_count,
        "successful": successful_count,
        "failed": failed_count,
        "skipped": skipped_count,
        "failed_seeds": failed_runs,
        "skipped_seeds": skipped_runs,
        "successful_seeds": [r['seed'] for r in successful_runs],
        "run_times": {
            "average_seconds": sum(run_times)/len(run_times) if run_times else 0,
            "min_seconds": min(run_times) if run_times else 0,
            "max_seconds": max(run_times) if run_times else 0,
            "total_seconds": sum(run_times) if run_times else 0
        }
    }
    
    with open(os.path.join(output_dir, "summary.json"), 'w') as f:
        json.dump(summary, f, indent=2)
    
    # Save a simple text summary
    with open(os.path.join(output_dir, "summary.txt"), 'w') as f:
        f.write(f"Monte Carlo Simulation Summary\n")
        f.write(f"{'='*50}\n")
        f.write(f"Completed: {datetime.now()}\n")
        f.write(f"Total runs: {total_count}\n")
        f.write(f"Successful: {successful_count}\n")
        f.write(f"Failed: {failed_count}\n")
        if skipped_count:
            f.write(f"Skipped: {skipped_count}\n")
        if failed_runs:
            f.write(f"Failed seeds: {failed_runs}\n")
        if run_times:
            f.write(f"\nRun times:\n")
            f.write(f"  Average: {avg_time/60:.1f} minutes\n")
            f.write(f"  Total: {sum(run_times)/60:.1f} minutes ({sum(run_times)/3600:.1f} hours)\n")
        f.write(f"\nResults saved to: {output_dir}\n")
    
    print(f"\nResults saved to: {output_dir}")
    print(f"Summary file: {os.path.join(output_dir, 'summary.txt')}")
    
    print(f"\nOutput directory structure:")
    print(f"  {output_dir}/")
    print(f"    ├── metadata.json")
    print(f"    ├── progress.csv")
    print(f"    ├── summary.json")
    print(f"    ├── summary.txt")
    print(f"    ├── parameter_descriptions.yaml")
    print(f"    └── seed_*/")
    print(f"          ├── simulation_config.yaml")
    print(f"          ├── parameters_used.yaml")
    print(f"          ├── parameters_used.csv")
    print(f"          ├── merged_snapshots_*.csv")
    print(f"          ├── snapshot_tick_*.csv")
    print(f"          ├── *_final_statistics_*.txt")
    print(f"          └── run_info.json")
    
    return successful_runs, failed_runs


# ======================================================================
# MAIN
# ======================================================================

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Run Monte Carlo simulations for mosquito ABM with parameter variation",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Run 30 simulations with default settings
  python run_monte_carlo.py
  
  # Run 10 simulations with custom seed
  python run_monte_carlo.py --runs 10 --seed 100
  
  # Run with custom output directory
  python run_monte_carlo.py --output /path/to/results
  
  # Resume a previous run (skip completed seeds)
  python run_monte_carlo.py --resume --output /path/to/results
  
  # Disable parameter variation (use base parameters only)
  python run_monte_carlo.py --no-vary
  
  # Run with custom JAR
  python run_monte_carlo.py --jar target/my-simulation.jar
  
  # Quiet mode (less verbose output)
  python run_monte_carlo.py --quiet
"""
    )
    
    parser.add_argument(
        "--runs", "-n", 
        type=int, 
        default=DEFAULT_RUNS,
        help=f"Number of runs (default: {DEFAULT_RUNS})"
    )
    
    parser.add_argument(
        "--seed", "-s", 
        type=int, 
        default=DEFAULT_BASE_SEED,
        help=f"Base seed (default: {DEFAULT_BASE_SEED})"
    )
    
    parser.add_argument(
        "--output", "-o", 
        type=str, 
        default=DEFAULT_OUTPUT_DIR,
        help=f"Output directory (default: {DEFAULT_OUTPUT_DIR})"
    )
    
    parser.add_argument(
        "--jar", "-j", 
        type=str, 
        help="Path to JAR file (auto-detected if not specified)"
    )
    
    parser.add_argument(
        "--increment", "-i", 
        type=int, 
        default=1,
        help="Seed increment between runs (default: 1)"
    )
    
    parser.add_argument(
        "--timeout", "-t", 
        type=int, 
        default=TIMEOUT_SECONDS,
        help=f"Timeout in seconds per run (default: {TIMEOUT_SECONDS})"
    )
    
    parser.add_argument(
        "--resume", "-r", 
        action="store_true",
        help="Resume a previous run (skip already completed seeds)"
    )
    
    parser.add_argument(
        "--no-vary", 
        action="store_true",
        help="Disable parameter variation (use base parameters only)"
    )
    
    parser.add_argument(
        "--quiet", "-q", 
        action="store_true",
        help="Quiet mode (less verbose output)"
    )
    
    parser.add_argument(
        "--dry-run", 
        action="store_true",
        help="Show what would be run without executing"
    )
    
    args = parser.parse_args()
    
    if args.dry_run:
        print("DRY RUN - Would execute:")
        print(f"  runs: {args.runs}")
        print(f"  base_seed: {args.seed}")
        print(f"  output_dir: {args.output}")
        print(f"  jar: {args.jar or 'auto-detect'}")
        print(f"  increment: {args.increment}")
        print(f"  timeout: {args.timeout}s")
        print(f"  resume: {args.resume}")
        print(f"  vary_params: {not args.no_vary}")
        sys.exit(0)
    
    run_monte_carlo(
        n_runs=args.runs,
        base_seed=args.seed,
        output_dir=args.output,
        jar_path=args.jar,
        seed_increment=args.increment,
        timeout=args.timeout,
        verbose=not args.quiet,
        resume=args.resume,
        vary_params=not args.no_vary
    )