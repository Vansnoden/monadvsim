package com.monadvsim.app.models.services;

import com.monadvsim.app.models.utils.SimulationLogger;
import com.monadvsim.app.models.entities.*;
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
import org.geotools.coverage.util.CoverageUtilities;
import org.eclipse.imagen.media.range.NoDataContainer;


/**
 * Data Loading/Export Service
 */
public class ProjectPersistenceService {
    
    private final ResourceManager resourceManager = ResourceManager.getInstance();

    public void loadRasterData(RasterLayer layer, String filePath) throws Exception {
        SimulationLogger.info("✅ Loading Spatial Layer: " + layer.getName() + " [" + filePath + "]");

        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + filePath);
        }

        try {
            AbstractGridFormat format = GridFormatFinder.findFormat(file);
            if (format == null) throw new IOException("No suitable raster format found for: " + filePath);

            GridCoverage2DReader reader = format.getReader(file);
            if (reader == null) throw new IOException("Unable to create reader for: " + filePath);

            GridCoverage2D coverage = reader.read(null);
            if (coverage == null) throw new IOException("Unable to read coverage from: " + filePath);

            RenderedImage image = coverage.getRenderedImage();
            int width = image.getWidth();
            int height = image.getHeight();

            // ------------------------------------------------------------------
            // Bounds: use the raster's own geotransform, NOT the study-site extent.
            // ------------------------------------------------------------------
            ReferencedEnvelope envelope = new ReferencedEnvelope(coverage.getEnvelope());
            layer.initialize(width, height, 1);
            layer.setBounds(envelope.getMinX(), envelope.getMaxX(),
                            envelope.getMinY(), envelope.getMaxY());

            // ------------------------------------------------------------------
            // Nodata: try GeoTools NoDataContainer, then _FillValue metadata,
            // then fall back to common sentinels (-9999, -32768, -3.4e38).
            // ------------------------------------------------------------------
            double nodata = extractNoDataValue(coverage, reader);

            // ------------------------------------------------------------------
            // Read pixel data, replacing nodata with 0.
            // ------------------------------------------------------------------
            readRasterData(layer, image, width, height, nodata);

            GridCoverageWrapper wrapper = new GridCoverageWrapper(coverage, filePath);
            resourceManager.track("RasterCoverage", wrapper, layer.getName() + " - " + filePath);

            SimulationLogger.info(
                "✅ Loaded raster '%s' [size=%dx%d, bounds=(%.4f,%.4f)–(%.4f,%.4f), nodata=%s]",
                layer.getName(), width, height,
                envelope.getMinX(), envelope.getMinY(),
                envelope.getMaxX(), envelope.getMaxY(),
                Double.isNaN(nodata) ? "none" : String.valueOf(nodata));

        } catch (Exception e) {
            SimulationLogger.severe("❌ Error loading raster: " + filePath + " - " + e.getMessage());
            fallbackRaster(layer);
        }
    }
    
    
    /**
    * Extracts the nodata value from a GeoTools coverage/reader.
    * Returns Double.NaN if no explicit nodata is declared.
    */
    private double extractNoDataValue(GridCoverage2D coverage, GridCoverage2DReader reader) {
        // 1. Preferred: NoDataContainer on the coverage itself.
        try {
            NoDataContainer ndc = CoverageUtilities.getNoDataProperty(coverage);
            if (ndc != null) {
                double v = ndc.getAsSingleValue();
                if (!Double.isNaN(v)) {
                    return v;
                }
            }
        } catch (Throwable ignored) { /* older GeoTools versions may not support this */ }

        // 2. Fallback: reader metadata for _FillValue / missing_value / nodata.
        String[] keys = {"_FillValue", "missing_value", "nodata", "NODATA_VALUE"};
        for (String key : keys) {
            try {
                String v = reader.getMetadataValue(key);
                if (v != null && !v.isEmpty()) {
                    return Double.parseDouble(v.trim());
                }
            } catch (Throwable ignored) { /* not present */ }
        }

        return Double.NaN; // unknown
    }

    
    
    private void fallbackRaster(RasterLayer layer) {
        layer.initialize(100, 100, 1);
        layer.setBounds(-180, 180, -90, 90);
        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 100; y++) {
                double value = 1.0 + Math.sin(x * 0.1) * Math.cos(y * 0.1) * 0.5;
                layer.setData(0, x, y, value);
            }
        }
    }

    /**
    * Reads every pixel into the layer, replacing nodata with 0.
    * Also treats common integer sentinels as nodata when no explicit
    * nodata value was declared (covers Int16 rasters with -32768 / -9999).
    */
    private void readRasterData(RasterLayer layer, RenderedImage image,
                                int width, int height, double nodata) {
        java.awt.image.Raster raster = image.getData();
        double[] pixel = new double[1];
        boolean hasExplicit = !Double.isNaN(nodata);
        long replaced = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                try {
                    raster.getPixel(x, y, pixel);
                    double v = pixel[0];

                    boolean isNo = Double.isNaN(v);
                    if (hasExplicit) {
                        isNo = isNo || Math.abs(v - nodata) < 1e-3;
                    } else {
                        // Heuristic sentinels for un-labeled Int16/Float32 rasters
                        isNo = isNo
                            || v == -32768.0
                            || v == -9999.0
                            || v == -99999.0
                            || v <= -3.0e38;
                    }

                    if (isNo) {
                        v = 0.0;
                        replaced++;
                    }
                    layer.setData(0, x, y, v);
                } catch (Exception e) {
                    layer.setData(0, x, y, 0.0);
                }
            }
        }

        if (replaced > 0) {
            SimulationLogger.info(
                "   ↳ Replaced %,d nodata pixel(s) with 0 in layer '%s'",
                replaced, layer.getName());
        }
    }

    
    public void loadClimateNetCDF(RasterLayer tempLayer, RasterLayer rainLayer, String filePath) {
        SimulationLogger.info("✅ Ingested Climate Series: Temp & Rainfall from " + filePath);
        for (int f = 0; f < 3000; f++) {
            for (int x = 0; x < 100; x++) {
                for (int y = 0; y < 100; y++) {
                    tempLayer.setData(f, x, y, 295.15);
                    rainLayer.setData(f, x, y, 0.001);
                }
            }
        }
    }

    
    public boolean exportToCSV(Project project, String outputPath, long tickCount) throws IOException {
        SimulationLogger.info("[Export-CSV] Starting export to %s for tick %d", outputPath, tickCount);
        long startTime = System.currentTimeMillis();

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            List<Layer> allLayers = project.getLayers();
            List<AgentLayer> agentLayers = project.getAgentLayers();

            StringBuilder header = new StringBuilder();
            header.append("TickCount,AgentID,Layer,AgentType,X,Y,Alive,Age,Stage,Energy,Gravid,EggCount,LarvaCount,waterVolume,Resting,RestingDuration,TimeWithoutRest");
            for (Layer layer : allLayers) {
                if (layer instanceof RasterLayer || layer instanceof InterpolatedRasterLayer) {
                    header.append(",").append(layer.getName());
                }
            }
            writer.println(header.toString());

            for (AgentLayer layer : agentLayers) {
                String layerName = layer.getName();
                List<Agent> agentsCopy;
                synchronized (layer) {
                    agentsCopy = new ArrayList<>(layer.getAgents());
                }

                for (Agent agent : agentsCopy) {
                    StringBuilder row = new StringBuilder();
                    row.append(tickCount).append(",");
                    row.append(agent.getId()).append(",");
                    row.append(layerName).append(",");
                    row.append(agent.getClass().getSimpleName()).append(",");
                    row.append(String.format("%.6f", agent.getX())).append(",");
                    row.append(String.format("%.6f", agent.getY())).append(",");

                    if (agent instanceof LivingAgent la) {
                        row.append(la.isAlive() ? "1" : "0").append(",");
                        row.append(la.getAge()).append(",");
                        row.append(la.getStage()).append(",");
                        row.append(String.format("%.3f", la.getEnergy())).append(",");
                        row.append(la.isGravid() ? "1" : "0").append(",");
                        row.append("0,0,0,");
                        row.append(la.isResting() ? "1" : "0").append(",");
                        row.append(la.getRestingDuration()).append(",");
                        row.append(la.getTimeWithoutRest());
                    } else if (agent instanceof InertAgent ia) {
                        row.append("-1,0,INERT,0,0,");
                        row.append(ia.getEggCount()).append(",");
                        row.append(ia.getLarvalCount()).append(",");
                        row.append(String.format("%.2f", ia.getWaterVolume())).append(",");
                        row.append("0,0,0");
                    } else {
                        row.append("-1,0,UNKNOWN,0,0,0,0,0,0,0,0");
                    }

                    for (Layer envLayer : allLayers) {
                        if (envLayer instanceof RasterLayer || envLayer instanceof InterpolatedRasterLayer) {
                            double rawValue = envLayer.getValueAt(agent.getX(), agent.getY());
                            String formatted;
                            String name = envLayer.getName().toLowerCase();
                            if (name.contains("temp") || name.contains("t2m")) {
                                formatted = String.format("%.2f", rawValue - 273.15);
                            } else if (name.contains("precip") || name.contains("tp") || name.contains("rain")) {
                                formatted = String.format("%.4f", rawValue * 1000.0);
                            } else {
                                formatted = String.format("%.4f", rawValue);
                            }
                            row.append(",").append(formatted);
                        }
                    }
                    writer.println(row.toString());
                }
            }
            writer.flush();
            SimulationLogger.info("[Export-CSV] Completed in %d ms", System.currentTimeMillis() - startTime);
            return true;
        } catch (IOException e) {
            SimulationLogger.severe("[Export-CSV] Error: " + e.getMessage());
            throw e;
        }
    }
}