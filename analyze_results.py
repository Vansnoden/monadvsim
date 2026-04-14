import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from datetime import datetime, timedelta
from scipy.spatial import cKDTree

# Configuration
TICKS_PER_DAY = 96
MINUTES_PER_TICK = 15
SIMULATION_START_DATE = datetime(2025, 9, 1)
STAGE_COLORS = {'ADULT': '#2E8B57', 'LARVA': '#4169E1', 'PUPA': '#FF8C00'}

INPUT_FILE_PATH = "results/merged_snapshots_20260130_022232.csv"
OUTPUT_FILE_FOLDER = "output"

# Load
df = pd.read_csv(INPUT_FILE_PATH)
mosquito_df = df[df['Layer'] == 'Mosquitoes'].copy()
watertank_df = df[df['Layer'] == 'WaterTanks'].copy()

# 1. TEMPORAL TRENDS
mosquito_df['Day'] = mosquito_df['TickCount'] / TICKS_PER_DAY
daily_pop = mosquito_df.groupby('Day').size()
stage_pop = mosquito_df.groupby(['Day', 'Stage']).size().unstack(fill_value=0)

# 2. ENVIRONMENTAL CORRELATION
# Get average temp per day
daily_env = mosquito_df.groupby('Day')[['t2m', 'Elevation']].mean()

