package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;

import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.time.Duration;
import java.io.File;
import java.util.Random;

public class App {
    public static void main(String[] args) {
        System.out.println("=== MonAdvSim: Headless Mode Activated ===");
        System.out.println("Status: Ingesting Real-World Data from /prepared_data...");

        // 1. Define Temporal Bounds (Synced with your NetCDF data)
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 2, 1, 0, 0); 
        TimeManager timeManager = new TimeManager(
            start, end, 
            Duration.ofMinutes(15), // Logic step
            Duration.ofHours(1)     // Environmental step
        );

        // 2. Initialize Project
        Project project = new Project("Addis_Ababa_Synthetic_Surveillance", "EPSG:4326");
        ProjectPersistenceService persistence = new ProjectPersistenceService();

        try {
            // 3. Ingest Real Data Layers
            // We define the grid size based on your clipped data (approx 100x100 for 5km buffer)
            RasterLayer popLayer = new RasterLayer("Population", 100, 100, 1);
            RasterLayer tempLayer = new RasterLayer("Temperature", 100, 100, 744);

            // Bounds must match the BUFFER (0.05) used in your Python script
            double minLon = 38.7078, maxLon = 38.8078;
            double minLat = 8.9306, maxLat = 9.0306;
            
            popLayer.setBounds(minLon, maxLon, minLat, maxLat);
            tempLayer.setBounds(minLon, maxLon, minLat, maxLat);

            persistence.loadPopulationToLayer(popLayer, "prepared_data/pop_addis.tif");
            persistence.loadClimateToLayer(tempLayer, "prepared_data/climate_2026_1.nc");

            project.addLayer(popLayer);
            project.addLayer(tempLayer);

            // 4. Seed Agents (Stochastic seeding within the populated ROI)
            AgentLayer mosquitoLayer = new AgentLayer("Anopheles_Stephensi");
            Random rand = new Random();
            int initialPopulation = 50000; // Increased for PhD-level density testing

            for (int i = 0; i < initialPopulation; i++) {
                double rx = minLon + (maxLon - minLon) * rand.nextDouble();
                double ry = minLat + (maxLat - minLat) * rand.nextDouble();
                
                // Only spawn if population density > 0 (Biased Seeding)
                if (popLayer.getValueAt(rx, ry) > 0.1) {
                    mosquitoLayer.addAgent(new LivingAgent(rx, ry));
                }
            }
            project.addLayer(mosquitoLayer);

            // 5. Setup Spatial Registry
            Rectangle2D worldBounds = new Rectangle2D.Double(minLon, minLat, 0.1, 0.1);
            SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds);

            // 6. Initialize & Start Engine
            SimulationEngine engine = new SimulationEngine(project, timeManager, spatialRegistry);
            
            // Output directory setup
            new File("output").mkdirs();

            Thread simThread = new Thread(engine);
            simThread.start();

            // 7. Wait and Export
            simThread.join(); 
            persistence.exportToCSV(project, "output/synthetic_surveillance_results.csv");
            
            System.out.println("=== Simulation Complete ===");
            System.out.println("Final Agent Count: " + mosquitoLayer.getAgents().size());
            System.out.println("Data stored in: output/synthetic_surveillance_results.csv");

        } catch (Exception e) {
            System.err.println("Fatal Error during Simulation: " + e.getMessage());
            e.printStackTrace();
        }
    }
}