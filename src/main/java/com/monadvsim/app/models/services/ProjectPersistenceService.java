package com.monadvsim.app.models.services;

import com.monadvsim.app.models.entities.Project;
import com.monadvsim.app.models.entities.RasterLayer;
import java.io.*;
import java.util.zip.*;

public class ProjectPersistenceService {

    /**
     * Saves the Project object to a .mvsim file.
     * Since we are headless, we serialize the Project object directly.
     */
    public void saveProject(Project project, String path) throws IOException {
        File file = new File(path);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(project);
        }
        System.out.println("Project saved to: " + path);
    }

    /**
     * Loads the Project object from a .mvsim file.
     */
    public Project loadProject(String path) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path))) {
            return (Project) ois.readObject();
        }
    }

    /**
     * MVS Helper: Imports a raw GeoTIFF and converts it into a RasterLayer.
     * Note: This requires GeoTools libraries in your pom.xml.
     */
    public RasterLayer importGeoTiff(File tiffFile, String layerName) {
        // Theoretical implementation:
        // 1. Use GeoTools GridFormatFinder to get a coverage reader.
        // 2. Extract the RenderedImage.
        // 3. Loop through pixels and call rasterLayer.setData(0, x, y, value).
        
        System.out.println("Importing spatial data from: " + tiffFile.getName());
        // For 48-hour prototype, you can return a dummy layer if GeoTools isn't ready
        return new RasterLayer(layerName, 100, 100, 1);
    }

    /**
     * Export Simulation Results to CSV.
     * This is the "Synthetic Surveillance" output for your PhD.
     */
    public void exportToCSV(Project project, String outputPath) throws IOException {
        try (PrintWriter writer = new PrintWriter(new File(outputPath))) {
            writer.println("AgentID,X,Y,Type,Status");
            project.getAgentLayers().forEach(layer -> {
                layer.getAgents().forEach(agent -> {
                    writer.printf("%s,%.6f,%.6f,%s,%s%n", 
                        agent.getId(), agent.getX(), agent.getY(), 
                        agent.getLifecycleStage(), agent.isAlive());
                });
            });
        }
    }
}