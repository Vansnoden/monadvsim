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
file_name = 'results/merged_snapshots_20260129_103310.csv'
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
n_total_ticks = len(mosquito_ticks)
indices = [0, n_total_ticks // 3, (2 * n_total_ticks) // 3, n_total_ticks - 1]
selected_ticks = [mosquito_ticks[i] for i in indices]

# 2. Setup the 2x2 grid
fig, axes = plt.subplots(2, 2, figsize=(16, 14))
axes = axes.flatten()

for i, tick in enumerate(selected_ticks):
    ax = axes[i]
    m_data = mosquito_df[mosquito_df['TickCount'] == tick]
    wt_data = watertank_df[watertank_df['TickCount'] == tick]
    
    # Fallback for water tanks
    if wt_data.empty and not watertank_df.empty:
        wt_data = watertank_df[watertank_df['TickCount'] == watertank_df['TickCount'].min()]
    
    # --- ADDED: Density Heatmap Layer ---
    if len(m_data) > 1:
        sns.kdeplot(
            data=m_data, x='X', y='Trend', 
            fill=True, thresh=0.05, levels=10, 
            cmap="Reds", alpha=0.3, ax=ax, zorder=1
        )

    # Plot Mosquitoes by Stage
    for stage, color in STAGE_COLORS.items():
        subset = m_data[m_data['Stage'] == stage]
        if not subset.empty:
            ax.scatter(subset['X'], subset['Y'], c=color, s=20, alpha=0.6, 
                       label=stage, edgecolors='white', linewidths=0.2, zorder=3)

    # Plot Water Tanks
    ax.scatter(wt_data['X'], wt_data['Y'], marker='x', color='black', 
               s=60, alpha=0.7, label='Water Tank', linewidths=1.5, zorder=4)
    
    day_val = tick / 96 
    ax.set_title(f'Snapshot {i+1}: Day {day_val:.2f}\nPopulation: {len(m_data)}', 
                 fontsize=14, fontweight='bold')
    ax.grid(True, linestyle='--', alpha=0.4)
    
    if i == 0:
        ax.legend(loc='upper right', frameon=True)

plt.suptitle('Spatial Evolution & Population Density Heatmap', fontsize=20, fontweight='bold', y=0.98)
plt.tight_layout(rect=[0, 0.03, 1, 0.95])
plt.savefig('output/mosquito_density_trend.png', dpi=300)
plt.show()

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