package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.util.Random;

public class App {
    public static void main(String[] args) {
        try {
            Project project = new Project("Addis_Ababa_MVS_V2");
            
            // FIX: Use LocalDateTime to match new TimeManager
            LocalDateTime simStart = LocalDateTime.of(2024, 1, 1, 0, 0);
            TimeManager timeManager = new TimeManager(simStart, 3000, 15); 
            
            ProjectPersistenceService persistence = new ProjectPersistenceService();

            double minLon = 38.70, maxLon = 38.80;
            double minLat = 8.95, maxLat = 9.05;

            // Initialize 5 Layers
            RasterLayer pop = new RasterLayer("Population", 100, 100, 1);
            RasterLayer temp = new RasterLayer("Temperature", 100, 100, 3000);
            RasterLayer rain = new RasterLayer("Rainfall", 100, 100, 3000);
            RasterLayer elev = new RasterLayer("Elevation", 100, 100, 1);
            RasterLayer build = new RasterLayer("Buildings", 100, 100, 1);

            RasterLayer[] allRasters = {pop, temp, rain, elev, build};
            for (RasterLayer r : allRasters) {
                r.setBounds(minLon, maxLon, minLat, maxLat);
                project.addLayer(r);
            }

            persistence.loadRasterData(pop, "prepared_data/pop_addis.tiff");
            persistence.loadRasterData(elev, "prepared_data/elev_addis.tiff");
            persistence.loadRasterData(build, "prepared_data/buildings_addis.tiff");
            persistence.loadClimateNetCDF(temp, rain, "prepared_data/climate_2024_01.nc");

            Rectangle2D worldBounds = new Rectangle2D.Double(minLon, minLat, 0.1, 0.1);
            SpatialRegistry spatialRegistry = new SpatialRegistry(worldBounds);

            AgentLayer mosquitoLayer = new AgentLayer("Mosquitoes");
            AgentLayer habitatLayer = new AgentLayer("Water_Tanks");
            Random rand = new Random();

            for (int i = 0; i < 2000; i++) {
                double rx = minLon + 0.1 * rand.nextDouble();
                double ry = minLat + 0.1 * rand.nextDouble();
                if (build.getValueAt(rx, ry) > 0.5) {
                    InertAgent tank = new InertAgent(rx, ry);
                    tank.addEggs(100); 
                    habitatLayer.addAgent(tank);
                }
                if (pop.getValueAt(rx, ry) > 0.5 && i < 500) {
                    mosquitoLayer.addAgent(new LivingAgent(rx, ry));
                }
            }
            
            project.addLayer(mosquitoLayer);
            project.addLayer(habitatLayer);

            SimulationEngine engine = new SimulationEngine(project, timeManager, spatialRegistry);
            engine.run(); // Executes the simulation

            persistence.exportToCSV(project, "output/synthetic_surveillance_results.csv");
            System.out.println("=== Simulation Complete ===");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}