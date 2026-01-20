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
import java.awt.image.Raster;
import java.util.concurrent.locks.ReentrantLock;

public class RasterLayer extends Layer {

  @JsonIgnore
  private GridCoverage2DReader readerReference;
  @JsonIgnore  
  private GridCoverage2D cachedCoverage;
  @JsonIgnore
  private Raster cachedRaster;
  @JsonIgnore
  private final ReentrantLock cacheLock = new ReentrantLock();

  public RasterLayer() {}
  
  public RasterLayer(String name, String relativePath) { 
    super(name, relativePath); 
  }

  public Double getValueAt(double x, double y, File projectFile) {
    try {
      GridCoverage2D coverage = getGridCoverage(projectFile);
      if (coverage == null) return null;
      
      // 1. Convert World Coordinates to Grid Coordinates (Pixels)
      Point2D worldPt = new Point2D.Double(x, y);
      Point2D gridPt = coverage.getGridGeometry().getCRSToGrid2D().transform(worldPt, null);
      int col = (int) gridPt.getX();
      int row = (int) gridPt.getY();
      
      // Get cached raster (thread-safe)
      Raster raster = getCachedRaster(coverage);
      if (raster == null) return null;
      
      // 2. Bounds check against image dimensions
      if (col < 0 || row < 0 || 
        col >= raster.getWidth() || 
        row >= raster.getHeight()) {
        return null;
      }
      
      // 3. Get the pixel value from Band 0
      return (double) raster.getSample(col, row, 0);
    } catch (Exception e) {
      return null;
    }
  }

  private Raster getCachedRaster(GridCoverage2D coverage) {
    cacheLock.lock();
    try {
      if (cachedRaster == null && coverage != null) {
        cachedRaster = coverage.getRenderedImage().getData();
      }
      return cachedRaster;
    } finally {
      cacheLock.unlock();
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

  @JsonIgnore
  public GridCoverage2D getGridCoverage(File projectFile) throws IOException {
    cacheLock.lock();
    try {
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
        this.cachedCoverage = (GridCoverage2D) readerReference.read(null);
      }
      return cachedCoverage;
    } finally {
      cacheLock.unlock();
    }
  }

  public void clearCache() {
    cacheLock.lock();
    try {
      cachedRaster = null;
    } finally {
      cacheLock.unlock();
    }
  }

  public void dispose() {
    cacheLock.lock();
    try {
      if (readerReference != null) {
        try {
          readerReference.dispose();
        } catch (IOException ignored) {}
        readerReference = null;
      }
      cachedCoverage = null;
      cachedRaster = null;
    } finally {
      cacheLock.unlock();
    }
  }
  
  // Helper methods for performance
  @JsonIgnore
  public double[] getValueRange(File projectFile) throws IOException {
    GridCoverage2D coverage = getGridCoverage(projectFile);
    if (coverage == null) return new double[]{0.0, 0.0};
    
    Raster raster = getCachedRaster(coverage);
    if (raster == null) return new double[]{0.0, 0.0};
    
    double min = Double.MAX_VALUE;
    double max = Double.MIN_VALUE;
    
    int width = raster.getWidth();
    int height = raster.getHeight();
    
    // Sample the raster to find min/max (don't check every pixel for performance)
    int sampleStep = Math.max(1, Math.min(width, height) / 100);
    
    for (int x = 0; x < width; x += sampleStep) {
      for (int y = 0; y < height; y += sampleStep) {
        double value = raster.getSampleDouble(x, y, 0);
        if (value < min) min = value;
        if (value > max) max = value;
      }
    }
    
    return new double[]{min, max};
  }
  
  @JsonIgnore
  public int getWidth(File projectFile) throws IOException {
    GridCoverage2D coverage = getGridCoverage(projectFile);
    if (coverage == null) return 0;
    return coverage.getRenderedImage().getWidth();
  }
  
  @JsonIgnore
  public int getHeight(File projectFile) throws IOException {
    GridCoverage2D coverage = getGridCoverage(projectFile);
    if (coverage == null) return 0;
    return coverage.getRenderedImage().getHeight();
  }
  
  @JsonIgnore
  public boolean isPointInBounds(double x, double y, File projectFile) {
    try {
      GridCoverage2D coverage = getGridCoverage(projectFile);
      if (coverage == null) return false;
      
      Point2D worldPt = new Point2D.Double(x, y);
      Point2D gridPt = coverage.getGridGeometry().getCRSToGrid2D().transform(worldPt, null);
      int col = (int) gridPt.getX();
      int row = (int) gridPt.getY();
      
      Raster raster = getCachedRaster(coverage);
      if (raster == null) return false;
      
      return col >= 0 && row >= 0 && 
             col < raster.getWidth() && 
             row < raster.getHeight();
    } catch (Exception e) {
      return false;
    }
  }
}
