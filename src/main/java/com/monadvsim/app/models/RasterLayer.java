package com.monadvsim.app.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.geotools.api.coverage.grid.GridCoverage;
import org.geotools.api.style.RasterSymbolizer;
import org.geotools.api.style.StyleFactory;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.map.GridReaderLayer;
import org.geotools.styling.SLD;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;

import java.io.File;
import java.io.IOException;
import java.awt.geom.Point2D;

/**
 * Represents a Raster (Grid) layer, such as a GeoTIFF, used for 
 * environmental thresholds and background imagery.
 */
public class RasterLayer extends Layer {

    @JsonIgnore
    private GridCoverage2DReader readerReference;
    
    @JsonIgnore  
    private GridCoverage cachedCoverage;

    public RasterLayer() {}
    public RasterLayer(String name, String relativePath) { 
        super(name, relativePath); 
    }

    /**
     * Samples a numeric value from the raster at specific world coordinates.
     */
    public Double getValueAt(double x, double y, File projectFile) {
        try {
            GridCoverage coverage = getGridCoverage(projectFile);
            if (!(coverage instanceof GridCoverage2D g2d)) return null;

            // 1. Convert World Coordinates to Grid Coordinates (Pixels)
            Point2D worldPt = new Point2D.Double(x, y);
            Point2D gridPt = g2d.getGridGeometry().getCRSToGrid2D().transform(worldPt, null);

            int col = (int) gridPt.getX();
            int row = (int) gridPt.getY();

            // 2. Bounds check against image dimensions
            if (col < 0 || row < 0 || 
                col >= g2d.getRenderedImage().getWidth() || 
                row >= g2d.getRenderedImage().getHeight()) {
                return null;
            }

            // 3. Get the pixel value from Band 0
            return g2d.getRenderedImage().getData().getSampleDouble(col, row, 0);
        } catch (Exception e) {
            return null;
        }
    }

    private File resolveFile(File projectFile) {
        if (getRelativePath() == null) return null;
        File file = new File(getRelativePath());
        if (file.isAbsolute()) return file;
        File baseDir = (projectFile != null) ? projectFile.getParentFile() : new File(".");
        return new File(baseDir, getRelativePath());
    }

    @Override 
    @JsonIgnore
    public org.geotools.map.Layer getGeoToolsLayer(File projectFile) {
        try {
            File file = resolveFile(projectFile);
            if (file == null || !file.exists()) return null;

            // Reuse the existing reader to avoid "sg2d.loops is null" rendering crashes
            if (this.readerReference == null) {
                AbstractGridFormat format = GridFormatFinder.findFormat(file);
                if (format == null) return null;
                this.readerReference = format.getReader(file);
            }

            StyleFactory sf = CommonFactoryFinder.getStyleFactory();
            FilterFactory ff = CommonFactoryFinder.getFilterFactory();

            // Create RasterSymbolizer for styling
            RasterSymbolizer symbolizer = sf.createRasterSymbolizer();
            
            // Map the layer's opacity (0.0 to 1.0) to the symbolizer
            double currentOpacity = Math.min(1.0, Math.max(0.0, this.getOpacity()));
            Expression opacityExpr = ff.literal(currentOpacity);
            symbolizer.setOpacity(opacityExpr);

            org.geotools.api.style.Style style = SLD.wrapSymbolizers(symbolizer);
            
            GridReaderLayer layer = new GridReaderLayer(readerReference, style);
            layer.setTitle(this.getName());
            return layer;

        } catch (Exception e) { 
            e.printStackTrace();
            return null; 
        }
    }

    /**
     * Returns the coverage data, caching it in memory to prevent 
     * constant disk I/O during simulation steps.
     */
    @JsonIgnore
    public GridCoverage getGridCoverage(File projectFile) throws IOException {
        if (cachedCoverage != null) return cachedCoverage;
        
        File file = resolveFile(projectFile);
        if (file == null || !file.exists()) return null;
        
        if (this.readerReference == null) {
            AbstractGridFormat format = GridFormatFinder.findFormat(file);
            if (format != null) {
                this.readerReference = format.getReader(file);
            }
        }
        
        if (readerReference != null) {
            this.cachedCoverage = readerReference.read(null);
        }
        return cachedCoverage;
    }

    /**
     * Call this when removing the layer to free file locks and memory.
     */
    public void dispose() {
        if (readerReference != null) {
            try {
                readerReference.dispose();
            } catch (IOException ignored) {}
            readerReference = null;
        }
        cachedCoverage = null;
    }
}
