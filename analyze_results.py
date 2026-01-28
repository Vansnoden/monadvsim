import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from datetime import datetime, timedelta
import warnings
warnings.filterwarnings('ignore')
import os

os.makedirs('output', exist_ok=True)

TICKS_PER_DAY = 96
MINUTES_PER_TICK = 15
SIMULATION_START_DATE = datetime(2025, 9, 1)

STAGE_COLORS = {'ADULT': '#2E8B57', 'LARVA': '#4169E1', 'PUPA': '#FF8C00'}
GRAVID_COLORS = {True: '#FF1493', False: '#4682B4'}

print("Loading simulation data...")
df = pd.read_csv('results/merged_snapshots_20260129_012653.csv')

print(f"\nDATA OVERVIEW:")
print(f"Total records: {len(df):,}")
print(f"Tick range: {df['TickCount'].min():,} to {df['TickCount'].max():,}")
print(f"Unique ticks: {len(df['TickCount'].unique())}")

df['Day'] = df['TickCount'] / TICKS_PER_DAY
df['Hour'] = (df['TickCount'] % TICKS_PER_DAY) * MINUTES_PER_TICK / 60
df['Date'] = SIMULATION_START_DATE + pd.to_timedelta(df['Day'], unit='D')

mosquito_df = df[df['Layer'] == 'Mosquitoes'].copy()

print(f"\nMOSQUITO DATA:")
print(f"Mosquito records: {len(mosquito_df):,}")
print(f"Day range: {mosquito_df['Day'].min():.2f} to {mosquito_df['Day'].max():.2f}")
print(f"Unique days: {len(mosquito_df['Day'].unique())}")

daily_stats = mosquito_df.groupby('Day').agg({
    'AgentID': 'count',
    'Alive': 'sum',
    'Gravid': 'sum',
    'Energy': 'mean',
    'Age': 'mean',
    't2m': 'mean'
}).rename(columns={'AgentID': 'TotalCount', 'Alive': 'AliveCount'})

daily_stats['DeadCount'] = daily_stats['TotalCount'] - daily_stats['AliveCount']
daily_stats['MortalityRate'] = daily_stats['DeadCount'] / daily_stats['TotalCount'].replace(0, np.nan) * 100

stage_daily = mosquito_df.groupby(['Day', 'Stage']).size().unstack(fill_value=0)

fig, axes = plt.subplots(3, 2, figsize=(16, 14))
fig.suptitle('Mosquito Population Dynamics', fontsize=16, fontweight='bold')

axes[0, 0].plot(daily_stats.index, daily_stats['TotalCount'], 
         marker='o', markersize=4, linewidth=2, color='#2E8B57', label='Total')
axes[0, 0].plot(daily_stats.index, daily_stats['AliveCount'], 
         marker='s', markersize=4, linewidth=2, color='#32CD32', label='Alive', linestyle='--')
axes[0, 0].fill_between(daily_stats.index, 0, daily_stats['TotalCount'], alpha=0.2, color='#2E8B57')
axes[0, 0].set_title('Total Population Trend', fontsize=12, fontweight='bold')
axes[0, 0].set_xlabel('Day')
axes[0, 0].set_ylabel('Number of Mosquitoes')
axes[0, 0].legend()
axes[0, 0].grid(True, alpha=0.3)

if 'ADULT' in stage_daily.columns:
    axes[0, 1].plot(stage_daily.index, stage_daily['ADULT'], label='Adult', color=STAGE_COLORS['ADULT'], linewidth=2)
if 'LARVA' in stage_daily.columns:
    axes[0, 1].plot(stage_daily.index, stage_daily['LARVA'], label='Larva', color=STAGE_COLORS['LARVA'], linewidth=2)
if 'PUPA' in stage_daily.columns:
    axes[0, 1].plot(stage_daily.index, stage_daily['PUPA'], label='Pupa', color=STAGE_COLORS['PUPA'], linewidth=2)
axes[0, 1].set_title('Life Stage Composition', fontsize=12, fontweight='bold')
axes[0, 1].set_xlabel('Day')
axes[0, 1].set_ylabel('Count')
axes[0, 1].legend()
axes[0, 1].grid(True, alpha=0.3)

axes[1, 0].plot(daily_stats.index, daily_stats['Gravid'], 
         marker='o', markersize=4, linewidth=2, color='#FF1493', label='Gravid Females')
