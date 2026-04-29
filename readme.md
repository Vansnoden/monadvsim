
# MONADSIM – Multi‑Agent Mosquito Simulation Framework

A high‑performance, rule‑based, individual‑based simulation framework for mosquito population dynamics, with a focus on *Anopheles stephensi*. The model integrates temperature‑dependent development and survival rates (Metzler matrix), environmental raster data (elevation, buildings, population density), climate time series (NetCDF), and behavioural rules written in JavaScript (GraalVM). It is designed to be generic for other vector species.

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
- **Temperature‑dependent life cycle** – egg, larva, pupa, adult stages transition according to validated rate equations (Metzler matrix).
- **Behavioural rules** – written in JavaScript, evaluated via GraalVM (feed, rest, lay eggs, move randomly, etc.).
- **Environmental context** – agents query raster layers (elevation, buildings, population) and climate variables (temperature, precipitation) at their current location.
- **Spatial indexing** – grid‑based spatial registry for efficient neighbour queries.
- **Parallel processing** – agent updates are batched and processed in parallel using a custom thread pool.
- **Memory‑efficient rasters** – large GeoTIFF files are memory‑mapped.
- **NetCDF climate data** – supports ERA5‑style NetCDF files with hourly data; temporal interpolation between frames.
- **Incremental spatial updates** – births, deaths, and movements are queued and applied in bulk.
- **Snapshot export** – periodic CSV exports of all agent states (tick, position, stage, age, energy, etc.).
- **YAML configuration** – all simulation parameters (timing, world bounds, file paths, species parameters, rules, seeding) are defined in a single YAML file.

---

## System Requirements

- **Java 17** (LTS)
- **Maven 3.6+**
- **Memory** – at least 8 GB RAM recommended for large simulations (10k+ tanks, 250k+ mosquitoes)
- **Disk space** – several GB for results (CSV snapshots)

---

## Installation & Build

Clone the repository and build with Maven:

```bash
git clone <repository-url>
cd monadvsim
mvn clean compile
```

### Dependencies (managed by Maven)

- GeoTools (raster handling)
- NetCDF (climate data)
- GraalVM JavaScript (rule evaluation)
- Jackson (YAML configuration)
- JFreeChart (not used directly but required by some classes)
- Aparapi (optional GPU acceleration – not activated)

---

## Configuration

All simulation settings are stored in a single YAML file located at  
`src/main/resources/config/simulation.yaml`.

### Example YAML structure

```yaml
time:
  startDateTime: "2025-09-01T00:00:00"
  totalTicks: 5760        # 30 days * 24 * 4 (15‑min ticks)
  tickMinutes: 15

world:
  centerLat: 41.8562149505558
  centerLon: 9.604134790332163
  bufferKm: 5
  cellSize: 0.001          # spatial registry grid cell size (degrees)

files:
  elevation: "prepared_data/elevation_5_km.tiff"
  buildings: "prepared_data/buildings_100_km.tif"
  population: "prepared_data/population_density_100m.tif"
  climateNetCDF: "prepared_data/historical_climate_2025_5_km_9_12.nc"

species:
  fecundity_a: 0.378
  fecundity_b: 0.173
  fecundity_Tmax: 40.0
  fecundity_c: 2.97
  egg_dev_a: -0.0009
  egg_dev_b: 0.048
  egg_dev_c: -0.345
  larva_dev_a: -0.0007
  larva_dev_b: 0.039
  larva_dev_c: -0.32
  pupa_dev_a: -0.0005
  pupa_dev_b: 0.026
  pupa_dev_c: -0.2
  egg_survival_amp: 1.01
  egg_survival_mean: 24.5
  egg_survival_sigma: 4.8
  larva_survival_amp: 0.95
  larva_survival_mean: 27.0
  larva_survival_sigma: 3.5
  pupa_survival_amp: 0.93
  pupa_survival_mean: 26.8
  pupa_survival_sigma: 3.8
  adult_mort_a: -0.00065
  adult_mort_b: 0.0364
  adult_mort_c: -0.4882

agentLayers:
  - name: "Mosquitoes"
    rules:
      - condition: "agent.stage == 'ADULT' && agent.energy < 0.5"
        action: "feed"
        priority: 5
      - condition: "agent.gravid == true && temperature > 293.15 && building_density > 0.1 && population > 0.05"
        action: "lay_eggs"
        priority: 6
      # ... more rules

  - name: "WaterTanks"
    rules:
      - condition: "agent.waterVolume <= 5 && agent.waterVolume > 0"
        action: "dry_out"
        priority: 3
      # ...

seeding:
  tanksToSeed: 10000
  mosquitoesToSeed: 250000
  habitatGridSizeX: 50
  habitatGridSizeY: 50
  tankBuildingThreshold: 0.15
  tankPopulationThreshold: 0.05
  mosquitoBuildingThreshold: 0.05
  mosquitoPopulationThreshold: 0.02

project:
  defaultAgentSearchRadius: 0.0005      # degrees (~55 m)
  defaultAgentStep: 0.00005             # degrees (~5.5 m per tick)
  defaultMaxAgentAge: 1920              # ticks (20 days)
```

