import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from datetime import datetime, timedelta
import warnings
import os

warnings.filterwarnings('ignore')

# --- Configuration ---
TICKS_PER_DAY = 96
MINUTES_PER_TICK = 15
SIMULATION_START_DATE = datetime(2025, 9, 1)
STAGE_COLORS = {'ADULT': '#2E8B57', 'LARVA': '#4169E1', 'PUPA': '#FF8C00'}

# Create output directory
os.makedirs('output', exist_ok=True)

# --- Load Data ---
file_name = 'results/merged_snapshots_20260129_012653.csv'
df = pd.read_csv(file_name)

# --- Data Processing ---
df['Day'] = df['TickCount'] / TICKS_PER_DAY
df['Hour'] = (df['TickCount'] % TICKS_PER_DAY) * MINUTES_PER_TICK / 60
df['Date'] = SIMULATION_START_DATE + pd.to_timedelta(df['Day'], unit='D')

mosquito_df = df[df['Layer'] == 'Mosquitoes'].copy()
watertank_df = df[df['Layer'] == 'WaterTanks'].copy()

# 1. TEMPORAL DYNAMICS
daily_stats = mosquito_df.groupby('Day').agg({
    'AgentID': 'count',
    'Alive': 'sum',
    'Gravid': 'sum',
    'Energy': 'mean',
    'Age': 'mean',
    't2m': 'mean'
}).rename(columns={'AgentID': 'TotalCount', 'Alive': 'AliveCount'})

daily_stats['MortalityRate'] = (daily_stats['TotalCount'] - daily_stats['AliveCount']) / daily_stats['TotalCount'].replace(0, np.nan) * 100
stage_daily = mosquito_df.groupby(['Day', 'Stage']).size().unstack(fill_value=0)

fig, axes = plt.subplots(3, 2, figsize=(16, 14))
fig.suptitle('Mosquito Population Dynamics Over Time', fontsize=16, fontweight='bold')

axes[0, 0].plot(daily_stats.index, daily_stats['TotalCount'], marker='o', color='#2E8B57', label='Total')
axes[0, 0].set_title('Population Trend')
axes[0, 0].set_xlabel('Day')
axes[0, 0].set_ylabel('Count')
axes[0, 0].legend()
axes[0, 0].grid(True, alpha=0.3)

for stage, color in STAGE_COLORS.items():
    if stage in stage_daily.columns:
        axes[0, 1].plot(stage_daily.index, stage_daily[stage], label=stage, color=color, linewidth=2)
axes[0, 1].set_title('Life Stage Distribution')
axes[0, 1].legend()
axes[0, 1].grid(True, alpha=0.3)

axes[1, 0].plot(daily_stats.index, daily_stats['Gravid'], marker='o', color='#FF1493', label='Gravid')
axes[1, 0].set_title('Reproductive State')
axes[1, 0].set_ylabel('Gravid Females')
axes[1, 0].grid(True, alpha=0.3)

axes[1, 1].plot(daily_stats.index, daily_stats['MortalityRate'], marker='x', color='#DC143C', label='Mortality %')
axes[1, 1].set_title('Mortality Trend')
axes[1, 1].set_ylabel('Rate (%)')
axes[1, 1].grid(True, alpha=0.3)

if 't2m' in daily_stats.columns:
    axes[2, 0].plot(daily_stats.index, daily_stats['t2m'], marker='o', color='#FF6347')
    axes[2, 0].set_title('Temperature Trend')
    axes[2, 0].set_ylabel('Temp (°C)')
    axes[2, 0].grid(True, alpha=0.3)

if 'Age' in mosquito_df.columns:
    age_avg = mosquito_df.groupby('Day')['Age'].mean()
    axes[2, 1].plot(age_avg.index, age_avg.values, marker='o', color='#6495ED')
    axes[2, 1].set_title('Average Age Progression')
    axes[2, 1].set_ylabel('Age (ticks)')
    axes[2, 1].grid(True, alpha=0.3)

plt.tight_layout(rect=[0, 0.03, 1, 0.95])
plt.savefig('output/temporal_dynamics.png', dpi=300)
plt.close()

