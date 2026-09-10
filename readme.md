# MONADSIM – Multi‑Agent Mosquito Simulation Framework

A high‑performance, rule‑based, individual‑based simulation framework for mosquito population dynamics, with a focus on *Anopheles stephensi*. The model integrates temperature‑dependent development and survival equations from Komi et al. 2025, environmental raster data (elevation, buildings, population density), climate time series (NetCDF), and behavioural rules evaluated in JavaScript via GraalVM. It is designed to be generic for other vector species.

## Table of Contents

1. [Features](#features)
2. [System Requirements](#system-requirements)
3. [Installation & Build](#installation--build)
4. [Configuration](#configuration)
5. [Running the Simulation](#running-the-simulation)
6. [Output & Results](#output--results)
7. [Code Architecture](#code-architecture)
8. [Extending the Model](#extending-the-model)
9. [Troubleshooting](#troubleshooting)

---

## Features

- **Agent‑based modelling** – each mosquito and water tank is an individual agent.
- **Temperature‑dependent life cycle** – egg, larva, pupa, and adult stages transition according to Komi et al. 2025 ODE equations. Larval and pupal transitions use accumulated degree‑days. Egg hatching in water tanks also uses degree‑day accumulation.
- **Behavioural rules** – rule *conditions* are JavaScript expressions evaluated via GraalVM. Actions are selected from a fixed Java action registry (`die`, `lay_eggs`, `move_random`, `feed`, `rest`, `seek_tank`, etc.).
- **Environmental context** – agents query raster layers (elevation, buildings, population) and climate variables (temperature, precipitation) at their current location.
- **Spatial indexing** – grid‑based `SpatialRegistry` for efficient neighbour queries.
- **Parallel processing** – agent updates are batched and processed in parallel using `AgentContainer`, `ManagedExecutorService`, and a dedicated rule‑worker pool.
- **Memory‑efficient rasters** – large GeoTIFF files are memory‑mapped via `MemoryMappedRasterLayer` when they exceed 100 MB.
- **NetCDF climate data** – supports ERA5‑style NetCDF files with hourly data and temporal interpolation between frames.
- **Incremental spatial updates** – births, deaths, and movements are queued and applied in bulk.
- **Occurrence‑based seeding** – initial tanks and mosquitoes can be seeded uniformly, by habitat suitability, or from occurrence CSV points.
- **Snapshot export** – periodic CSV exports of all agent states every 100 ticks, plus a final snapshot.
- **Snapshot merging** – all snapshot CSVs are merged into one file after simulation completion.
- **YAML configuration** – all simulation parameters (timing, file paths, species parameters, rules, seeding) are defined in a single YAML file.
- **Resource management** – `ResourceManager`, `ManagedExecutorService`, and a watchdog thread monitor and clean up simulation resources.

---

## System Requirements

- **Java 17+** (LTS)
- **Maven 3.6+**
- **Memory** – at least 8 GB RAM recommended for large simulations (10k+ tanks, 250k+ mosquitoes)
- **Disk space** – several GB for results (CSV snapshots and merged files)

---

## Installation & Build

Clone the repository and build with Maven:

```bash
git clone <repository-url>
cd monadvsim
mvn clean compile
```

### Dependencies (managed by Maven)

- GeoTools – raster handling, shapefile reading, CRS transforms
- NetCDF‑Java (`ucar.nc2`) – climate data
- GraalVM JavaScript – rule condition evaluation
- Jackson – YAML configuration and JSON species parameters
- JTS – geometry operations
- Standard Java concurrency utilities

---

## Configuration

All simulation settings are stored in a YAML file. The default path is:

```text
config/simulation.yaml
```

`ConfigLoader` attempts to load from the classpath first, then the file system, then a `config/` prefix.

### Top‑level YAML sections

| Section | Purpose |
|:---|:---|
| `time` | Simulation start date/time, total ticks, tick length in minutes |
| `files` | Paths to study site, elevation, buildings, population, climate NetCDF |
| `layers` | Data‑driven layer definitions (raster, timeseries, vector, occurrence) |
| `species` | Temperature‑dependent life‑cycle coefficients |
| `agentLayers` | Agent layer names and behavioural rules |
| `seeding` | Initial agent seeding parameters |
| `project` | Project defaults (search radius, step size, max age) |
| `tokens` | Token‑to‑layer mapping for rule bindings |
| `gridCellSizeDegrees` | Spatial registry grid cell size in degrees |
| `inert_agents` | Water tank agent parameters |

### Species parameters

All temperatures in the equations are in **degrees Celsius**. The simulation converts Kelvin from climate data to Celsius before applying these formulas.

| Process | YAML keys | Equation / notes |
|:---|:---|:---|
| Egg development | `egg_dev_rho`, `egg_dev_k`, `egg_dev_Delta`, `egg_dev_lambda` | Exponential form; `T` in °C |
| Larva development | `larva_dev_a`, `larva_dev_Tmin`, `larva_dev_Tmax`, `larva_dev_m` | Brière; zero outside `Tmin`–`Tmax` |
| Pupa development | `pupa_dev_rho`, `pupa_dev_k`, `pupa_dev_Delta`, `pupa_dev_lambda` | Exponential form |
| Egg mortality | `egg_mort_b1`, `egg_mort_b2`, `egg_mort_b3` | `exp(b1 + b2*T + b3*T²)` |
| Larva mortality | `larva_mort_b1`, `larva_mort_b2`, `larva_mort_b3` | `exp(b1 + b2*T + b3*T²)` |
| Pupa mortality | `pupa_mort_b1`, `pupa_mort_b2`, `pupa_mort_b3` | `exp(b1 + b2*T + b3*T²)` |
| Adult mortality | `adult_mortality_per_day`, `adult_mort_b1`, `adult_mort_b2`, `adult_mort_b3` | Constant per day unless `adult_mort_b*` are non‑zero, then exponential‑quadratic |
| Fecundity | `fecundity_rmax`, `fecundity_Topt`, `fecundity_c` | `exp(rmax + c*(Topt - T)²) * sex_ratio`; capped at 50 eggs per lay |
| Sex ratio | `sex_ratio` | Proportion females |
| Host carrying capacity | `host_carrying_capacity_base` | `base + log1p(livestockDensity)` |

### Hard‑coded life‑cycle constants

These constants are defined in `LifecycleModel` and `InertAgent`:

- Larva base temperature: `T_BASE_LARVA = 13.3 °C`
- Pupa base temperature: `T_BASE_PUPA = 10.0 °C`
- Degree‑days larva → pupa: `66.5`
- Degree‑days pupa → adult: `22.0`
- Egg base temperature: `10.0 °C`
- Degree‑days egg → hatch: `22.0`

### Water tank parameters (`inert_agents`)

| Key | Default | Meaning |
|:---|:---|:---|
| `max_larvae_capacity` | 500 | Maximum larvae before density‑dependent mortality |
| `mortality_intensity` | 0.5 | Fraction of excess larvae killed |
| `min_larvae_retain` | 50 | Minimum larvae retained after mortality |
| `max_water_volume` | 100.0 | Maximum water volume (%) |
| `hatch_fraction_min` | 0.1 | Minimum fraction of ready eggs hatched per tick |
| `hatch_fraction_max` | 0.6 | Maximum fraction of ready eggs hatched per tick |

### Example YAML snippet

```yaml
time:
  startDateTime: "2020-04-01T00:00:00"
  totalTicks: 17280
  tickMinutes: 15

files:
  studySite: "prepared_data/somali/somali.shp"
  elevation: "prepared_data/somali/Small_Somali_Elevation_10m.tif"
  buildings: "prepared_data/somali/Small_Somali_Building_Density_10m.tif"
  population: "prepared_data/somali/population_2020_1km.tif"
  climateNetCDF: "prepared_data/somali/climate_t2m_tp_2020.nc"

layers:
  - name: Elevation
    filePath: "prepared_data/somali/Small_Somali_Elevation_10m.tif"
    type: raster
    active: true
  - name: Climate
    filePath: "prepared_data/somali/climate_t2m_tp_2020.nc"
    type: timeseries
    variables: [t2m, tp]
    active: true

species:
  egg_dev_rho: 0.005
  egg_dev_k: 39.2084
  egg_dev_Delta: 2.0
  egg_dev_lambda: -0.8549
  larva_dev_a: 2.705e-5
  larva_dev_Tmin: 5.123
  larva_dev_Tmax: 45.0
  larva_dev_m: 1.663
  pupa_dev_rho: 0.0051
  pupa_dev_k: 39.94
  pupa_dev_Delta: 2.0
  pupa_dev_lambda: -0.9082
  egg_mort_b1: 3.5729
  egg_mort_b2: -0.3235
  egg_mort_b3: 0.00494
  larva_mort_b1: 2.0
  larva_mort_b2: -0.7395
  larva_mort_b3: 0.01749
  pupa_mort_b1: 5.8826
  pupa_mort_b2: -0.5785
  pupa_mort_b3: 0.00946
  fecundity_rmax: 1.6022
  fecundity_Topt: 30.634
  fecundity_c: -0.00527
  adult_mortality_per_day: 0.1198
  sex_ratio: 0.5

agentLayers:
  - name: Mosquitoes
    rules:
      - condition: "stage == 'ADULT' && gravid && energy > 0.5"
        action: "lay_eggs"
        priority: 10
      - condition: "stage == 'ADULT' && !resting && energy < 0.2"
        action: "rest"
        priority: 5
  - name: WaterTanks
    rules: []

seeding:
  tanksToSeed: 5000
  mosquitoesToSeed: 10000
  habitatGridSizeX: 100
  habitatGridSizeY: 100
  tankBuildingThreshold: 0.0001
  tankPopulationThreshold: 0.0001
  mosquitoBuildingThreshold: 0.0001
  mosquitoPopulationThreshold: 0.0001
  seedAcrossFullStudySite: false
  useOccurrencePoints: false

project:
  defaultAgentSearchRadius: 0.005
  defaultAgentStep: 0.00005
  defaultMaxAgentAge: 2880

tokens:
  - token: temperature
    layer: t2m
  - token: precipitation
    layer: tp
  - token: population
    layer: Population
  - token: building_density
    layer: Buildings
  - token: elevation
    layer: Elevation

gridCellSizeDegrees: 0.001
```

### Preparing input data

- **Raster layers** (GeoTIFF): elevation, building density, population density. They must cover the defined world bounds.
- **Climate NetCDF** – should contain variables such as `t2m` (temperature in Kelvin) and `tp` (precipitation in metres). Dimensions must include `(time, latitude, longitude)`. If files are missing, the simulation falls back to synthetic data or default layers.
- **Study site** – a shapefile (`.shp`) or QGIS project (`.qgz`/`.qgs` pointing to a shapefile) used to define world bounds and constrain seeding.
- **Occurrence CSV** – optional. Expected columns: `longitude, latitude, year, month, day, source`.

---

## Running the Simulation

From the project root:

```bash
mvn exec:java
```

This will:

- Load the YAML configuration.
- Load raster, vector, and climate data.
- Seed initial water tanks and mosquitoes.
- Start the simulation loop.
- Export snapshots every 100 ticks to `results/snapshot_tick_<tick>.csv`.
- Export a final snapshot when the simulation stops.
- Merge all snapshots into `results/merged_snapshots_<timestamp>.csv`.
- Save final statistics to `results/<project>_final_statistics_<timestamp>.txt`.

### Command‑line arguments

The main class accepts up to three arguments:

```text
[seed] [outputDir] [configPath]
```

- `seed` – optional long integer. If the first argument is not a number, it is treated as the config path.
- `outputDir` – optional output directory. Default: `results`.
- `configPath` – optional config path. Default: `config/simulation.yaml`.

Examples:

```bash
mvn exec:java -Dexec.args="12345 results config/simulation.yaml"
mvn exec:java -Dexec.args="config/simulation.yaml"
```

### JVM options

Configure the `exec-maven-plugin` with appropriate heap settings. For example:

```bash
mvn exec:java -Dexec.jvmArgs="-Xmx12g -Xms6g"
```

---

## Output & Results

### Snapshot CSV files

Each snapshot contains one row per agent at a given tick. The header is:

```text
TickCount,AgentID,Layer,AgentType,X,Y,Alive,Age,Stage,Energy,Gravid,EggCount,LarvaCount,waterVolume,Resting,RestingDuration,TimeWithoutRest,<environmental layers>
```

Column details:

- `TickCount` – simulation tick.
- `AgentID` – unique agent ID.
- `Layer` – `Mosquitoes` or `WaterTanks`.
- `AgentType` – `LivingAgent` or `InertAgent`.
- `X`, `Y` – longitude, latitude.
- `Alive` – `1` alive, `0` dead, `-1` not applicable (inert agent).
- `Age` – age in ticks for living agents.
- `Stage` – `LARVA`, `PUPA`, `ADULT`, or `INERT`.
- `Energy` – 0–1.
- `Gravid` – `1`/`0`.
- `EggCount`, `LarvalCount`, `waterVolume` – for water tanks.
- `Resting`, `RestingDuration`, `TimeWithoutRest` – for living agents.
- `<environmental layers>` – temperature, precipitation, population, building density, elevation, etc., at the agent’s location.

### Merged file

After the simulation ends, all `snapshot_tick_*.csv` files are merged into a single CSV using `SnapshotMerger`. Intermediate snapshot files are deleted after a successful merge.

### Statistics file

A final statistics file is saved with:

- Agent counts per layer
- Spatial registry performance
- Memory usage
- Average tick time
- Final tick and effective FPS
- Layer counts

### Analysis notebook

A Jupyter notebook (`analyze_sim_data.ipynb`) can load the merged CSV and produce population dynamics, temperature correlations, spatial suitability maps, and model validation plots.

---

## Code Architecture

### Core packages

- `com.monadvsim.app` – main entry point `App`.
- `models.engine` – simulation core:
  - `SimulationEngine` – main loop, tick management, snapshot export.
  - `TimeManager` – simulation time, frame indexing, temporal interpolation factor.
  - `SpatialRegistry` – grid‑based spatial indexing.
  - `RuleEngine` – GraalVM JavaScript rule condition evaluation and action dispatch.
  - `AgentLifeCycleManager` – queues for births/deaths/moves and agent pooling.
  - `LifecycleModel` – temperature‑dependent rates and degree‑day transitions.
  - `SpeciesParameters` – species coefficients.
  - `SimulationMetrics` – performance counters.
- `models.entities` – agent and layer classes:
  - `Agent` (abstract), `LivingAgent` (mosquitoes), `InertAgent` (water tanks).
  - `AgentContainer` – thread‑safe partitioned agent storage.
  - `AgentLayer` – container + rule processing + lifecycle updates.
  - `RasterLayer`, `InterpolatedRasterLayer`, `MemoryMappedRasterLayer`.
  - `Project` – container for all layers and global settings.
  - `LifecycleStage` – `EGG`, `LARVA`, `PUPA`, `ADULT`, `UNDEFINED`.
- `models.services` – `ProjectPersistenceService` (loads rasters, NetCDF, exports CSV), `LayerFactory`.
- `models.config` – `SimulationConfig` with nested YAML‑mapped classes.
- `models.utils` – `ResourceManager`, `ManagedExecutorService`, `SnapshotMerger`, `ConfigLoader`, `OccurrenceLoader`, `VectorBoundsLoader`, `SeedManager`, `SimulationLogger`, `ClimateDatasetManager`, `SpeciesParameterLoader`.

### Key workflows

1. **Initialisation**
   - Parse YAML → create `TimeManager`, `SpatialRegistry`, `LifecycleModel`.
   - Load rasters and climate NetCDF via `LayerFactory`.
   - Create agent layers and add rules from YAML.
   - Seed initial agents using uniform, weighted habitat suitability, or occurrence‑based seeding.

2. **Simulation tick**
   - Advance time (`TimeManager.tick()`).
   - Update environment (set active frame of raster layers).
   - Process each agent layer:
     - For `InertAgent`: hatch eggs using degree‑day accumulation, apply density‑dependent mortality.
     - For `LivingAgent`: increment stage age, apply temperature‑dependent development/mortality, update resting state, check stage‑specific max age.
     - Evaluate rule conditions in priority order.
     - Execute actions (`die`, `lay_eggs`, `move_random`, `feed`, `rest`, `seek_tank`, etc.).
   - Apply pending spatial updates (births, deaths, moves) to `SpatialRegistry`.
   - Export snapshot every 100 ticks.
   - Adaptive sleep to maintain ~60 FPS.

3. **Shutdown**
   - Export final snapshot.
   - Merge all snapshots into one CSV.
   - Save final statistics.
   - Clean up resources (thread pools, memory‑mapped files, GeoTools coverage objects).

### Rule engine bindings

JavaScript rule conditions have access to:

- `agent` – the agent object.
- `stage`, `age`, `stageAgeTick`, `energy`, `gravid`, `alive`, `resting`, `x`, `y` – for living agents.
- `waterVolume`, `eggCount`, `larvalCount`, `capacity` – for water tanks.
- `temperature`, `precipitation` – climate values at the agent location.
- Token bindings defined in `tokens` (e.g. `population`, `building_density`, `elevation`).
- `getTotalLarvae` – total larval count across all water tanks.

### Available rule actions

- `die`
- `get_gravid`
- `lay_eggs`
- `move_random`
- `reproduce`
- `feed`
- `dry_out`
- `freeze`
- `evaporate`
- `rest`
- `stop_resting`
- `die_exhaustion`
- `rest_in_building`
- `pupate`
- `emerge`
- `thin_larvae`
- `seek_tank`

---

## Extending the Model

### Adding a new species

1. Create a new YAML configuration with appropriate coefficients (fecundity, development rates, mortality).
2. Pass the config path as a command‑line argument or replace the default `config/simulation.yaml`.
3. Adjust behavioural rules to match the species’ ecology.

### Adding a new agent type

1. Extend `Agent` or one of its subclasses.
2. Implement `updateState()` if needed. Lifecycle is usually driven by `LifecycleModel` or custom logic in `AgentLayer`.
3. Add a new `AgentLayer` in the YAML with its own rules.
4. Modify `App.configureSimulation()` to create the layer and seed it.

### Adding a new environmental layer

1. Add the layer to the YAML under `layers`.
2. Ensure the layer type is supported (`raster`, `timeseries`, `vector`, `occurrence`).
3. Add its token to `tokens` if rules need to reference it.
4. Use the token in JavaScript rule conditions.

### Writing new rules

Rules are defined in YAML under `agentLayers[].rules`:

```yaml
rules:
  - condition: "stage == 'ADULT' && gravid && energy > 0.5"
    action: "lay_eggs"
    priority: 10
```

Conditions are JavaScript expressions. Actions must match one of the supported action names. Higher priority rules are evaluated first.

---

## Troubleshooting

- **Memory mapping failed** – `MemoryMappedRasterLayer` refuses to fall back to heap allocation for very large rasters. Downscale the raster or increase `MAX_MAPPABLE_BYTES` in the code.
- **NetCDF time units not parsed** – check the `units` attribute of the time variable. Supported formats include `days since`, `hours since`, and `minutes since` with common date formats.
- **Missing raster or shapefile** – the simulation may fall back to synthetic data or default bounds. Check logs in `results/logs`.
- **GraalVM JavaScript errors** – ensure the GraalVM JavaScript dependencies are on the classpath and that rule conditions are valid JavaScript expressions.
- **Spatial inconsistency** – enable `-Ddebug.spatial` to log mismatches between layer agents and the spatial registry.
- **Slow ticks** – monitor the `[Monitor]` logs. Large agent counts, complex rules, or large search radii can slow down ticks. Increase heap size or reduce agent counts.
- **Shapefile CRS issues** – `VectorBoundsLoader` automatically reprojects to EPSG:4326. Check logs for CRS transformation messages.