### Specie's specification details

# Species Parameters in `simulation.yaml`

The `species` section defines temperature‑dependent coefficients for the Metzler matrix life‑cycle model.  
All temperatures in the equations are in **degrees Celsius**. The simulation automatically converts Kelvin (from climate data) to Celsius before applying these formulas.

| Parameter | Description | Mathematical expression | Unit | Typical value (*An. stephensi*) |
|-----------|-------------|------------------------|------|--------------------------------|
| `fecundity_a` | Amplitude factor for fecundity | `F(T) = a · exp(b·T) – exp(b·Tmax – ((Tmax – T)/c)²)` | eggs·female⁻¹·day⁻¹ | 0.378 |
| `fecundity_b` | Exponential coefficient for fecundity | – | °C⁻¹ | 0.173 |
| `fecundity_Tmax` | Temperature where fecundity reaches zero | – | °C | 40.0 |
| `fecundity_c` | Shape parameter for the descending limb | – | °C | 2.97 |
| `egg_dev_a` | Quadratic coefficient for egg development rate | `dE(T) = max(0, a·T² + b·T + c)` | day⁻¹·°C⁻² | -0.0009 |
| `egg_dev_b` | Linear coefficient for egg development rate | – | day⁻¹·°C⁻¹ | 0.048 |
| `egg_dev_c` | Intercept for egg development rate | – | day⁻¹ | -0.345 |
| `larva_dev_a` | Quadratic coefficient for larval development rate | same quadratic form | day⁻¹·°C⁻² | -0.0007 |
| `larva_dev_b` | Linear coefficient for larval development rate | – | day⁻¹·°C⁻¹ | 0.039 |
| `larva_dev_c` | Intercept for larval development rate | – | day⁻¹ | -0.32 |
| `pupa_dev_a` | Quadratic coefficient for pupal development rate | same quadratic form | day⁻¹·°C⁻² | -0.0005 |
| `pupa_dev_b` | Linear coefficient for pupal development rate | – | day⁻¹·°C⁻¹ | 0.026 |
| `pupa_dev_c` | Intercept for pupal development rate | – | day⁻¹ | -0.2 |
| `egg_survival_amp` | Maximum survival probability for eggs | `S_E(T) = amp · exp( –0.5 · ((T – μ)/σ)² )` | dimensionless | 1.01 |
| `egg_survival_mean` | Optimal temperature for egg survival | μ | °C | 24.5 |
| `egg_survival_sigma` | Temperature tolerance width for eggs | σ | °C | 4.8 |
| `larva_survival_amp` | Maximum larval survival probability | same Gaussian form | dimensionless | 0.95 |
| `larva_survival_mean` | Optimal temperature for larval survival | μ | °C | 27.0 |
| `larva_survival_sigma` | Temperature tolerance width for larvae | σ | °C | 3.5 |
| `pupa_survival_amp` | Maximum pupal survival probability | same Gaussian form | dimensionless | 0.93 |
| `pupa_survival_mean` | Optimal temperature for pupal survival | μ | °C | 26.8 |
| `pupa_survival_sigma` | Temperature tolerance width for pupae | σ | °C | 3.8 |
| `adult_mort_a` | Quadratic coefficient for adult mortality rate | `μ_A(T) = max(0, a·T² + b·T + c)` | day⁻¹·°C⁻² | -0.00065 |
| `adult_mort_b` | Linear coefficient for adult mortality rate | – | day⁻¹·°C⁻¹ | 0.0364 |
| `adult_mort_c` | Intercept for adult mortality rate | – | day⁻¹ | -0.4882 |

**Notes:**

- Development rates (`dE`, `dL`, `dP`) are in **1/day**. The probability of completing a stage in one tick is `1 – exp(–rate · dt)`, where `dt` is the tick duration in days (e.g., 0.25/24 = 0.0104167 days for a 15‑minute tick).
- Survival probabilities (`S_E`, `S_L`, `S_P`) are applied **once** when the stage is completed (i.e., a larva that finishes development survives with probability `S_L`).
- Adult mortality is applied every tick as a daily rate converted to a per‑tick probability: `p_die = 1 – exp(–μ_A · dt)`.
- Fecundity `F(T)` is in **eggs per female per day**. The actual number of eggs laid per tick is:


### Preparing Input Data

- **Raster layers** (GeoTIFF): elevation, building density, population density.  
  They must cover the defined world bounds (center ± buffer).  