axes[1, 0].set_title('Reproductive Dynamics', fontsize=12, fontweight='bold')
axes[1, 0].set_xlabel('Day')
axes[1, 0].set_ylabel('Count')
axes[1, 0].legend()
axes[1, 0].grid(True, alpha=0.3)

axes[1, 1].plot(daily_stats.index, daily_stats['MortalityRate'], 
         marker='x', markersize=4, linewidth=2, color='#DC143C', label='Mortality Rate (%)')
ax4_twin = axes[1, 1].twinx()
ax4_twin.plot(daily_stats.index, daily_stats['Energy'], 
              marker='o', markersize=4, linewidth=2, color='#FFD700', label='Avg Energy', alpha=0.7)
axes[1, 1].set_title('Mortality and Energy Trends', fontsize=12, fontweight='bold')
axes[1, 1].set_xlabel('Day')
axes[1, 1].set_ylabel('Mortality Rate (%)', color='#DC143C')
ax4_twin.set_ylabel('Average Energy', color='#FFD700')
axes[1, 1].tick_params(axis='y', labelcolor='#DC143C')
ax4_twin.tick_params(axis='y', labelcolor='#FFD700')
axes[1, 1].grid(True, alpha=0.3)

if 't2m' in daily_stats.columns:
    axes[2, 0].plot(daily_stats.index, daily_stats['t2m'], 
             marker='o', markersize=4, linewidth=2, color='#FF6347', label='Temperature')
    axes[2, 0].set_title('Environmental Temperature', fontsize=12, fontweight='bold')
    axes[2, 0].set_xlabel('Day')
    axes[2, 0].set_ylabel('Temperature (°C)')
    axes[2, 0].grid(True, alpha=0.3)

if 'Age' in mosquito_df.columns:
    age_stats = mosquito_df.groupby('Day')['Age'].agg(['mean', 'std']).fillna(0)
    axes[2, 1].plot(age_stats.index, age_stats['mean'], 
             marker='o', markersize=4, linewidth=2, color='#6495ED', label='Mean Age')
    axes[2, 1].fill_between(age_stats.index, 
                     age_stats['mean'] - age_stats['std'], 
                     age_stats['mean'] + age_stats['std'], 
                     alpha=0.2, color='#6495ED')
    axes[2, 1].set_title('Mosquito Age Progression', fontsize=12, fontweight='bold')
    axes[2, 1].set_xlabel('Day')
    axes[2, 1].set_ylabel('Age (ticks)')
    axes[2, 1].grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig('output/temporal_dynamics.png', dpi=300, bbox_inches='tight')
plt.close()

unique_days = sorted(mosquito_df['Day'].unique())
print(f"\nSPATIAL ANALYSIS:")
print(f"Unique days found: {len(unique_days)}")
print(f"Days: {unique_days}")

if len(unique_days) > 0:
    n_plots = min(4, len(unique_days))
    n_cols = 2
    n_rows = (n_plots + n_cols - 1) // n_cols
    
    fig, axes = plt.subplots(n_rows, n_cols, figsize=(5*n_cols, 5*n_rows))
    if n_plots == 1:
        axes = np.array([axes])
    axes = axes.flatten()
    
    for idx, day in enumerate(unique_days[:n_plots]):
        ax = axes[idx]
        day_data = mosquito_df[np.isclose(mosquito_df['Day'], day, atol=0.01)]
        
        if len(day_data) > 0 and 'Stage' in day_data.columns:
            for stage, color in STAGE_COLORS.items():
                stage_data = day_data[day_data['Stage'] == stage]
                if not stage_data.empty:
                    ax.scatter(stage_data['X'], stage_data['Y'], 
                              c=color, s=30, alpha=0.7, label=stage, edgecolors='black', linewidth=0.5)
            
            ax.set_title(f'Day {day:.2f} - {len(day_data)} mosquitoes', fontsize=12, fontweight='bold')
            ax.set_xlabel('Longitude')
            ax.set_ylabel('Latitude')
            ax.grid(True, alpha=0.3)
            if idx == 0:
                ax.legend()
        else:
            ax.text(0.5, 0.5, f'No data for Day {day:.2f}', 
                   ha='center', va='center', transform=ax.transAxes, fontsize=12)
            ax.set_title(f'Day {day:.2f}: No Data', fontsize=12, fontweight='bold')
    
    for idx in range(n_plots, len(axes)):
        axes[idx].set_visible(False)
    
    plt.suptitle('Spatial Distribution of Mosquitoes', fontsize=14, fontweight='bold')
    plt.tight_layout()
    plt.savefig('output/spatial_evolution.png', dpi=300, bbox_inches='tight')
    plt.close()

