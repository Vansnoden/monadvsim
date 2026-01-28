import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns

# --- Configuration ---
TICKS_PER_DAY = 96
FILE_NAME = 'results/merged_snapshots_20260128_213023.csv'
STAGE_COLORS = {'ADULT': 'forestgreen', 'LARVA': 'royalblue', 'PUPA': 'darkorange'}

# 1. Load and Prepare Data
df = pd.read_csv(FILE_NAME)
df['Day'] = df['TickCount'] / TICKS_PER_DAY
mosquito_df = df[df['Layer'] == 'Mosquitoes'].copy()

# 2. [REFINED] Spatial Evolution (4 Key Days)
target_ticks = [100, 1000, 1900, 2880]
fig, axes = plt.subplots(2, 2, figsize=(16, 14))
axes = axes.flatten()

for i, tick in enumerate(target_ticks):
    ax = axes[i]
    day = int(round(tick / TICKS_PER_DAY))
    tick_data = df[df['TickCount'] == tick]
    
    # Infrastructure & Mosquitoes
    tanks = tick_data[tick_data['Layer'] == 'WaterTanks']
    ax.scatter(tanks['X'], tanks['Y'], c='black', marker='x', s=45, label='Water Tanks', zorder=2)
    
    mos_tick = tick_data[tick_data['Layer'] == 'Mosquitoes']
    for stage, color in STAGE_COLORS.items():
        s_df = mos_tick[mos_tick['Stage'] == stage]
        if not s_df.empty:
            ax.scatter(s_df['X'], s_df['Y'], c=color, s=12, alpha=0.6, label=f'Mosquito ({stage})')
    
    ax.set_title(f"Day {day} Spatial State", fontsize=15, fontweight='bold')
    if i == 0: ax.legend(loc='upper right', frameon=True)

plt.tight_layout()
plt.savefig('output/refined_spatial_distribution.png')


# Plot Total Population
pop_trend = mosquito_df.groupby('TickCount').size().reset_index(name='MosquitoCount')
stage_trend = mosquito_df.groupby(['TickCount', 'Stage']).size().unstack(fill_value=0)
pop_trend['Days'] = (pop_trend['TickCount'] * 15) / (60 * 24)

plt.figure(figsize=(10, 6))
plt.plot(pop_trend['Days'], pop_trend['MosquitoCount'], marker='.', linestyle='-', color='teal', linewidth=2)
plt.title('Total Mosquito Population Trend', fontsize=14)
plt.xlabel('Time (Days)', fontsize=12)
plt.ylabel('Number of Mosquitoes', fontsize=12)
plt.grid(True, which='both', linestyle='--', alpha=0.7)
plt.tight_layout()
plt.savefig('mosquito_population_trend.png')

# Also save the stage-wise one just in case
stage_trend['Days'] = (stage_trend.index * 15) / (60 * 24)
plt.figure(figsize=(10, 6))
for stage in ['ADULT', 'LARVA', 'PUPA']:
    if stage in stage_trend.columns:
        plt.plot(stage_trend['Days'], stage_trend[stage], label=stage, linewidth=2)

plt.title('Mosquito Population Trend by Stage', fontsize=14)
plt.xlabel('Time (Days)', fontsize=12)
plt.ylabel('Count', fontsize=12)
plt.legend()
plt.grid(True, which='both', linestyle='--', alpha=0.7)
plt.tight_layout()
plt.savefig('output/mosquito_stages_trend.png')

# 3. [NEW] Correlation Heatmap (Environmental Determinants)
plt.figure(figsize=(10, 8))
corr_cols = ['Energy', 'Age', 'Elevation', 'Buildings', 'Population', 'tp', 't2m', 'EggCount', 'LarvaCount']
sns.heatmap(mosquito_df[corr_cols].corr(), annot=True, cmap='coolwarm', center=0, fmt='.2f')
plt.title('Figure 5: Correlation Heatmap (Environment vs. Agent Biology)')
plt.savefig('output/correlation_heatmap.png')

# 4. [NEW] Altitudinal Distribution (Highland Barrier)
plt.figure(figsize=(10, 6))
sns.boxplot(data=mosquito_df, x='Stage', y='Elevation', palette='Set2')
plt.title('Figure 6: Altitudinal Distribution per Life Stage')
plt.ylabel('Elevation (m)')
plt.savefig('output/elevation_distribution.png')

# 5. [NEW] Reproductive Effort (Birth/Death Dynamics)
plt.figure(figsize=(10, 6))
repro = mosquito_df.groupby('Day')[['EggCount', 'LarvaCount']].sum()
repro.plot(kind='line', ax=plt.gca(), linewidth=2, color=['red', 'blue'])
plt.title('Figure 7: Reproductive Effort (Egg & Larva Totals)')
plt.ylabel('Total Count in System')
plt.savefig('output/reproductive_effort.png')

# 6. [NEW] Urban Density Impact
plt.figure(figsize=(10, 6))
mosquito_df['Urban_Bin'] = pd.qcut(mosquito_df['Buildings'], q=5, duplicates='drop')
sns.countplot(data=mosquito_df, x='Urban_Bin', palette='magma')
plt.title('Figure 8: Vector Presence across Urban Density Levels')
plt.xlabel('Building Density (Quantiles)')
plt.xticks(rotation=0)
plt.savefig('output/urban_density_impact.png')

print("All advanced manuscript figures have been generated successfully.")