# 3. SPATIAL PROXIMITY TO BREEDING SITES
# We'll check the proximity of ADULT mosquitoes to WaterTanks
# because larvae are naturally at the tanks. Proximity of adults indicates seeking breeding/resting sites.
representative_ticks = sorted(mosquito_df['TickCount'].unique())
# Pick 4 snapshots
indices = [0, len(representative_ticks)//3, 2*len(representative_ticks)//3, len(representative_ticks)-1]
target_ticks = [representative_ticks[i] for i in indices]

proximity_results = []

for tick in target_ticks:
    m_subset = mosquito_df[(mosquito_df['TickCount'] == tick) & (mosquito_df['Stage'] == 'ADULT')]
    w_subset = watertank_df[watertank_df['TickCount'] == tick]
    
    if w_subset.empty: # Use first available if not in this tick
        w_subset = watertank_df[watertank_df['TickCount'] == watertank_df['TickCount'].min()]
        
    if not m_subset.empty and not w_subset.empty:
        tree = cKDTree(w_subset[['X', 'Y']].values)
        distances, _ = tree.query(m_subset[['X', 'Y']].values)
        proximity_results.append({
            'Tick': tick,
            'Day': tick/TICKS_PER_DAY,
            'AvgDist': np.mean(distances),
            'MedianDist': np.median(distances),
            'Count': len(m_subset)
        })

proximity_summary = pd.DataFrame(proximity_results)

# --- VISUALIZATION ---

# Figure 1: Population and Stage Dynamics
plt.figure(figsize=(12, 6))
plt.subplot(1, 2, 1)
for stage in STAGE_COLORS:
    if stage in stage_pop.columns:
        plt.plot(stage_pop.index, stage_pop[stage], label=stage, color=STAGE_COLORS[stage], linewidth=2)
plt.title('A. Population Dynamics by Life Stage')
plt.xlabel('Simulation Day')
plt.ylabel('Agent Count')
plt.legend()
plt.grid(True, alpha=0.3)

plt.subplot(1, 2, 2)
ax1 = plt.gca()
ax1.plot(daily_pop.index, daily_pop.values, color='black', label='Total Pop', linewidth=2, linestyle='--')
ax1.set_ylabel('Total Mosquitoes')
ax2 = ax1.twinx()
ax2.plot(daily_env.index, daily_env['t2m'], color='red', label='Temp (°C)', alpha=0.6)
ax2.set_ylabel('Mean Temperature (°C)', color='red')
plt.title('B. Total Population vs Temperature')
plt.grid(True, alpha=0.1)
plt.tight_layout()
plt.savefig(f'{OUTPUT_FILE_FOLDER}/dynamics_analysis.png', dpi=300)

# Figure 2: Spatial Clustering Heatmaps
fig, axes = plt.subplots(2, 2, figsize=(16, 14))
axes = axes.flatten()

for i, tick in enumerate(target_ticks):
    ax = axes[i]
    m_data = mosquito_df[mosquito_df['TickCount'] == tick]
    w_data = watertank_df[watertank_df['TickCount'] == tick]
    if w_data.empty: w_data = watertank_df[watertank_df['TickCount'] == watertank_df['TickCount'].min()]
    
    # Background: Water Tank locations
    ax.scatter(w_data['X'], w_data['Y'],  
               s=2,
               marker='x',          # Sets the shape to a cross
               color='black', 
               alpha=0.2, 
               linewidths=1.5,
               label='Water Sources')
    
    # Foreground: Mosquito Heatmap
    if len(m_data) > 5:
        sns.kdeplot(data=m_data, x='X', y='Y', fill=True, cmap='viridis', alpha=0.6, ax=ax)
    
    # Scatter of actual mosquitoes
    for stage, color in STAGE_COLORS.items():
        subset = m_data[m_data['Stage'] == stage]
        ax.scatter(subset['X'], subset['Y'], c=color, s=10, alpha=0.7, label=stage if i==0 else "")

    day_val = tick / TICKS_PER_DAY
    ax.set_title(f'Day {day_val:.1f} (N={len(m_data)})')
    if i == 0: ax.legend(loc='upper right', markerscale=5)

plt.suptitle('Spatio-Temporal Distribution of An. stephensi', fontsize=16)
plt.tight_layout(rect=[0, 0.03, 1, 0.95])
plt.savefig(f'{OUTPUT_FILE_FOLDER}/spatial_clustering.png', dpi=300)

# Figure 3: Proximity Analysis (Article Key Insight)
plt.figure(figsize=(8, 5))
plt.plot(proximity_summary['Day'], proximity_summary['AvgDist'] * 111, marker='o', color='darkred', label='Avg Dist (km)')
plt.title('Mosquito Proximity to Nearest Water Source (Potential Breeding Sites)')
plt.xlabel('Day')
plt.ylabel('Mean Distance (km)')
plt.grid(True, alpha=0.3)
plt.savefig(f'{OUTPUT_FILE_FOLDER}/proximity_trend.png', dpi=300)

# Save Stats
proximity_summary.to_csv(f'{OUTPUT_FILE_FOLDER}/proximity_stats.csv', index=False)
daily_stats = pd.concat([daily_pop.rename('TotalPop'), stage_pop, daily_env], axis=1)
daily_stats.to_csv(f'{OUTPUT_FILE_FOLDER}/daily_dynamics_stats.csv')

# Read the generated stats to provide concrete numbers
daily_stats = pd.read_csv(f'{OUTPUT_FILE_FOLDER}/daily_dynamics_stats.csv')
proximity_stats = pd.read_csv(f'{OUTPUT_FILE_FOLDER}/proximity_stats.csv')

# print("Daily Stats Summary:")
# print(daily_stats.head())
# print(daily_stats.tail())

# print("\nProximity Stats Summary:")
# print(proximity_stats)

# Peak population and final population in the snapshots
peak_pop = daily_stats['TotalPop'].max()
final_pop = daily_stats['TotalPop'].iloc[-1]
avg_temp = daily_stats['t2m'].mean()

# print(f"\nPeak Pop: {peak_pop}")
# print(f"Final Pop in snapshots: {final_pop}")
# print(f"Average Temp: {avg_temp:.2f}")

env_trend = watertank_df.groupby('TickCount')[['t2m', 'tp']].mean()

# Plot environmental trend
plt.figure(figsize=(10, 5))
plt.plot(env_trend.index / TICKS_PER_DAY, env_trend['t2m'], label='Temp (t2m)', color='red')
plt.axhline(y=15.25, color='blue', linestyle='--', label='MS Mean Temp (15.25)')
plt.title('Environmental Conditions Over Entire Simulation (via WaterTank Data)')
plt.xlabel('Day')
plt.ylabel('Temperature (°C)')
plt.legend()
plt.grid(True)
plt.savefig(f'{OUTPUT_FILE_FOLDER}/env_full_trend.png')