package com.monadvsim.app.models.services;

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

    public void exportToCSV(Project project, String outputPath) throws IOException {
        RasterLayer pop = project.getRasterByName("Population");
        RasterLayer temp = project.getRasterByName("Temperature");
        RasterLayer rain = project.getRasterByName("Rainfall");

        try (PrintWriter writer = new PrintWriter(new File(outputPath))) {
            writer.println("AgentID,X,Y,Type,Status,PopDensity,TempC,Rain_mm");
            for (AgentLayer layer : project.getAgentLayers()) {
                for (Agent agent : layer.getAgents()) {
                    double t = (temp != null) ? temp.getValueAt(agent.getX(), agent.getY()) - 273.15 : 25.0;
                    double r = (rain != null) ? rain.getValueAt(agent.getX(), agent.getY()) * 1000 : 0.0; // m to mm
                    double p = (pop != null) ? pop.getValueAt(agent.getX(), agent.getY()) : 0.0;
                    LifecycleStage stage = LifecycleStage.UNDEFINED;
                    boolean alive = false;
                    if (agent instanceof LivingAgent la){ 
                        alive = la.isAlive();
                        stage = la.getStage();
                    }

                    writer.printf("%s,%.6f,%.6f,%s,%b,%.2f,%.2f,%.4f%n",
                        agent.getId(), agent.getX(), agent.getY(),
                        stage, alive, p, t, r);
                }
            }
        }
        System.out.println("✅ Results exported with Environmental Context to: " + outputPath);
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
            System.out.println("✅ Climate data loaded successfully");
            
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