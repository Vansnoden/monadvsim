#!/usr/bin/env python3
# run_monte_carlo.py — Monte Carlo / sensitivity runner for monadvsim.
#
# Revised for uniform full-study-site seeding and short (default 500-tick) runs.
#
# Differences vs. the previous version:
#   * DEFAULT_TICKS = 500 (~5.2 simulated days at 15-min ticks). `--ticks` is
#     now the primary duration flag; `--days` is kept for compatibility and
#     converted to ticks (days × 96).
#   * DEFAULT_TIMEOUT_SECONDS = 1800 (30 min per run) — 500-tick runs finish
#     in ~5 minutes at realistic agent counts.
#   * DEFAULT_HEAP_GB = 1 — at 500 ticks the population peaks well below
#     ~15k agents, so 1 GB is comfortable.
#   * Seeding is uniform across the full study site (`seedAcrossFullStudySite:
#     true`, `useOccurrencePoints: false`). No occurrence file, year filter,
#     or buffer appears in the YAML template.
#   * `tanks_to_seed` base 200 / `mosquitoes_to_seed` base 5000 — larger than
#     the occurrence-seeded bases because uniform seeding spreads agents
#     across the whole study area.
#   * Dormant Brière / Sharpe–Schoolfield development parameters removed from
#     PARAMETER_RANGES — they are not called during stage transitions in the
#     current degree-day implementation, so varying them has no effect.
#   * Deterministic per-(seed, parameter) RNG via SHA-256, so re-running with
#     `--seed 42` produces byte-identical YAML across processes.
#   * `--parallel N` runs N JVMs concurrently; `--oat-replicates N` replicates
#     each one-at-a-time factor cell with independent seeds.
#   * One-at-a-time mode without `--oat-factors` uses each parameter's own
#     `sensitivity_range` (e.g. ±20% mortality, ±50% movement distance).
#
# Scientific caveat: 500 ticks = ~5.2 simulated days. This is a screening
# window, not a full validation window. Seasonal signal is present but small;
# Boyce Index values reflect a non-equilibrium population distribution.

import argparse
import csv
import glob
import hashlib
import json
import os
import random
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from typing import Any, Dict, List, Optional

import yaml

# ======================================================================
# CONFIGURATION
# ======================================================================

DEFAULT_OUTPUT_DIR = "./monte_carlo_results"
DEFAULT_BASE_SEED = 42
DEFAULT_RUNS = 30
DEFAULT_TICKS = 500
DEFAULT_TIMEOUT_SECONDS = 1800
DEFAULT_HEAP_GB = 1
DEFAULT_PARALLEL = 1

JAR_PATHS = [
    "target/monadvsim-1.0-SNAPSHOT.jar",
    "target/monadvsim-1.0-SNAPSHOT-shaded.jar",
    "target/monadvsim.jar",
    "monadvsim-1.0-SNAPSHOT.jar",
    "monadvsim.jar",
]

STUDY_SITE = "prepared_data/somali/somali.shp"
TICKS_PER_DAY = 96

SEASON_START_DATES = {
    "Bega":   "2020-01-01T00:00:00",
    "Belg":   "2020-04-01T00:00:00",
    "Kiremt": "2020-07-01T00:00:00",
    "Tesdey": "2020-10-01T00:00:00",
}

SEASON_WEIGHTS = {"Bega": 11, "Belg": 7, "Kiremt": 6, "Tesdey": 6}

# ======================================================================
# YAML TEMPLATE — uniform seeding, total_ticks substituted per run.
# ======================================================================

