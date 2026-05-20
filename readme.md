
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
<a href="src/main/resources/config/simulation.yaml">
    src/main/resources/config/simulation.yaml
</a>.


### Specie's specification details

The `species` section defines temperature‑dependent coefficients for the Metzler matrix life‑cycle model.  
All temperatures in the equations are in **degrees Celsius**. The simulation automatically converts Kelvin (from climate data) to Celsius before applying these formulas.

| Parameter | Meaning |Yaml Key |
|:---|:---|:---|
|*Egg and pupa development (Sharpe--Schoolfield)*|
|$\rho$ | Development rate scale | $\mathrm{day}^{-1}$ | egg_dev_rho, pupa_dev_rho |
|$k$ | Optimal temperature | $^{\circ}\mathrm{C}$ | egg_dev_k, pupa_dev_k |
|$\Delta$ | Temperature sensitivity | $^{\circ}\mathrm{C}$ | egg_dev_Delta, pupa_dev_Delta |
|$\lambda$ | Baseline development offset | $\mathrm{day}^{-1}$ | egg_dev_lambda, pupa_dev_lambda |
|Larva development (Brière) |
|$a$ | Scaling factor | $\mathrm{day}^{-1} , ^{\circ}\mathrm{C}^{-(1+1/m)}$ | larva_dev_a |
|$T_{\min}$ | Minimum temperature | $^{\circ}\mathrm{C}$ | larva_dev_Tmin |
|$T_{\max}$ | Maximum temperature | $^{\circ}\mathrm{C}$ | larva_dev_Tmax |
|$m$ | Shape exponent | --- | larva_dev_m | \addlinespace
|*Stage survival (Gaussian)*|
|$\mathrm{amp}_X$ | Maximum survival | --- | <Stage>_survival_amp |
|$\mu_X$ | Optimum temperature | $^{\circ}\mathrm{C}$ | <Stage>_survival_mean |
|$\sigma_X$ | Temperature tolerance width | $^{\circ}\mathrm{C}$ | <Stage>_survival_sigma|
|*Adult mortality (exponential quadratic)*|
|$b_1$ | Intercept | --- | adult_mort_b1 |
|$b_2$ | Linear coefficient | $^{\circ}\mathrm{C}^{-1}$ | adult_mort_b2 |
|$b_3$ | Quadratic coefficient | $^{\circ}\mathrm{C}^{-2}$ | adult_mort_b3 |
|*Fecundity*|
|$a$ | Maximum fecundity scale | eggs/fem/day | fecundity_rmax |
|$b$ | Exponential coefficient | $^{\circ}\mathrm{C}^{-1}$ | fecundity_c |
|$T_{\max}$ | Temp. of max fecundity | $^{\circ}\mathrm{C}$ | fecundity_Topt |
|$c$ | Quadratic decay parameter | $^{\circ}\mathrm{C}^{-2}$ | fecundity_c | 
___


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

A Jupyter notebook (accessible at <a href="analyze_sim_data.ipynb">/analyze_sim_data.ipynb</a>) can load the merged CSV and produce:
- Population dynamics plots (by stage)
- Temperature correlation
- Proximity analysis to water sources
- Spatial Suitability Maps
- Models Validation
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


