package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;

import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Random;

public class App {
    public static void main(String[] args) {
        System.out.println("=== MonAdvSim: Headless Mode Activated ===");

        // 1. Define Temporal Bounds
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 2, 1, 0, 0); // 1 Month Sim
        TimeManager timeManager = new TimeManager(
            start, end, 
            Duration.ofMinutes(15), // Simulation Tick
            Duration.ofHours(1)     // Environmental Data Step
        );

        // 2. Initialize Project & Environment
        Project project = new Project("Addis_Ababa_Surveillance", "EPSG:4326");
        
        // MVS Hack: Create a dummy 100x100 grid if no GeoTIFF is provided
        RasterLayer popDensity = new RasterLayer("Population", 100, 100, 744); // 744 hours in a month
        popDensity.setBounds(38.7, 38.8, 8.9, 9.0); // Sample Addis coordinates
        project.addLayer(popDensity);

        // 3. Seed Agents (Synthetic Population)
        AgentLayer mosquitoLayer = new AgentLayer("Anopheles_Stephensi");
        Random rand = new Random();
        for (int i = 0; i < 10000; i++) { // Start with 10k agents for the MVS test
            double rx = 38.7 + (0.1 * rand.nextDouble());
            double ry = 8.9 + (0.1 * rand.nextDouble());
            mosquitoLayer.addAgent(new LivingAgent(rx, ry));
        }
        project.addLayer(mosquitoLayer);

        // 4. Setup Spatial Registry (QuadTree)
        // Match the bounds of your RasterLayer
        Rectangle2D worldBounds = new Rectangle2D.Double(38.7, 8.9, 0.1, 0.1);
        SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds);

        // 5. Initialize & Start Engine
        SimulationEngine engine = new SimulationEngine(project, timeManager, spatialRegistry);
        
        // Set a limit for the 48-hour test run
        engine.setTickLimit(5000); 

        // Run in a separate thread to keep the main thread free for monitoring
        Thread simThread = new Thread(engine);
        simThread.start();

        // 6. Monitor & Export Results
        try {
            simThread.join(); // Wait for simulation to finish
            
            ProjectPersistenceService persistence = new ProjectPersistenceService();
            persistence.exportToCSV(project, "output/synthetic_surveillance_results.csv");
            System.out.println("Success: Results exported to CSV.");
            
        } catch (Exception e) {
            System.err.println("Simulation interrupted: " + e.getMessage());
        }
    }
}