- **Climate NetCDF** – should contain variables `t2m` (temperature in Kelvin) and `tp` (precipitation in m).  
  Dimensions must be `(valid_time, latitude, longitude)`.  
  If files are missing, the simulation falls back to synthetic data.

---

## Running the Simulation

From the project root:

```bash
mvn exec:java
```

This will:
- Load the YAML configuration from the classpath.
- Load all raster and climate data.
- Seed initial agents (water tanks and mosquitoes).
- Start the simulation loop.
- Export snapshots every 100 ticks to `results/snapshot_tick_<tick>.csv`.
- After completion, merge all snapshots into a single CSV (`results/merged_snapshots_<timestamp>.csv`).
- Save final statistics to a text file.

### JVM Options

The `pom.xml` already configures the `exec-maven-plugin` with recommended GC settings and heap size (`-Xmx8g -Xms4g`). You can override them via command line:

```bash
mvn exec:java -Dexec.jvmArgs="-Xmx12g -Xms6g"
```

---

## Output & Results

### Snapshot CSV files

Each snapshot contains one row per agent at a given tick with the following columns:

- `TickCount`
- `AgentID`
- `Layer` (Mosquitoes / WaterTanks)
- `AgentType` (LivingAgent / InertAgent)
- `X`, `Y` (longitude, latitude)
- `Alive` (1/0)
- `Age` (ticks)
- `Stage` (ADULT, LARVA, PUPA)
- `Energy` (0‑1)
- `Gravid` (1/0)
- `EggCount`, `LarvalCount`, `WaterVolume` (for tanks)
- `Resting`, `RestingDuration`, `TimeWithoutRest`
- Environmental variables at the agent’s location (`t2m`, `tp`, `population`, `building_density`, `elevation`)

### Merged file

After the simulation ends, all snapshots are merged into a single CSV file using the `SnapshotMerger` utility. This file is suitable for analysis with Python/R.

### Statistics file

A final statistics file (`results/<project>_final_statistics_<timestamp>.txt`) contains:
- Agent counts per layer
- Spatial registry performance
- Memory usage
- Average tick time
- Final tick and effective FPS

### Analysis notebook

A Jupyter notebook (provided separately) can load the merged CSV and produce:
- Population dynamics plots (by stage)
- Temperature correlation
- Spatial heatmaps
- Proximity analysis to water sources

---

## Code Architecture

### Core packages

- `com.monadvsim.app` – main entry point `App`.
- `models.engine` – simulation core:  
  - `SimulationEngine` – main loop, tick management, snapshot export.  
  - `TimeManager` – simulation time, frame indexing.  
  - `SpatialRegistry` – grid‑based spatial indexing.  
  - `RuleEngine` – GraalVM JavaScript rule evaluation.  
  - `AgentLifeCycleManager` – queues for births/deaths/moves.  
  - `LifecycleModel` – temperature‑dependent rates (Metzler matrix).  
  - `SpeciesParameters` – species coefficients (loaded from YAML).
- `models.entities` – agent classes:  
  - `Agent` (abstract), `LivingAgent` (mosquitoes), `InertAgent` (water tanks).  
  - `AgentContainer` – thread‑safe partitioned storage.  
  - `AgentLayer` – container + rule processing.  
  - `RasterLayer`, `InterpolatedRasterLayer`, `MemoryMappedRasterLayer`.  
  - `Project` – container for all layers and global settings.
- `models.services` – `ProjectPersistenceService` (loads rasters, NetCDF, exports CSV).
- `models.utils` – `ResourceManager`, `ManagedExecutorService`, `SnapshotMerger`, `ConfigLoader`.

### Key workflows

1. **Initialisation**  
   - Parse YAML → create `TimeManager`, `SpatialRegistry`, `LifecycleModel`.  
   - Load rasters and climate NetCDF.  
   - Create agent layers and add rules from YAML.  
   - Seed initial agents using weighted habitat suitability.

2. **Simulation tick**  
   - Advance time (`TimeManager.tick()`).  
   - Update environment (set active frame of raster layers).  
   - Process each agent layer:  
     - Apply temperature‑dependent stage transitions (egg→larva→pupa→adult, mortality).  
     - Evaluate behavioural rules (JavaScript) in priority order.  
     - Execute actions (feed, move, rest, lay eggs, die).  
     - Automatic hatching for water tanks (rate‑based).  
   - Apply pending spatial updates (births, deaths, moves) to `SpatialRegistry`.  
   - Export snapshot every 100 ticks.  
   - Adaptive sleep to maintain ~60 FPS.

3. **Shutdown**  
   - Export final snapshot.  
   - Merge all snapshots into one CSV.  
   - Clean up resources (thread pools, memory‑mapped files).

---

## Extending the Model

### Adding a new species