# 2. REFINED SPATIAL EVOLUTION (Mosquitoes + Water Tanks)
mosquito_ticks = sorted(mosquito_df['TickCount'].unique())
n_snapshots = len(mosquito_ticks)
n_cols = 2
n_rows = (n_snapshots + n_cols - 1) // n_cols

fig, axes = plt.subplots(n_rows, n_cols, figsize=(14, 6 * n_rows))
axes = axes.flatten()

for i, tick in enumerate(mosquito_ticks):
    ax = axes[i]
    m_data = mosquito_df[mosquito_df['TickCount'] == tick]
    wt_data = watertank_df[watertank_df['TickCount'] == tick]
    
    # Fallback for water tank background if no data at exact tick
    if wt_data.empty and not watertank_df.empty:
        wt_data = watertank_df[watertank_df['TickCount'] == watertank_df['TickCount'].min()]
    
    # Plot Water Tanks as black crosses
    ax.scatter(wt_data['X'], wt_data['Y'], marker='x', color='black', s=40, alpha=0.4, label='Water Tank', linewidths=1)
    
    # Plot Mosquitoes by Stage
    for stage, color in STAGE_COLORS.items():
        subset = m_data[m_data['Stage'] == stage]
        if not subset.empty:
            ax.scatter(subset['X'], subset['Y'], c=color, s=20, alpha=0.7, label=stage, edgecolors='white', linewidths=0.3)
    
    day_val = tick / TICKS_PER_DAY
    ax.set_title(f'Day {day_val:.2f} (Tick {tick})\nMosquitoes: {len(m_data)} | Tanks: {len(wt_data)}', fontsize=12, fontweight='bold')
    ax.set_xlabel('Longitude')
    ax.set_ylabel('Latitude')
    ax.grid(True, alpha=0.2)
    if i == 0:
        ax.legend(loc='upper right', frameon=True, fontsize='small')

# Remove empty subplots
for j in range(i + 1, len(axes)):
    fig.delaxes(axes[j])

plt.suptitle('Spatial Evolution: Mosquito Distribution vs Water Tanks (Black Crosses)', fontsize=16, fontweight='bold', y=0.98)
plt.tight_layout(rect=[0, 0.03, 1, 0.95])
plt.savefig('output/spatial_evolution_tanks.png', dpi=300)
plt.close()

# 3. DIURNAL PATTERNS
mosquito_df['HourOfDay'] = (mosquito_df['TickCount'] % TICKS_PER_DAY) * MINUTES_PER_TICK / 60
fig, axes = plt.subplots(1, 2, figsize=(14, 5))

# Hourly population
hourly_pop = mosquito_df.groupby('HourOfDay').size()
axes[0].plot(hourly_pop.index, hourly_pop.values, marker='o', color='#2E8B57')
axes[0].set_title('Population by Hour')
axes[0].set_xlabel('Hour')
axes[0].set_xticks(range(0, 25, 4))
axes[0].grid(True, alpha=0.3)

# Hourly Energy
if 'Energy' in mosquito_df.columns:
    hourly_energy = mosquito_df.groupby('HourOfDay')['Energy'].mean()
    axes[1].plot(hourly_energy.index, hourly_energy.values, marker='s', color='#FFD700')
    axes[1].set_title('Mean Energy by Hour')
    axes[1].set_xlabel('Hour')
    axes[1].set_xticks(range(0, 25, 4))
    axes[1].grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig('output/diurnal_patterns.png', dpi=300)
plt.close()

# 4. SUMMARY EXPORT
stats_summary = {
    'Total Records': len(mosquito_df),
    'Avg Daily Pop': daily_stats['TotalCount'].mean(),
    'Max Pop': daily_stats['TotalCount'].max(),
    'Avg Mortality %': daily_stats['MortalityRate'].mean(),
    'Avg Temp': mosquito_df['t2m'].mean() if 't2m' in mosquito_df.columns else 0
}
summary_df = pd.DataFrame(list(stats_summary.items()), columns=['Metric', 'Value'])
summary_df.to_csv('output/simulation_summary.csv', index=False)

print("Analysis completed. Visuals saved in 'output/' folder.")