mosquito_df['HourOfDay'] = (mosquito_df['TickCount'] % TICKS_PER_DAY) * MINUTES_PER_TICK / 60

fig, axes = plt.subplots(2, 2, figsize=(14, 10))

hourly_stats = mosquito_df.groupby('HourOfDay').size()
axes[0, 0].plot(hourly_stats.index, hourly_stats.values, marker='o', linewidth=2, color='#2E8B57')
axes[0, 0].set_title('Mosquito Activity by Hour', fontsize=12, fontweight='bold')
axes[0, 0].set_xlabel('Hour of Day')
axes[0, 0].set_ylabel('Mosquito Count')
axes[0, 0].set_xticks(range(0, 25, 4))
axes[0, 0].grid(True, alpha=0.3)

if 'Energy' in mosquito_df.columns:
    energy_hourly = mosquito_df.groupby('HourOfDay')['Energy'].mean()
    axes[0, 1].plot(energy_hourly.index, energy_hourly.values, marker='^', linewidth=2, color='#FFD700')
    axes[0, 1].set_title('Average Energy by Hour', fontsize=12, fontweight='bold')
    axes[0, 1].set_xlabel('Hour of Day')
    axes[0, 1].set_ylabel('Average Energy')
    axes[0, 1].set_xticks(range(0, 25, 4))
    axes[0, 1].grid(True, alpha=0.3)

if 'Age' in mosquito_df.columns:
    age_hourly = mosquito_df.groupby('HourOfDay')['Age'].mean()
    axes[1, 0].plot(age_hourly.index, age_hourly.values, marker='s', linewidth=2, color='#6495ED')
    axes[1, 0].set_title('Average Age by Hour', fontsize=12, fontweight='bold')
    axes[1, 0].set_xlabel('Hour of Day')
    axes[1, 0].set_ylabel('Average Age (ticks)')
    axes[1, 0].set_xticks(range(0, 25, 4))
    axes[1, 0].grid(True, alpha=0.3)

if 'Stage' in mosquito_df.columns:
    hourly_stages = mosquito_df.groupby(['HourOfDay', 'Stage']).size().unstack(fill_value=0)
    for stage in hourly_stages.columns:
        if stage in STAGE_COLORS:
            axes[1, 1].plot(hourly_stages.index, hourly_stages[stage], 
                           label=stage, color=STAGE_COLORS[stage], linewidth=2)
    axes[1, 1].set_title('Stage Activity by Hour', fontsize=12, fontweight='bold')
    axes[1, 1].set_xlabel('Hour of Day')
    axes[1, 1].set_ylabel('Count')
    axes[1, 1].legend()
    axes[1, 1].grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig('output/diurnal_patterns.png', dpi=300, bbox_inches='tight')
plt.close()

daily_stats['GrowthRate'] = daily_stats['TotalCount'].pct_change() * 100
daily_stats['ReproductionRate'] = daily_stats['Gravid'] / daily_stats['TotalCount'].replace(0, np.nan) * 100

fig, axes = plt.subplots(1, 2, figsize=(14, 6))

if len(daily_stats) > 1:
    colors = ['#32CD32' if x > 0 else '#DC143C' for x in daily_stats['GrowthRate'].iloc[1:]]
    axes[0].bar(daily_stats.index[1:], daily_stats['GrowthRate'].iloc[1:], color=colors)
    axes[0].axhline(y=0, color='black', linestyle='-', linewidth=0.5)
    axes[0].set_title('Daily Population Growth Rate', fontsize=12, fontweight='bold')
    axes[0].set_xlabel('Day')
    axes[0].set_ylabel('Growth Rate (%)')
    axes[0].grid(True, alpha=0.3, axis='y')

axes[1].plot(daily_stats.index, daily_stats['ReproductionRate'], 
         marker='o', color='#FF1493', linewidth=2)
