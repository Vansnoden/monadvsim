package com.monadvsim.app.models.services;

import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.AgentLayer;
import com.monadvsim.app.models.entities.InertAgent;
import com.monadvsim.app.models.entities.LivingAgent;
import com.monadvsim.app.models.entities.Project;
import com.monadvsim.app.models.entities.RasterLayer;
import java.awt.Point;
import java.awt.geom.Rectangle2D;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.geometry.Position;
import org.geotools.api.parameter.GeneralParameterValue;
import org.geotools.api.parameter.ParameterValue;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.OverviewPolicy;
import org.geotools.coverage.io.netcdf.NetCDFReader;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;


/**
 * Service to handle data ingestion from GIS files and simulation results export.
 */
public class ProjectPersistenceService {

    /**
     * Ingests a TIFF using GeoTools and dynamically initializes the layer
     * based on the file's actual metadata.
     */
    public void loadRasterData(RasterLayer layer, String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("* Raster file not found: " + filePath);
        }

        // Use GeoTiffReader to access the file
        GeoTiffReader reader = new GeoTiffReader(file);
        GridCoverage2D coverage = reader.read(null);
        // Extract dimensions from the GridEnvelope
        GridEnvelope envelope = coverage.getGridGeometry().getGridRange();
        int width = envelope.getSpan(0);  // Returns actual width in pixels
        int height = envelope.getSpan(1); // Returns actual height in pixels
        
        // Extract Geographic Bounds (The Fix)
        GridGeometry2D geometry = coverage.getGridGeometry();
        ReferencedEnvelope bounds = geometry.getEnvelope2D();
        // Extract the coordinates
        double minLon = bounds.getMinX();
        double maxLon = bounds.getMaxX();
        double minLat = bounds.getMinY();
        double maxLat = bounds.getMaxY();

        // Dynamically initialize your model's internal array
        layer.initialize(width, height, layer.getFrames());

        System.out.println("-> GeoTools Ingested: " + layer.getName());
        System.out.println("-> Resolution: " + width + "x" + height);

        // Populate the data
        // In a full implementation, you'd iterate the RenderedImage or use evaluate()
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Get value at pixel (x,y)
                float[] pixel = (float[]) coverage.evaluate((Position) new Point(x, y));
                layer.setData(0, x, y, pixel[0]);
            }
        }
        
        reader.dispose();
    }

    /**
     * Exports results with precision required for PhD spatial analysis.
     */
    public void exportToCSV(Project project, String outputPath) throws IOException {
        RasterLayer pop = project.getRasterByName("Population");
        RasterLayer temp = project.getRasterByName("Temperature");
        RasterLayer rain = project.getRasterByName("Rainfall");

        File file = new File(outputPath);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(file)))) {
            writer.println("AgentID,X,Y,Type,Status,PopDensity,TempC,Rain_mm,Age,LarvaeCount,isGravid");

            for (AgentLayer layer : project.getAgentLayers()) {
                for (Agent agent : layer.getAgents()) {
                    // Pull environment data from the exact coordinate
                    double t = project.getTemperatureAt(agent.getX(), agent.getY());
                    double r = (rain != null) ? rain.getValueAt(agent.getX(), agent.getY()) * 1000 : 0.0;
                    double p = (pop != null) ? pop.getValueAt(agent.getX(), agent.getY()) : 0.0;

                    String type = (agent instanceof LivingAgent) ? "Adult" : "Habitat";
                    int larvae = (agent instanceof InertAgent ia) ? ia.getLarvalCount() : 0;
                    int age = (agent instanceof LivingAgent la) ? la.getAge(): 0;
                    boolean gravid = (agent instanceof LivingAgent la) && la.isGravid();
                    boolean alive = (agent instanceof LivingAgent la) && la.isAlive();

                    writer.printf(Locale.US, "%s,%.6f,%.6f,%s,%b,%.4f,%.2f,%.4f,%d,%d,%b%n",
                            agent.getId(), agent.getX(), agent.getY(),
                            type, alive, p, t, r, 
                            age, larvae, gravid);
                }
            }
        }
        System.out.println("Results successfully exported to: " + outputPath);
    }
    
    
    /**
    * Ingests temporal climate data from a NetCDF file.
    * Maps '2m_temperature' and 'total_precipitation' variables to internal RasterLayers.
    */
    public void loadClimateNetCDF(RasterLayer tempLayer, RasterLayer rainLayer, String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("-> NetCDF climate file missing: " + filePath);
        }

        // 1. Initialize the NetCDF Reader
        NetCDFReader reader = new NetCDFReader(file, null);

        // 2. Identify available variables (Names may vary based on your source like ERA5-Land)
        // Common names: "t2m" or "2m_temperature" and "tp" or "total_precipitation"
        String[] varNames = reader.getGridCoverageNames();
        System.out.println("-> NetCDF Metadata found variables: " + String.join(", ", varNames));

        // 3. Define read parameters to handle the temporal dimension
        // We want the raw data without overview or subsampling to keep PhD accuracy
        ParameterValue<OverviewPolicy> policy = AbstractGridFormat.OVERVIEW_POLICY.createValue();
        policy.setValue(OverviewPolicy.IGNORE);
        GeneralParameterValue[] params = new GeneralParameterValue[]{policy};

        // 4. Extract data for each time frame (tick)
        // We assume the NetCDF has 'n' time steps matching your simulation duration
        int maxFrames = 3000; // Adjust based on your NetCDF 'Time' dimension

        for (int frame = 0; frame < maxFrames; frame++) {
            // Read the temperature coverage for this specific time index
            // Note: NetCDFReader implementation of read(params) varies by specific file structure
            GridCoverage2D tempCoverage = reader.read("2m_temperature", params);
            GridCoverage2D rainCoverage = reader.read("total_precipitation", params);

            int width = tempLayer.getWidth();
            int height = tempLayer.getHeight();

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // Extract Pixel Value at (x,y)
                    float[] tVal = (float[]) tempCoverage.evaluate((Position) new Point(x, y));
                    float[] rVal = (float[]) rainCoverage.evaluate((Position) new Point(x, y));

                    // Store in RasterLayer (frame, x, y, value)
                    tempLayer.setData(frame, x, y, (double) tVal[0]);
                    rainLayer.setData(frame, x, y, (double) rVal[0]);
                }
            }

            // Progress log for large datasets
            if (frame % 500 == 0) {
                System.out.println("-> Processing Climate Frame: " + frame);
            }
        }

        reader.dispose();
        System.out.println("-> NetCDF Climate Data successfully mapped to simulation grid.");
    }
}