1. Create a new YAML configuration (e.g., `aedes_aegypti.yaml`) with the appropriate coefficients (fecundity, development rates, survival).  
2. Change the `simulation.yaml` path in `App.main()` or pass it as a command‑line argument (future enhancement).  
3. Adjust behavioural rules to match the species’ ecology.

### Adding a new agent type

1. Extend `Agent` or one of its subclasses.  
2. Implement `updateState()` if needed (usually not, because lifecycle is driven by `LifecycleModel`).  
3. Add a new `AgentLayer` in the YAML with its own rules.  
4. Modify `App.configureSimulation()` to create the layer and seed it.

### Adding a new environmental layer

1. Add the layer to the YAML under `files`.  
2. Load it in `configureSimulation()` using `ProjectPersistenceService.loadRasterData()`.  
3. Add its token to the `tokens` and `layerNames` lists in `App` (or externalise those to YAML as well).  
4. Use the token in JavaScript rule conditions.

---

## Troubleshooting

| Problem | Likely cause | Solution |
|---------|--------------|----------|
| `FileNotFoundException` for `simulation.yaml` | YAML not in classpath | Place file in `src/main/resources/config/` and rebuild. |
| OutOfMemoryError | Too many agents or large rasters | Increase heap (`-Xmx12g`), reduce `tanksToSeed`/`mosquitoesToSeed`. |
| Simulation stuck | Deadlock in rule evaluation | Check for infinite loops in JavaScript conditions. Enable debug logging. |
| No snapshots exported | Results directory not writable | Create `results/` folder manually or check permissions. |
| `GridCoverage2D` disposal errors | GeoTools resource leak | Already handled by `ResourceManager`; ignore warnings. |
| GraalVM context creation fails | Missing `--add-opens` JVM flags | Ensure `pom.xml` includes `--add-opens=java.base/java.lang=ALL-UNNAMED` etc. |

---

## License

MIT

---

<!-- ## Contact & Citation -->

<!-- For questions or to report issues, please contact [your email / GitHub issues].

If you use this framework in a publication, cite:

> [Your name et al., "MONADSIM: A high‑performance multi‑agent mosquito simulation", Year, Journal/DOI] -->

```


#### CLipped climatic data

```
./clip_timeseries_data \
    --source=/mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/climatic_timeseries_data/2020_data.grib \
    --bounds=/home/void/Documents/codes/monadvsim/prepared_data/dire/dire_dawa.shp \
    --target=/home/void/Documents/codes/monadvsim/prepared_data/dire_dawa/climate_t2m_tp_2020.nc \
    --daily \
    --buffer=1.0 \
    --variables=2t,tp \
    --rename-t2m \
    --verbose

```


#### Inspect clipped data:

```cdo sinfo /home/void/Documents/codes/monadvsim/prepared_data/dire_dawa/climate_t2m_tp_2020.nc```

```cdo showvar /home/void/Documents/codes/monadvsim/prepared_data/dire_dawa/climate_t2m_tp_2020.nc```

```ogrinfo -so -al /home/void/Documents/codes/monadvsim/prepared_data/dire_dawa/dire_dawa.shp```

renaming variable:

```cdo chname,2t,t2m,tp,tp climate_t2m_tp_2020.nc climate_t2m_tp_2020.nc```


#### Downscaling large rasters before processing

```
gdal_translate -outsize 10% 10% /mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/somali_building.tif /mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/small_somali_building.tif
```

```
{
  gdalinfo -stats "/mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/small_somali_building.tif"
  echo "========================================="
  gdalinfo -stats "/mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/small_somali_elevation.tif"
  echo "========================================="
  gdalinfo -stats "/mnt/monadworld/projects/phd/article_manuscripts/a_cdm_for_vector_of_vbds/data/datasets/gee_exports/Somali_EO_Export_10m-20260425T143410Z-3-001/Somali_EO_Export_10m/small_somali_population.tif"
} | xclip -selection clipboard
```


#### Merge many geotiff into one

```
# Merge Building Density
gdalbuildvrt Somali_Building_Density_10m.vrt Somali_Building_Density_10m-*.tif
gdal_translate -co COMPRESS=LZW -co BIGTIFF=IF_NEEDED Somali_Building_Density_10m.vrt Somali_Building_Density_10m.tif

# Merge Elevation
gdalbuildvrt Somali_Elevation_10m.vrt Somali_Elevation_10m-*.tif
gdal_translate -co COMPRESS=LZW -co BIGTIFF=IF_SAFER Somali_Elevation_10m.vrt Somali_Elevation_10m.tif

# Merge Population
gdalbuildvrt Somali_Population_10m.vrt Somali_Population_10m-*.tif
gdal_translate -co COMPRESS=LZW -co BIGTIFF=IF_SAFER Somali_Population_10m.vrt Somali_Population_10m.tif
```