axes[1].set_title('Gravid Female Percentage', fontsize=12, fontweight='bold')
axes[1].set_xlabel('Day')
axes[1].set_ylabel('Gravid Females (%)')
axes[1].grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig('output/growth_reproduction.png', dpi=300, bbox_inches='tight')
plt.close()

env_corr_cols = ['Energy', 'Age', 'Elevation', 'Buildings', 'Population', 't2m']
env_corr_cols = [col for col in env_corr_cols if col in mosquito_df.columns]

if len(env_corr_cols) > 1:
    fig, axes = plt.subplots(1, 2, figsize=(14, 6))
    
    correlation_matrix = mosquito_df[env_corr_cols].corr()
    im1 = axes[0].imshow(correlation_matrix, cmap='coolwarm', aspect='auto', 
                         vmin=-1, vmax=1, interpolation='nearest')
    axes[0].set_title('Environmental Correlation Heatmap', fontsize=12, fontweight='bold')
    axes[0].set_xticks(range(len(env_corr_cols)))
    axes[0].set_yticks(range(len(env_corr_cols)))
    axes[0].set_xticklabels(env_corr_cols, rotation=45, ha='right')
    axes[0].set_yticklabels(env_corr_cols)
    plt.colorbar(im1, ax=axes[0])
    
    if 't2m' in mosquito_df.columns and 'Energy' in mosquito_df.columns:
        scatter = axes[1].scatter(mosquito_df['t2m'], mosquito_df['Energy'], 
                                 c=mosquito_df['Age'] if 'Age' in mosquito_df.columns else None, 
                                 cmap='viridis', alpha=0.6, s=10)
        axes[1].set_title('Temperature vs Energy', fontsize=12, fontweight='bold')
        axes[1].set_xlabel('Temperature (°C)')
        axes[1].set_ylabel('Energy')
        if 'Age' in mosquito_df.columns:
            plt.colorbar(scatter, ax=axes[1], label='Age')
        axes[1].grid(True, alpha=0.3)
    
    plt.tight_layout()
    plt.savefig('output/environmental_correlations.png', dpi=300, bbox_inches='tight')
    plt.close()

stats_summary = {
    'Simulation Duration (days)': mosquito_df['Day'].max(),
    'Total Records': len(mosquito_df),
    'Average Daily Population': daily_stats['TotalCount'].mean(),
    'Peak Population': daily_stats['TotalCount'].max(),
    'Lowest Population': daily_stats['TotalCount'].min(),
    'Average Mortality Rate (%)': daily_stats['MortalityRate'].mean(),
    'Peak Mortality Rate (%)': daily_stats['MortalityRate'].max(),
    'Average Gravid Percentage (%)': daily_stats['ReproductionRate'].mean(),
    'Average Energy': mosquito_df['Energy'].mean() if 'Energy' in mosquito_df.columns else 0,
    'Average Age (ticks)': mosquito_df['Age'].mean() if 'Age' in mosquito_df.columns else 0,
    'Average Temperature (°C)': mosquito_df['t2m'].mean() if 't2m' in mosquito_df.columns else 0
}

summary_df = pd.DataFrame(list(stats_summary.items()), columns=['Metric', 'Value'])
summary_df.to_csv('output/simulation_summary.csv', index=False)

print(f"\n{'='*60}")
print(f"ANALYSIS COMPLETE")
print(f"{'='*60}")
print(f"\nKey Findings:")
print(f"Simulation: {stats_summary['Simulation Duration (days)']:.1f} days")
print(f"Total mosquitoes: {stats_summary['Total Records']:,}")
print(f"Average daily population: {stats_summary['Average Daily Population']:.0f}")
print(f"Average mortality rate: {stats_summary['Average Mortality Rate (%)']:.1f}%")
print(f"Average gravid percentage: {stats_summary['Average Gravid Percentage (%)']:.1f}%")

if len(daily_stats) > 1:
    final_pop = daily_stats['TotalCount'].iloc[-1]
    initial_pop = daily_stats['TotalCount'].iloc[0]
    if initial_pop > 0:
        total_growth = ((final_pop - initial_pop) / initial_pop) * 100
        print(f"Total growth: {total_growth:+.1f}%")

print(f"\nOutputs saved to 'output/' folder")