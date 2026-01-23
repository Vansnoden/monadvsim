import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import os

def run_visualization(csv_path="output/synthetic_surveillance_results.csv"):
    # 1. Load Data & Setup
    if not os.path.exists(csv_path):
        print(f"Error: {csv_path} not found. Running from local directory instead...")
        csv_path = "synthetic_surveillance_results.csv"
        
    df = pd.read_csv(csv_path)
    os.makedirs('output', exist_ok=True)
    
    # Filter agents
    adults = df[df['Type'].str.contains('Adult', case=False)]
    tanks = df[df['Type'].str.contains('Tank', case=False)]

    # --- PLOT 1: SPATIAL DISTRIBUTION ---
    plt.figure(figsize=(12, 8))
    # Plot Water Tanks as context
    plt.scatter(tanks['X'], tanks['Y'], c='gray', alpha=0.2, s=2, label='Potential Habitats')
    # Plot Adults colored by Temperature to show environmental sensing
    sc = plt.scatter(adults['X'], adults['Y'], c=adults['TempC'], 
                    cmap='YlOrRd', alpha=0.6, s=8, label='An. stephensi')
    
    plt.colorbar(sc, label='Temperature at Mosquito Location (°C)')
    plt.title("Addis Ababa MVS: Spatial Distribution & Climate Sensing", fontsize=15)
    plt.xlabel("Longitude")
    plt.ylabel("Latitude")
    plt.legend(loc='upper right')
    plt.grid(True, linestyle='--', alpha=0.4)
    plt.savefig('output/01_spatial_distribution.png', dpi=300)
    print("Saved: output/01_spatial_distribution.png")

    # --- PLOT 2: POPULATION DENSITY CORRELATION ---
    plt.figure(figsize=(10, 6))
    sns.kdeplot(data=adults, x='PopDensity', fill=True, color='red', bw_adjust=0.5)
    plt.title("Mosquito Abundance vs. Human Population Density", fontsize=13)
    plt.xlabel("Normalized Human Population Density")
    plt.ylabel("Mosquito Density Frequency")
    plt.savefig('output/02_pop_correlation.png', dpi=300)
    print("Saved: output/02_pop_correlation.png")

    # --- PLOT 3: AGENT COMPOSITION ---
    plt.figure(figsize=(8, 6))
    df['Type'].value_counts().plot(kind='bar', color=['skyblue', 'salmon'])
    plt.title("Final Agent Count Comparison", fontsize=13)
    plt.ylabel("Number of Agents")
    plt.xticks(rotation=0)
    plt.tight_layout()
    plt.savefig('output/03_agent_counts.png', dpi=300)
    print("Saved: output/03_agent_counts.png")

if __name__ == "__main__":
    run_visualization()