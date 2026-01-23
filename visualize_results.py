import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns

def plot_simulation(csv_path):
    # 1. Load Data
    df = pd.read_csv(csv_path)
    
    if df.empty:
        print("No data to plot.")
        return

    # 2. Create Plot
    plt.figure(figsize=(12, 8))
    
    # Plot Water Tanks (InertAgents)
    tanks = df[df['Type'] == 'WaterTank']
    plt.scatter(tanks['X'], tanks['Y'], c='blue', alpha=0.5, s=10, label='Water Tanks')
    
    # Plot Adult Mosquitoes (LivingAgents)
    adults = df[df['Type'] == 'Adult']
    if not adults.empty:
        plt.scatter(adults['X'], adults['Y'], c='red', alpha=0.7, s=15, label='An. stephensi')

    # 3. Aesthetics
    plt.title("Spatial Distribution: Addis Ababa MVS Results", fontsize=15)
    plt.xlabel("Longitude")
    plt.ylabel("Latitude")
    plt.legend()
    plt.grid(True, linestyle='--', alpha=0.6)
    
    # Save Figure
    output_fig = "simulation_map.png"
    plt.savefig(output_fig)
    print(f"✅ Map saved to {output_fig}")

if __name__ == "__main__":
    plot_simulation("output/synthetic_surveillance_results.csv")
