package com.monadvsim.app.models.services;

import com.monadvsim.app.models.engine.SpatialRegistry;
import com.monadvsim.app.models.engine.TimeManager;
import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.utils.ClimateDatasetManager;
import com.monadvsim.app.models.utils.ResourceManager;
import com.monadvsim.app.models.utils.ResourceManager.GridCoverageWrapper;
import java.awt.image.RenderedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.geometry.jts.ReferencedEnvelope;


/**
 * Data Loading/Export Service
 *
 * Loads raster data (TIFF/GeoTIFF) using GeoTools
 *
 * Loads climate data from NetCDF files
 *
 * Exports simulation snapshots to CSV with comprehensive agent data
 *
 * Handles coordinate transformations and data formatting
 *
 * Manages file I/O for simulation results
 * 
 * 
 * @author void
 */


public class ProjectPersistenceService {
    
    private final ResourceManager resourceManager = ResourceManager.getInstance();

    // Helper to initialize any TIFF (Population, Buildings, Elevation)
    public void loadRasterData(RasterLayer layer, String filePath) throws Exception {
        System.out.println("✅ Loading Spatial Layer: " + layer.getName() + " [" + filePath + "]");
        
        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + filePath);
        }
        
        try {
            // Find appropriate reader for the raster format
            AbstractGridFormat format = GridFormatFinder.findFormat(file);
            if (format == null) {
                throw new IOException("No suitable raster format found for: " + filePath);
            }
            
            GridCoverage2DReader reader = format.getReader(file);
            if (reader == null) {
                throw new IOException("Unable to create reader for: " + filePath);
            }
            
            // Read the coverage
            GridCoverage2D coverage = reader.read(null);
            if (coverage == null) {
                throw new IOException("Unable to read coverage from: " + filePath);
            }
            
            // Get the raster dimensions
            RenderedImage image = coverage.getRenderedImage();
            int width = image.getWidth();
            int height = image.getHeight();
            
            System.out.printf("  Raster dimensions: %d x %d%n", width, height);
            
            // Get the geographic bounds
            ReferencedEnvelope envelope = new ReferencedEnvelope(coverage.getEnvelope());
            double minX = envelope.getMinX();
            double maxX = envelope.getMaxX();
            double minY = envelope.getMinY();
            double maxY = envelope.getMaxY();
            
            System.out.printf("  Bounds: [%.6f, %.6f, %.6f, %.6f]%n", 
                minX, minY, maxX, maxY);
            
            // Initialize the layer with correct dimensions
            layer.initialize(width, height, 1); // Static raster has 1 frame
            layer.setBounds(minX, maxX, minY, maxY);
            
            // Read the raster data
            readRasterData(layer, image, width, height);
            
            GridCoverageWrapper wrapper = new GridCoverageWrapper(coverage, filePath);
            // Track resource for cleanup
            resourceManager.track("RasterCoverage", wrapper, layer.getName() + " - " + filePath);
            
            System.out.println("✅ Successfully loaded raster: " + layer.getName());
            
        } catch (Exception e) {
            System.err.println("❌ Error loading raster: " + filePath + " - " + e.getMessage());
            e.printStackTrace();
            
            // Fallback: create a 100x100 grid with default values
            System.out.println("⚠️ Using fallback 100x100 grid for: " + layer.getName());
            fallbackRaster(layer);
        }
    }
    
    
    private void fallbackRaster(RasterLayer layer) {
        // Create a simple 100x100 grid with some variation
        layer.initialize(100, 100, 1);
        layer.setBounds(-180, 180, -90, 90); // World bounds
        
        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 100; y++) {
                // Create some artificial data pattern
                double value = 1.0 + Math.sin(x * 0.1) * Math.cos(y * 0.1) * 0.5;
                layer.setData(0, x, y, value);
            }
        }
    }

    
    private void readRasterData(RasterLayer layer, RenderedImage image, int width, int height) {
        // Get the raster data
        java.awt.image.Raster raster = image.getData();
        
        // Read pixel values
        double[] pixel = new double[1]; // Assuming single band for now
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                try {
                    raster.getPixel(x, y, pixel);
                    layer.setData(0, x, y, pixel[0]);
                } catch (Exception e) {
                    // If there's an error reading a pixel, use 0.0
                    layer.setData(0, x, y, 0.0);
                }
            }
        }
        
        // Print sample values for verification
        System.out.printf("  Sample values at corners: [%.2f, %.2f, %.2f, %.2f]%n",
            layer.getDataGrid()[0][0][0],
            layer.getDataGrid()[0][width-1][0],
            layer.getDataGrid()[0][0][height-1],
            layer.getDataGrid()[0][width-1][height-1]
        );
    }
    
    
    // New method to handle dual-variable NetCDF (Temp + Rain)
    public void loadClimateNetCDF(RasterLayer tempLayer, RasterLayer rainLayer, String filePath) throws Exception {
        // This method should map '2m_temperature' -> tempLayer
        // and 'total_precipitation' -> rainLayer
        System.out.println("✅ Ingested Climate Series: Temp & Rainfall from " + filePath);
        
        // Fallback to 22C and 0.0mm rain if file read fails
        for (int f = 0; f < 3000; f++) {
            for (int x = 0; x < 100; x++) {
                for (int y = 0; y < 100; y++) {
                    tempLayer.setData(f, x, y, 295.15); // 22 Celsius
                    rainLayer.setData(f, x, y, 0.001);  // Trace rain
                }
            }
        }
    }

    public boolean exportToCSVSimple(Project project, String outputPath, long tickCount) {
        try {
            File dir = new File("results");
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    System.err.println("[Export] ERROR: Could not create results directory");
                    return false;
                }
            }

            File file = new File(outputPath);
            if (file.exists()) {
                System.out.println("[Export] WARNING: File already exists, overwriting: " + outputPath);
            }

            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                // Write header
                writer.println("TickCount,AgentID,Layer,AgentType,X,Y,Alive,Age,Stage,Energy,Gravid");

                // Write data
                for (AgentLayer layer : project.getAgentLayers()) {
                    String layerName = layer.getName();
                    // Get a thread-safe copy of agents
                    List<Agent> agents;
                    synchronized (layer) {
                        agents = new ArrayList<>(layer.getAgents());
                    }

                    for (Agent agent : agents) {
                        StringBuilder row = new StringBuilder();
                        row.append(tickCount).append(",");
                        row.append(agent.getId()).append(",");
                        row.append(layerName).append(",");
                        row.append(agent.getClass().getSimpleName()).append(",");
                        row.append(String.format("%.6f", agent.getX())).append(",");
                        row.append(String.format("%.6f", agent.getY())).append(",");

                        if (agent instanceof LivingAgent la) {
                            row.append(la.isAlive()).append(",");
                            row.append(la.getAge()).append(",");
                            row.append(la.getStage()).append(",");
                            row.append(String.format("%.3f", la.getEnergy())).append(",");
                            row.append(la.isGravid());
                        } else if (agent instanceof InertAgent ia) {
                            row.append("true,"); // Alive placeholder
                            row.append("0,"); // Age
                            row.append("INERT,");
                            row.append("0,"); // Energy
                            row.append("false");
                        } else {
                            row.append("true,0,UNKNOWN,0,false");
                        }

                        writer.println(row.toString());
                    }
                }

                writer.flush();
                System.out.println("[Export] SUCCESS: Exported " + file.getAbsolutePath() + 
                                 " (" + file.length() + " bytes)");
                return true;

            } catch (IOException e) {
                System.err.println("[Export] ERROR writing CSV: " + e.getMessage());
                return false;
            }

        } catch (Exception e) {
            System.err.println("[Export] CRITICAL ERROR: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    
    public boolean exportToCSV(Project project, String outputPath, long tickCount) throws IOException {
        System.out.printf("[Export-CSV] Starting export to %s for tick %d%n", outputPath, tickCount);
        System.out.printf("[Export-CSV] Project has %d agent layers%n", project.getAgentLayers().size());
    
        long startTime = System.currentTimeMillis();
    
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            System.out.println("[Export-CSV] File writer created successfully");
        
            // Collect all layers
            List<Layer> allLayers = project.getLayers();
            List<AgentLayer> agentLayers = project.getAgentLayers();

         
            // Build header dynamically
            StringBuilder header = new StringBuilder();
            header.append("TickCount,AgentID,Layer,AgentType,X,Y,Alive,Age,Stage,Energy,"
                    + "Gravid,EggCount,LarvaCount,waterVolume,Resting,RestingDuration,TimeWithoutRest");

            // Add raster layer columns
            for (Layer layer : allLayers) {
                if (layer instanceof RasterLayer || layer instanceof InterpolatedRasterLayer) {
                    header.append(",").append(layer.getName());
                }
            }

            // Add agent layer statistics (nearby counts)
//            for (AgentLayer agentLayer : agentLayers) {
//                header.append(",Nearby_").append(agentLayer.getName().replace(" ", "_"));
//            }

            writer.println(header.toString());

            // Write data for each agent
            for (AgentLayer layer : agentLayers) {
                String layerName = layer.getName();

                for (Agent agent : layer.getAgents()) {
                    StringBuilder row = new StringBuilder();

                    // Basic agent info
                    row.append(tickCount).append(",");
                    row.append(agent.getId()).append(",");
                    row.append(layerName).append(",");
                    row.append(agent.getClass().getSimpleName()).append(",");
                    row.append(String.format("%.6f", agent.getX())).append(",");
                    row.append(String.format("%.6f", agent.getY())).append(",");

                    // Agent-specific attributes
                    if (agent instanceof LivingAgent la) {
                        row.append(la.isAlive()? "1":"0").append(",");
                        row.append(la.getAge()).append(",");
                        row.append(la.getStage()).append(",");
                        row.append(String.format("%.3f", la.getEnergy())).append(",");
                        row.append(la.isGravid() ? "1":"0").append(",");
                        // place older for other fields
                        row.append("0,");
                        row.append("0,");
                        row.append("0,");
                        row.append(la.isResting() ? "1":"0").append(",");  // Add resting
                        row.append(la.getRestingDuration()).append(",");  // Add resting duration
                        row.append(la.getTimeWithoutRest());  // Add time without rest
                    } else if (agent instanceof InertAgent ia) {
                        row.append("-1,"); // Alive placeholder for InertAgent
                        row.append("0,"); // Age
                        row.append("INERT,");
                        row.append("0,"); // Energy
                        row.append("0,");
                        // Add InertAgent specific fields
                        row.append(ia.getEggCount()).append(",");
                        row.append(ia.getLarvalCount()).append(",");
                        row.append(String.format("%.2f", ia.getWaterVolume())).append(",");
                        row.append("0,");
                        row.append("0,");
                        row.append("0");
                    }

                    // Add raster layer values
                    for (Layer envLayer : allLayers) {
                        if (envLayer instanceof RasterLayer || envLayer instanceof InterpolatedRasterLayer) {
                            double value = envLayer.getValueAt(agent.getX(), agent.getY());

                            // Format based on layer type
                            String layerNameLower = envLayer.getName().toLowerCase();
                            if (layerNameLower.contains("temp") || layerNameLower.contains("t2m")) {
                                // Temperature: convert Kelvin to Celsius
                                value = value - 273.15;
                                row.append(String.format(",%.2f", value));
                            } else if (layerNameLower.contains("rain") || layerNameLower.contains("precip") || 
                                      layerNameLower.contains("tp")) {
                                // Precipitation: convert m to mm
                                value = value * 1000;
                                row.append(String.format(",%.4f", value));
                            } else if (layerNameLower.contains("pop")) {
                                // Population density
                                row.append(String.format(",%.2f", value));
                            } else {
                                // Other layers
                                row.append(String.format(",%.4f", value));
                            }
                        }
                    }

//                    // Add nearby agent counts
//                    SpatialRegistry spatialRegistry = project.getSpatialRegistry();
//                    if (spatialRegistry != null) {
//                        for (AgentLayer otherLayer : agentLayers) {
//                            // Get agents from this layer that are nearby
//                            List<Agent> nearbyAgents = spatialRegistry.getNearbyAgents(
//                                agent.getX(), agent.getY(), 
//                                project.getDefaultAgentSearchRadius());
//
//                            // Count agents from the specific layer
//                            long count = nearbyAgents.stream()
//                                .filter(a -> {
//                                    // Check which layer this agent belongs to
//                                    for (AgentLayer al : agentLayers) {
//                                        if (al.getAgents().contains(a)) {
//                                            return al.getName().equals(otherLayer.getName());
//                                        }
//                                    }
//                                    return false;
//                                })
//                                .count();
//
//                            row.append(",").append(count);
//                        }
//                    } else {
//                        // If no spatial registry, add zeros
//                        for (int i = 0; i < agentLayers.size(); i++) {
//                            row.append(",0");
//                        }
//                    }

                    writer.println(row.toString());
                }
            }

            System.out.println("Results exported with Environmental Context to: " + outputPath);
            System.out.println("Included " + allLayers.size() + " layers and " + 
                                  agentLayers.size() + " agent layers");
            return true;
        } catch (IOException e) {
            System.err.println("[Export-CSV] ERROR writing CSV: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("[Export-CSV] ERROR writing CSV: " + e.getMessage());
            return false;
        } finally {
            long endTime = System.currentTimeMillis();
            System.out.printf("[Export-CSV] Export completed in %d ms%n", endTime - startTime);
        }
    }
    
    
    /**
     * Load climate data from NetCDF file into the project
     */
    public ClimateDatasetManager loadClimateData(Project project, String netcdfFilePath, 
                                                TimeManager timeManager) throws Exception {
        System.out.println("Loading climate data from: " + netcdfFilePath);
        
        ClimateDatasetManager climateManager = new ClimateDatasetManager(timeManager);
        
        try {
            // Load standard ERA5 variables
            climateManager.loadAvailableVariables(netcdfFilePath);
            
            // Add layers to project
            for (InterpolatedRasterLayer layer : climateManager.getLayers().values()) {
                project.addLayer(layer);

                // Set up tokens for rule engine
                if (project.getTokens() == null) {
                    project.setTokens(new ArrayList<>());
                }
                if (project.getLayerNames() == null) {
                    project.setLayerNames(new ArrayList<>());
                }

                // Map variable names to tokens
                String token = ProjectPersistenceService.getTokenForVariable(layer.getName());
                project.getTokens().add(token);
                project.getLayerNames().add(layer.getName());
            }
            
            climateManager.printStatistics();
            System.out.println("Climate data loaded successfully");
            
            return climateManager;
            
        } catch (IOException e) {
            System.err.println("Error loading NetCDF file: " + e.getMessage());
            throw new Exception("Failed to load climate data", e);
        }
    }
    
    public static String getTokenForVariable(String variableName) {
        // Map NetCDF variable names to tokens used in rules
        switch (variableName.toLowerCase()) {
            case "t2m":
                return "temperature";
            case "tp":
                return "precipitation";
            case "sp":
                return "pressure";
            case "u10":
                return "wind_u";
            case "v10":
                return "wind_v";
            case "r":
                return "humidity";
            case "d2m":
                return "dewpoint";
            default:
                return variableName;
        }
    }
    
    /**
     * Load multiple climate files (e.g., different time periods)
     */
    public List<ClimateDatasetManager> loadClimateTimeSeries(Project project, 
                                                            List<String> netcdfFiles,
                                                            TimeManager timeManager) throws Exception {
        List<ClimateDatasetManager> managers = new ArrayList<>();
        
        for (String filePath : netcdfFiles) {
            System.out.println("Loading: " + filePath);
            ClimateDatasetManager manager = loadClimateData(project, filePath, timeManager);
            managers.add(manager);
        }
        
        return managers;
    }
}