BASE_YAML_TEMPLATE = """# ================================================================
# SIMULATION CONFIGURATION — Monte Carlo run {run_id}
# Seed: {seed}   Strategy: {strategy}
# ================================================================

time:
  startDateTime: "{start_date}"
  totalTicks: {total_ticks}
  tickMinutes: 15

files:
  studySite: "{study_site}"

gridCellSizeDegrees: {grid_cell_size}

species:
  egg_dev_rho: 0.005
  egg_dev_k: 39.20841161317061
  egg_dev_Delta: 2.0
  egg_dev_lambda: -0.854916887158478
  larva_dev_a: 2.7054689312186438e-05
  larva_dev_Tmin: 5.123026541348266
  larva_dev_Tmax: 44.999999999835175
  larva_dev_m: 1.663077023745059
  pupa_dev_rho: 0.005115463305281753
  pupa_dev_k: 39.940257466039114
  pupa_dev_Delta: 2.0
  pupa_dev_lambda: -0.9081661833839708
  egg_mort_b1: {egg_mort_b1}
  egg_mort_b2: -0.323474
  egg_mort_b3: 0.004941
  larva_mort_b1: {larva_mort_b1}
  larva_mort_b2: -0.7395036647759831
  larva_mort_b3: 0.017491023810106067
  pupa_mort_b1: {pupa_mort_b1}
  pupa_mort_b2: -0.578528
  pupa_mort_b3: 0.009458
  fecundity_rmax: {fecundity_rmax}
  fecundity_Topt: {fecundity_Topt}
  fecundity_c: {fecundity_c}
  adult_mort_b1: {adult_mort_b1}
  adult_mort_b2: -0.1377
  adult_mort_b3: 0.003908
  adult_mortality_per_day: 0.1198
  sex_ratio: {sex_ratio}
  host_carrying_capacity_base: {host_carrying_capacity_base}
  fecundity_scale: {fecundity_scale}

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

agentLayers:
  - name: "Mosquitoes"
    rules:
      - condition: "temperature < 273.15 || temperature > 318.15"
        action: "die"
        priority: 10
      - condition: "stage == 'ADULT' && energy < 0.75 && !resting"
        action: "feed"
        priority: 9
      - condition: "stage == 'ADULT' && energy < 0.8 && resting"
        action: "rest"
        priority: 8
      - condition: "stage == 'ADULT' && gravid && energy < 0.8"
        action: "feed"
        priority: 8
      - condition: "stage == 'ADULT' && energy > 0.6 && !gravid"
        action: "get_gravid"
        priority: 8
      - condition: "(precipitation > 0.008 || temperature < 285.15) && building_density > 0.1 && !resting"
        action: "rest_in_building"
        priority: 6
      - condition: "stage == 'ADULT' && !resting && !gravid && stageAgeTick < 2000"
        action: "move_random"
        priority: 3
      - condition: "true"
        action: "rest"
        priority: 2
      - condition: "gravid && alive"
        action: "seek_tank"
        priority: 90
      - condition: "gravid && alive && temperature > 280.15 && getTotalLarvae < 30000"
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
      - condition: "temperature < 273.15 && waterVolume > 0"
        action: "freeze"
        priority: 3

inert_agents:
  max_larvae_capacity: {max_larvae_capacity}
  mortality_intensity: {mortality_intensity}
  min_larvae_retain: {min_larvae_retain}
  max_water_volume: 100.0
  hatch_fraction_min: {hatch_fraction_min}
  hatch_fraction_max: {hatch_fraction_max}

seeding:
  seedAcrossFullStudySite: true
  useOccurrencePoints: false
  tanksToSeed: {tanks_to_seed}
  mosquitoesToSeed: {mosquitoes_to_seed}
  habitatGridSizeX: 100
  habitatGridSizeY: 100
  tankSuitability:
    floorSuitability: 0.05
    stochasticJitter: 0.30
    layers:
      - name: Population
        weight: 1.0
        cap: 100.0
        power: 1.0
      - name: Buildings
        weight: 0.0
        cap: 1.0
        power: 1.0

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
# PARAMETER RANGES
# ======================================================================

PARAMETER_RANGES = {
    'fecundity_scale': {
        'type': 'uniform',
        'base': 0.5,
        'monte_carlo_min': 0.30, 'monte_carlo_max': 0.70,
        'sensitivity_range': 0.20,
        'description': 'Fecundity calibration scale',
        'category': 'fecundity',
    },
    'fecundity_rmax': {
        'type': 'uniform',
        'base': 1.6021994175985133,
        'monte_carlo_min': 1.40, 'monte_carlo_max': 1.80,
        'sensitivity_range': 0.20,
        'description': 'Fecundity log-scale peak',
        'category': 'fecundity',
    },
    'fecundity_Topt': {
        'type': 'uniform',
        'base': 30.634033187663604,
        'monte_carlo_min': 29.0, 'monte_carlo_max': 32.0,
        'sensitivity_range': 0.10,
        'description': 'Temperature of max fecundity (°C)',
        'category': 'fecundity',
    },
    'fecundity_c': {
        'type': 'uniform',
        'base': -0.0052723551194922445,
        'monte_carlo_min': -0.0065, 'monte_carlo_max': -0.0040,
        'sensitivity_range': 0.20,
        'description': 'Fecundity thermal breadth coefficient',
        'category': 'fecundity',
    },
    'sex_ratio': {
        'type': 'uniform',
        'base': 0.5,
        'monte_carlo_min': 0.45, 'monte_carlo_max': 0.55,
        'sensitivity_range': 0.10,
        'description': 'Proportion of females',
        'category': 'fecundity',
    },
    'host_carrying_capacity_base': {
        'type': 'uniform',
        'base': 1.0,
        'monte_carlo_min': 0.5, 'monte_carlo_max': 1.5,
        'sensitivity_range': 0.30,
        'description': 'Baseline host-carrying factor κ in κ+log1p(pop)',
        'category': 'fecundity',
    },
    'adult_mort_b1': {
        'type': 'uniform',
        'base': -1.4775,
        'monte_carlo_min': -1.80, 'monte_carlo_max': -1.15,
        'sensitivity_range': 0.20,
        'description': 'Adult mortality intercept (b1)',
        'category': 'mortality',
    },
    'larva_mort_b1': {
        'type': 'uniform',
        'base': 2.0,
        'monte_carlo_min': 1.6, 'monte_carlo_max': 2.4,
        'sensitivity_range': 0.20,
        'description': 'Larval mortality intercept (b1)',
        'category': 'mortality',
    },
    'pupa_mort_b1': {
        'type': 'uniform',
        'base': 5.882576,
        'monte_carlo_min': 5.2, 'monte_carlo_max': 6.5,
        'sensitivity_range': 0.20,
        'description': 'Pupal mortality intercept (b1)',
        'category': 'mortality',
    },
    'egg_mort_b1': {
        'type': 'uniform',
        'base': 3.572876,
        'monte_carlo_min': 3.2, 'monte_carlo_max': 3.9,
        'sensitivity_range': 0.20,
        'description': 'Egg mortality intercept (b1)',
        'category': 'mortality',
    },
    'max_larvae_capacity': {
        'type': 'int_uniform',
        'base': 300,
        'monte_carlo_min': 200, 'monte_carlo_max': 500,
        'sensitivity_range': 0.30,
        'description': 'Maximum larvae per tank',
        'category': 'habitat',
    },
    'mortality_intensity': {
        'type': 'uniform',
        'base': 0.4,
        'monte_carlo_min': 0.20, 'monte_carlo_max': 0.60,
        'sensitivity_range': 0.30,
        'description': 'Density-dependent mortality intensity',
        'category': 'habitat',
    },
    'min_larvae_retain': {
        'type': 'int_uniform',
        'base': 20,
        'monte_carlo_min': 10, 'monte_carlo_max': 40,
        'sensitivity_range': 0.30,
        'description': 'Minimum larvae retained after density kill',
        'category': 'habitat',
    },
    'hatch_fraction_min': {
        'type': 'uniform',
        'base': 0.1,
        'monte_carlo_min': 0.05, 'monte_carlo_max': 0.15,
        'sensitivity_range': 0.30,
        'description': 'Minimum hatch fraction per tick',
        'category': 'habitat',
    },
    'hatch_fraction_max': {
        'type': 'uniform',
        'base': 0.3,
        'monte_carlo_min': 0.15, 'monte_carlo_max': 0.45,
        'sensitivity_range': 0.30,
        'description': 'Maximum hatch fraction per tick',
        'category': 'habitat',
    },
    'tanks_to_seed': {
        'type': 'int_uniform',
        'base': 200,
        'monte_carlo_min': 100, 'monte_carlo_max': 500,
        'sensitivity_range': 0.50,
        'description': 'Number of water tanks seeded',
        'category': 'seeding',
    },
    'mosquitoes_to_seed': {
        'type': 'int_uniform',
        'base': 5000,
        'monte_carlo_min': 2000, 'monte_carlo_max': 15000,
        'sensitivity_range': 0.50,
        'description': 'Number of mosquitoes seeded',
        'category': 'seeding',
    },
    'agent_step': {
        'type': 'uniform',
        'base': 0.0005,
        'monte_carlo_min': 0.00025, 'monte_carlo_max': 0.0010,
        'sensitivity_range': 0.50,
        'description': 'Per-tick random-walk step size (degrees)',
        'category': 'movement',
    },
    'agent_search_radius': {
        'type': 'uniform',
        'base': 0.02,
        'monte_carlo_min': 0.01, 'monte_carlo_max': 0.04,
        'sensitivity_range': 0.50,
        'description': 'Agent search radius (degrees)',
        'category': 'movement',
    },
    'grid_cell_size': {
        'type': 'uniform',
        'base': 0.001,
        'monte_carlo_min': 0.0005, 'monte_carlo_max': 0.002,
        'sensitivity_range': 0.30,
        'description': 'Spatial registry cell size (degrees)',
        'category': 'spatial',
    },
}

TEMPLATE_CONSTANTS = {
    'max_agent_age': 2880,
}

# ======================================================================
# RNG and sampling
# ======================================================================

def _rng_for(seed: int, param_name: str) -> random.Random:
    h = hashlib.sha256(f"{seed}:{param_name}".encode("utf-8")).hexdigest()
    return random.Random(int(h, 16) % (2**32))


def _clamp_params(params: Dict[str, Any]) -> Dict[str, Any]:
    p = dict(params)
    if p['hatch_fraction_max'] < p['hatch_fraction_min'] + 0.01:
        p['hatch_fraction_max'] = p['hatch_fraction_min'] + 0.01
    for k in ('tanks_to_seed', 'mosquitoes_to_seed'):
        if p[k] < 1:
            p[k] = 1
    if p['max_larvae_capacity'] < 10:
        p['max_larvae_capacity'] = 10
    if p['min_larvae_retain'] < 1:
        p['min_larvae_retain'] = 1
    if p['min_larvae_retain'] >= p['max_larvae_capacity']:
        p['min_larvae_retain'] = max(1, p['max_larvae_capacity'] // 10)
    p['sex_ratio'] = min(0.99, max(0.01, p['sex_ratio']))
    if p['fecundity_c'] > 0:
        p['fecundity_c'] = -abs(p['fecundity_c'])
    for k in ('fecundity_scale', 'host_carrying_capacity_base', 'agent_step',
              'agent_search_radius', 'grid_cell_size', 'mortality_intensity'):
        if p[k] <= 0:
            p[k] = max(1e-6, abs(p[k]))
    return p


def sample_monte_carlo_params(seed: int) -> Dict[str, Any]:
    params: Dict[str, Any] = {}
    for pname, spec in PARAMETER_RANGES.items():
        rng = _rng_for(seed, pname)
        lo, hi = spec['monte_carlo_min'], spec['monte_carlo_max']
        if spec['type'] == 'int_uniform':
            params[pname] = rng.randint(int(lo), int(hi))
        elif spec['type'] == 'uniform':
            params[pname] = rng.uniform(lo, hi)
        else:
            params[pname] = spec['base']
    params.update(TEMPLATE_CONSTANTS)
    return _clamp_params(params)


def base_params() -> Dict[str, Any]:
    p = {k: v['base'] for k, v in PARAMETER_RANGES.items()}
    p.update(TEMPLATE_CONSTANTS)
    return p


def swept_params(pname: str, factor: float) -> Dict[str, Any]:
    p = base_params()
    spec = PARAMETER_RANGES[pname]
    swept = spec['base'] * (1.0 + factor)
    if spec['type'] == 'int_uniform':
        swept = int(round(swept))
    p[pname] = swept
    return _clamp_params(p)

# ======================================================================
# Season allocation
# ======================================================================

def allocate_seasons(n_runs: int) -> List[str]:
    total_w = sum(SEASON_WEIGHTS.values())
    alloc: List[str] = []
    for season, w in SEASON_WEIGHTS.items():
        alloc.extend([season] * int(round(n_runs * w / total_w)))
    while len(alloc) < n_runs:
        alloc.append("Bega")
    return alloc[:n_runs]


def pick_start_date(args, run_index: int, season_alloc: Optional[List[str]]) -> str:
    if args.start_date:
        return args.start_date
    if not args.allocate_seasons or season_alloc is None:
        return SEASON_START_DATES["Bega"]
    return SEASON_START_DATES[season_alloc[run_index]]

# ======================================================================
# YAML generation
# ======================================================================

def generate_yaml_config(seed, run_id, strategy_label, start_date,
                         total_ticks, params) -> str:
    fmt = {
        'run_id': run_id, 'seed': seed, 'strategy': strategy_label,
        'start_date': start_date, 'total_ticks': total_ticks,
        'study_site': STUDY_SITE, **params,
    }
    return BASE_YAML_TEMPLATE.format(**fmt)

# ======================================================================
# Run plan
# ======================================================================

def build_run_plan(args) -> List[Dict[str, Any]]:
    total_ticks = resolve_ticks(args)
    season_alloc = allocate_seasons(args.runs) if args.allocate_seasons else None
    plans: List[Dict[str, Any]] = []
    run_id = 1

    if args.strategy == "none":
        for i in range(args.runs):
            seed = args.seed + i * args.increment
            plans.append({
                'run_id': run_id, 'seed': seed,
                'strategy_label': 'baseline',
                'start_date': pick_start_date(args, i, season_alloc),
                'total_ticks': total_ticks, 'params': base_params(),
            })
            run_id += 1

    elif args.strategy == "monte-carlo":
        for i in range(args.runs):
            seed = args.seed + i * args.increment
            plans.append({
                'run_id': run_id, 'seed': seed,
                'strategy_label': 'monte-carlo',
                'start_date': pick_start_date(args, i, season_alloc),
                'total_ticks': total_ticks,
                'params': sample_monte_carlo_params(seed),
            })
            run_id += 1

    elif args.strategy == "one-at-a-time":
        key_params = args.key_params if args.key_params else list(PARAMETER_RANGES.keys())
        for pname in key_params:
            if pname not in PARAMETER_RANGES:
                print(f"WARNING: unknown key parameter '{pname}', skipping")
                continue
            spec = PARAMETER_RANGES[pname]
            if args.oat_factors:
                factors = args.oat_factors
            else:
                r = spec['sensitivity_range']
                factors = [-r, 0.0, r]
            for f in factors:
                for rep in range(args.oat_replicates):
                    seed = args.seed + (run_id - 1) * args.increment
                    plans.append({
                        'run_id': run_id, 'seed': seed,
                        'strategy_label': f'oat:{pname}@{f:+.0%}#r{rep}',
                        'start_date': pick_start_date(args, run_id - 1, season_alloc),
                        'total_ticks': total_ticks,
                        'params': swept_params(pname, f),
                    })
                    run_id += 1

    return plans


def resolve_ticks(args) -> int:
    if args.ticks is not None:
        return args.ticks
    if args.days is not None:
        return args.days * TICKS_PER_DAY
    return DEFAULT_TICKS

# ======================================================================
# Validation
# ======================================================================

def _available_ram_gb() -> float:
    try:
        with open('/proc/meminfo') as f:
            for line in f:
                if line.startswith('MemAvailable:'):
                    return int(line.split()[1]) / (1024 * 1024)
    except Exception:
        pass
    return 8.0


def validate_environment(args) -> List[str]:
    problems: List[str] = []
    if not os.path.exists(STUDY_SITE):
        problems.append(f"Study site shapefile not found: {STUDY_SITE}")
    try:
        jar = args.jar or find_jar()
        if not os.path.exists(jar):
            problems.append(f"JAR not found: {jar}")
    except SystemExit:
        problems.append("No JAR found (Maven build skipped)")
    ticks = resolve_ticks(args)
    if ticks <= 0:
        problems.append(f"ticks must be > 0 (got {ticks})")
    if ticks > 35040:
        problems.append(f"ticks={ticks} exceeds the 2020 climate data range")
    avail = _available_ram_gb()
    if args.parallel * args.heap > avail * 0.85:
        problems.append(
            f"--parallel {args.parallel} × --heap {args.heap} GB "
            f"= {args.parallel*args.heap} GB > {avail*0.85:.1f} GB budget "
            f"(available: {avail:.1f} GB)")
    return problems

# ======================================================================
# Plan printing
# ======================================================================

def print_plan(plans: List[Dict[str, Any]], args) -> None:
    ticks = resolve_ticks(args)
    print(f"\n{'='*70}")
    print(f"RUN PLAN ({len(plans)} runs, strategy={args.strategy}, "
          f"ticks={ticks} ({ticks/TICKS_PER_DAY:.1f} d), "
          f"parallel={args.parallel}, heap={args.heap} GB)")
    print(f"{'='*70}")
    for p in plans[:60]:
        n_varied = sum(1 for k, v in p['params'].items()
                       if k in PARAMETER_RANGES and v != PARAMETER_RANGES[k]['base'])
        extra = f"  [{n_varied} varied]" if n_varied else ""
        print(f"  run {p['run_id']:>3}  seed={p['seed']:<8}  "
              f"start={p['start_date'][:10]}  ticks={p['total_ticks']}{extra}")
    if len(plans) > 60:
        print(f"  ... ({len(plans) - 60} more runs)")
    print(f"{'='*70}\n")

# ======================================================================
# JAR discovery
# ======================================================================

def find_jar() -> str:
    for path in JAR_PATHS:
        if os.path.exists(path):
            return os.path.abspath(path)
    if os.path.exists("pom.xml"):
        print("No JAR found. Building with Maven...")
        try:
            r = subprocess.run(["mvn", "clean", "package", "-DskipTests"],
                               check=True, capture_output=True, text=True)
            print(r.stdout[-2000:])
            for path in JAR_PATHS:
                if os.path.exists(path):
                    return os.path.abspath(path)
        except subprocess.CalledProcessError as e:
            print(f"Build failed: {e.stderr[-500:] if e.stderr else 'Unknown error'}")
    jars = glob.glob("*.jar") + glob.glob("target/*.jar")
    if jars:
        return os.path.abspath(jars[0])
    print("ERROR: No JAR file found. Run `mvn clean package` first.")
    sys.exit(1)

# ======================================================================
# Per-run execution
# ======================================================================

def save_parameter_summary(seed, params, output_dir, meta=None):
    seed_dir = os.path.join(output_dir, f"seed_{seed}")
    os.makedirs(seed_dir, exist_ok=True)
    payload = dict(params)
    if meta:
        payload.update({f"_meta_{k}": v for k, v in meta.items()})
    with open(os.path.join(seed_dir, "parameters_used.yaml"), "w") as f:
        yaml.dump(payload, f, default_flow_style=False, sort_keys=True)
    with open(os.path.join(seed_dir, "parameters_used.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["parameter", "value", "base", "category"])
        for k, v in sorted(params.items()):
            spec = PARAMETER_RANGES.get(k, {})
            w.writerow([k, v, spec.get('base', ''), spec.get('category', '')])


def run_one(plan, args, jar_path, output_dir) -> Optional[str]:
    seed = plan['seed']
    seed_dir = os.path.join(output_dir, f"seed_{seed}")
    os.makedirs(seed_dir, exist_ok=True)

    cfg_text = generate_yaml_config(
        seed=seed, run_id=plan['run_id'],
        strategy_label=plan['strategy_label'],
        start_date=plan['start_date'],
        total_ticks=plan['total_ticks'],
        params=plan['params'])
    cfg_path = os.path.join(seed_dir, "simulation_config.yaml")
    with open(cfg_path, "w") as f:
        f.write(cfg_text)

    save_parameter_summary(seed, plan['params'], output_dir, meta={
        'run_id': plan['run_id'], 'strategy': plan['strategy_label'],
        'start_date': plan['start_date'], 'total_ticks': plan['total_ticks']})

    heap_g = args.heap
    xms = max(1, heap_g // 2)
    cmd = ["java", f"-Xmx{heap_g}g", f"-Xms{xms}g",
           "-XX:+UseG1GC", "-jar", jar_path,
           str(seed), seed_dir, cfg_path]

    if not args.quiet:
        print(f"\n  run {plan['run_id']} | seed {seed} | {plan['strategy_label']}")

    start = time.time()
    try:
        result = subprocess.run(cmd, capture_output=True, text=True,
                                timeout=args.timeout)
        elapsed = time.time() - start
        if result.returncode != 0:
            print(f"  ✗ run {plan['run_id']} FAILED (code {result.returncode}) "
                  f"in {elapsed/60:.1f} min")
            if result.stderr:
                print(f"    stderr: {result.stderr[-500:]}")
            return None
        print(f"  ✓ run {plan['run_id']} done in {elapsed/60:.1f} min")
        return seed_dir
    except subprocess.TimeoutExpired:
        print(f"  ✗ run {plan['run_id']} TIMED OUT after {args.timeout/60:.1f} min")
        return None
    except Exception as e:
        print(f"  ✗ run {plan['run_id']} ERROR: {e}")
        return None

# ======================================================================
# Main
# ======================================================================

def main():
    parser = argparse.ArgumentParser(
        description="Monte Carlo / sensitivity runner for monadvsim "
                    "(uniform seeding, short runs)")
    parser.add_argument("--runs", "-n", type=int, default=DEFAULT_RUNS)
    parser.add_argument("--seed", "-s", type=int, default=DEFAULT_BASE_SEED)
    parser.add_argument("--increment", "-i", type=int, default=1)
    parser.add_argument("--ticks", type=int, default=None,
                        help=f"ticks per run (default: {DEFAULT_TICKS})")
    parser.add_argument("--days", "-d", type=int, default=None,
                        help="[deprecated] days per run; --ticks takes priority")
    parser.add_argument("--output", "-o", type=str, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--jar", "-j", type=str, default=None)
    parser.add_argument("--timeout", "-t", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--strategy", "-S",
                        choices=["monte-carlo", "one-at-a-time", "none"],
                        default="monte-carlo")
    parser.add_argument("--oat-factors", type=float, nargs="+", default=None)
    parser.add_argument("--oat-replicates", type=int, default=1)
    parser.add_argument("--key-params", type=str, nargs="+", default=None)
    parser.add_argument("--allocate-seasons", action="store_true")
    parser.add_argument("--start-date", type=str, default=None)
    parser.add_argument("--resume", "-r", action="store_true")
    parser.add_argument("--parallel", "-p", type=int, default=DEFAULT_PARALLEL)
    parser.add_argument("--heap", type=int, default=DEFAULT_HEAP_GB)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--quiet", "-q", action="store_true")
    args = parser.parse_args()

    if args.strategy == "one-at-a-time":
        n_key = len(args.key_params) if args.key_params else len(PARAMETER_RANGES)
        if args.oat_factors:
            n_factors = len(args.oat_factors)
        else:
            n_factors = 3
        print(f"INFO: one-at-a-time = {n_key} params × {n_factors} factors × "
              f"{args.oat_replicates} reps = {n_key*n_factors*args.oat_replicates} runs")

    if args.validate_only:
        problems = validate_environment(args)
        if problems:
            print("VALIDATION FAILED:")
            for p in problems:
                print(f"  - {p}")
            sys.exit(1)
        print("Validation passed.")
        print_plan(build_run_plan(args), args)
        sys.exit(0)

    plans = build_run_plan(args)
    if args.dry_run:
        print_plan(plans, args)
        problems = validate_environment(args)
        if problems:
            print("NOTES:")
            for p in problems:
                print(f"  - {p}")
        sys.exit(0)

    problems = validate_environment(args)
    if problems:
        print("VALIDATION FAILED:")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)

    os.makedirs(args.output, exist_ok=True)
    jar_path = args.jar or find_jar()

    existing = set()
    if args.resume:
        for d in glob.glob(os.path.join(args.output, "seed_*")):
            if glob.glob(os.path.join(d, "merged_*.csv")):
                try:
                    existing.add(int(os.path.basename(d).split('_')[1]))
                except ValueError:
                    pass
        if existing:
            print(f"Resuming: {len(existing)} already-completed seeds will be skipped")

    print_plan(plans, args)

    meta = {
        "started": datetime.now().isoformat(),
        "strategy": args.strategy,
        "runs": args.runs,
        "ticks_per_run": resolve_ticks(args),
        "simulated_days_per_run": resolve_ticks(args) / TICKS_PER_DAY,
        "base_seed": args.seed,
        "increment": args.increment,
        "output_dir": os.path.abspath(args.output),
        "jar_path": os.path.abspath(jar_path),
        "timeout_seconds": args.timeout,
        "heap_gb": args.heap,
        "parallel": args.parallel,
        "allocate_seasons": args.allocate_seasons,
        "start_date": args.start_date,
        "seeding_mode": "uniform_suitability_full_site",
        "param_descriptions": {
            k: {
                "base": v["base"],
                "mc_range": [v["monte_carlo_min"], v["monte_carlo_max"]],
                "sensitivity_range": v["sensitivity_range"],
                "category": v["category"],
                "description": v["description"],
            } for k, v in PARAMETER_RANGES.items()
        },
    }
    with open(os.path.join(args.output, "metadata.json"), "w") as f:
        json.dump(meta, f, indent=2, default=str)

    progress_path = os.path.join(args.output, "progress.csv")
    progress_lock = threading.Lock()
    if not os.path.exists(progress_path):
        with open(progress_path, "w", newline="") as f:
            csv.writer(f).writerow(
                ["run_id", "seed", "strategy", "start_date", "status",
                 "elapsed_seconds", "output_dir"])

    def _log_progress(row):
        with progress_lock:
            with open(progress_path, "a", newline="") as f:
                csv.writer(f).writerow(row)

    successful, failed, skipped = [], [], []
    run_times: List[float] = []

    def _execute(plan):
        start = time.time()
        result = run_one(plan, args, jar_path, args.output)
        elapsed = time.time() - start
        status = "SUCCESS" if result else "FAILED"
        _log_progress([plan['run_id'], plan['seed'], plan['strategy_label'],
                       plan['start_date'], status, f"{elapsed:.1f}", result or ""])
        return plan, result, elapsed

    active_plans = [p for p in plans
                    if not (args.resume and p['seed'] in existing)]
    for p in plans:
        if args.resume and p['seed'] in existing:
            skipped.append(p['seed'])

    if args.parallel <= 1:
        for plan in active_plans:
            _, result, elapsed = _execute(plan)
            run_times.append(elapsed)
            (successful if result else failed).append(plan['seed'])
    else:
        with ThreadPoolExecutor(max_workers=args.parallel) as ex:
            futures = {ex.submit(_execute, p): p for p in active_plans}
            for fut in as_completed(futures):
                plan, result, elapsed = fut.result()
                run_times.append(elapsed)
                (successful if result else failed).append(plan['seed'])

    print(f"\n{'='*70}")
    print(f"DONE — {len(successful)} successful, {len(failed)} failed, "
          f"{len(skipped)} skipped (of {len(plans)})")
    if run_times:
        print(f"Avg per-run: {sum(run_times)/len(run_times)/60:.1f} min  "
              f"(total {sum(run_times)/3600:.1f} h)")
    print(f"Results: {os.path.abspath(args.output)}")

    with open(os.path.join(args.output, "summary.json"), "w") as f:
        json.dump({
            "completed": datetime.now().isoformat(),
            "total": len(plans),
            "successful": successful,
            "failed": failed,
            "skipped": skipped,
            "avg_run_seconds": sum(run_times) / len(run_times) if run_times else 0,
            "total_run_seconds": sum(run_times),
        }, f, indent=2, default=str)

    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
