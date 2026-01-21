package com.monadvsim.app.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.geotools.api.coverage.grid.GridCoverage;
import org.geotools.api.style.RasterSymbolizer;
import org.geotools.api.style.StyleFactory;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.geometry.Position2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.GridReaderLayer;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.geotools.styling.SLD;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.referencing.operation.MathTransform2D;
import org.geotools.api.referencing.operation.TransformException;
import org.locationtech.jts.geom.Coordinate;

import java.io.File;
import java.io.IOException;
import java.awt.geom.Point2D;
import java.awt.geom.AffineTransform;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.awt.image.RenderedImage;
import java.awt.image.DataBuffer;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

public class RasterLayer extends Layer {

  @JsonIgnore
  private GridCoverage2DReader readerReference;
  @JsonIgnore  
  private GridCoverage2D cachedCoverage;
  @JsonIgnore
  private volatile WritableRaster cachedRaster;
  @JsonIgnore
  private final ReentrantLock cacheLock = new ReentrantLock();
  @JsonIgnore
  private volatile boolean cacheEnabled = true;
  @JsonIgnore
  private volatile boolean isLargeRaster = false;
  @JsonIgnore
  private static final long MAX_CACHE_SIZE_BYTES = 50_000_000; // 50MB
  @JsonIgnore
  private final AtomicInteger accessCount = new AtomicInteger(0);
  @JsonIgnore
  private static final int ACCESS_THRESHOLD_FOR_CACHE = 10;
  @JsonIgnore
  private AffineTransform worldToGridTransform;

  public RasterLayer() {}
  
  public RasterLayer(String name, String relativePath) { 
    super(name, relativePath); 
  }

  public Double getValueAt(double x, double y, File projectFile) {
    // Early check for cache disabled
    if (!cacheEnabled || isLargeRaster) {
      return getValueDirect(x, y, projectFile);
    }
    
    try {
      GridCoverage2D coverage = getGridCoverage(projectFile);
      if (coverage == null) return null;
      
      // Check if we should use cache based on access frequency
      int accesses = accessCount.incrementAndGet();
      if (accesses < ACCESS_THRESHOLD_FOR_CACHE) {
        return getValueDirect(x, y, projectFile);
      }
      
      // 1. Convert World Coordinates to Grid Coordinates (Pixels)
      Point2D.Double worldPt = new Point2D.Double(x, y);
      Point2D.Double gridPt = new Point2D.Double();
      getWorldToGridTransform(coverage).transform(worldPt, gridPt);
      
      int col = (int) Math.round(gridPt.getX());
      int row = (int) Math.round(gridPt.getY());
      
      // Get cached raster
      WritableRaster raster = getCachedRaster(coverage);
      if (raster == null) {
        return getValueDirect(x, y, projectFile);
      }
      
      // 2. Bounds check
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

  // Memory-efficient direct sampling (no caching)
  private Double getValueDirect(double x, double y, File projectFile) {
    try {
      GridCoverage2D coverage = getGridCoverage(projectFile);
      if (coverage == null) return null;
      
      RenderedImage image = coverage.getRenderedImage();
      if (image == null) return null;
      
      // Convert world coordinates to grid coordinates using transform
      Point2D.Double worldPt = new Point2D.Double(x, y);
      Point2D.Double gridPt = new Point2D.Double();
      getWorldToGridTransform(coverage).transform(worldPt, gridPt);
      
      int col = (int) Math.round(gridPt.getX());
      int row = (int) Math.round(gridPt.getY());
      
      if (col < 0 || row < 0 || 
          col >= image.getWidth() || 
          row >= image.getHeight()) {
        return null;
      }
      
      // Sample directly without caching the entire raster
      Raster tile = image.getTile(image.getMinTileX(), image.getMinTileY());
      if (tile != null) {
        // Check if point is within this tile
        if (col >= tile.getMinX() && col < tile.getMinX() + tile.getWidth() &&
            row >= tile.getMinY() && row < tile.getMinY() + tile.getHeight()) {
          return (double) tile.getSample(col, row, 0);
        }
      }
      
      // Fallback to direct sampling
      return (double) image.getData().getSample(col, row, 0);
    } catch (Exception e) {
      return null;
    }
  }

  private AffineTransform getWorldToGridTransform(GridCoverage2D coverage) {
    if (worldToGridTransform == null && coverage != null) {
      try {
        // Get the transform from world to grid coordinates
        Object transform = coverage.getGridGeometry().getGridToCRS();
        if (transform instanceof AffineTransform2D) {
          AffineTransform2D gridToWorld = (AffineTransform2D) transform;
          // Get the inverse transform
          MathTransform2D inverse = gridToWorld.inverse();
          
          // Check if the inverse is also an AffineTransform2D
          if (inverse instanceof AffineTransform2D) {
            worldToGridTransform = (AffineTransform2D) inverse;
          } else {
            // Create a simple inverse transform manually
            double[] matrix = new double[6];
            gridToWorld.getMatrix(matrix);
            
            // Calculate inverse of 2D affine transform
            double a = matrix[0];
            double b = matrix[1];
            double c = matrix[2];
            double d = matrix[3];
            double e = matrix[4];
            double f = matrix[5];
            
            double det = a * d - b * c;
            if (Math.abs(det) > 1e-10) {
              double invDet = 1.0 / det;
              double invA = d * invDet;
              double invB = -b * invDet;
              double invC = -c * invDet;
              double invD = a * invDet;
              double invE = (c * f - d * e) * invDet;
              double invF = (b * e - a * f) * invDet;
              
              worldToGridTransform = new AffineTransform(invA, invB, invC, invD, invE, invF);
            } else {
              // Fallback: create a simple transform based on envelope
              worldToGridTransform = createSimpleWorldToGridTransform(coverage);
            }
          }
        } else {
          // Fallback: create a simple transform based on envelope
          worldToGridTransform = createSimpleWorldToGridTransform(coverage);
        }
      } catch (Exception e) {
        // Fallback to identity transform
        worldToGridTransform = new AffineTransform();
      }
    }
    return worldToGridTransform != null ? worldToGridTransform : new AffineTransform();
  }

  private AffineTransform createSimpleWorldToGridTransform(GridCoverage2D coverage) {
    try {
      ReferencedEnvelope env = coverage.getEnvelope2D();
      RenderedImage image = coverage.getRenderedImage();
      if (env != null && image != null) {
        double scaleX = image.getWidth() / env.getWidth();
        double scaleY = image.getHeight() / env.getHeight();
        double transX = -env.getMinX() * scaleX;
        double transY = -env.getMinY() * scaleY;
        return new AffineTransform(scaleX, 0, 0, scaleY, transX, transY);
      }
    } catch (Exception e) {
      // Ignore and fall through
    }
    return new AffineTransform();
  }

  private WritableRaster getCachedRaster(GridCoverage2D coverage) {
    if (cachedRaster != null) return cachedRaster;
    
    cacheLock.lock();
    try {
      if (cachedRaster != null) return cachedRaster;
      
      if (coverage == null) return null;
      
      RenderedImage image = coverage.getRenderedImage();
      if (image == null) return null;
      
      // Estimate memory size
      long estimatedSize = estimateRasterSize(image);
      
      if (estimatedSize > MAX_CACHE_SIZE_BYTES) {
        System.out.println("Raster too large for caching: " + getName() + 
                         " (" + image.getWidth() + "x" + image.getHeight() + 
                         ", estimated " + (estimatedSize / 1_000_000) + "MB), using direct sampling");
        isLargeRaster = true;
        cacheEnabled = false;
        return null;
      }
      
      try {
        // Create a writable copy using minimal memory
        cachedRaster = createMemoryEfficientCopy(image);
        System.out.println("Cached raster: " + getName() + " (" + 
                         image.getWidth() + "x" + image.getHeight() + ")");
        return cachedRaster;
      } catch (OutOfMemoryError e) {
        System.err.println("Out of memory caching raster: " + getName() + 
                         ", switching to direct sampling");
        isLargeRaster = true;
        cacheEnabled = false;
        System.gc();
        return null;
      }
    } finally {
      cacheLock.unlock();
    }
  }

  private long estimateRasterSize(RenderedImage image) {
    if (image == null) return 0;
    
    DataBuffer buffer = image.getData().getDataBuffer();
    if (buffer == null) return 0;
    
    int dataType = buffer.getDataType();
    int bytesPerPixel;
    
    switch (dataType) {
      case DataBuffer.TYPE_BYTE:
        bytesPerPixel = 1;
        break;
      case DataBuffer.TYPE_USHORT:
      case DataBuffer.TYPE_SHORT:
        bytesPerPixel = 2;
        break;
      case DataBuffer.TYPE_INT:
      case DataBuffer.TYPE_FLOAT:
        bytesPerPixel = 4;
        break;
      case DataBuffer.TYPE_DOUBLE:
        bytesPerPixel = 8;
        break;
      default:
        bytesPerPixel = 4; // conservative default
    }
    
    long size = (long) image.getWidth() * image.getHeight() * 
                image.getSampleModel().getNumBands() * bytesPerPixel;
    
    // Add overhead for DataBuffer and Raster objects
    return size + 1024; // 1KB overhead
  }

  private WritableRaster createMemoryEfficientCopy(RenderedImage image) {
    // Get the sample model to understand data layout
    int width = image.getWidth();
    int height = image.getHeight();
    
    // Create a new raster with the same sample model
    WritableRaster raster = Raster.createWritableRaster(
      image.getSampleModel(),
      image.getData().getDataBuffer(),
      new java.awt.Point(0, 0)
    );
    
    return raster;
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
      
      if (this.readerReference == null) {
        AbstractGridFormat format = GridFormatFinder.findFormat(file);
        if (format == null) return null;
        this.readerReference = format.getReader(file);
      }
      
      StyleFactory sf = CommonFactoryFinder.getStyleFactory();
      FilterFactory ff = CommonFactoryFinder.getFilterFactory();
      
      RasterSymbolizer symbolizer = sf.createRasterSymbolizer();
      double currentOpacity = Math.min(1.0, Math.max(0.0, this.getOpacity()));
      Expression opacityExpr = ff.literal(currentOpacity);
      symbolizer.setOpacity(opacityExpr);
      
      org.geotools.api.style.Style style = SLD.wrapSymbolizers(symbolizer);
      GridReaderLayer layer = new GridReaderLayer(readerReference, style);
      layer.setTitle(this.getName());
      return layer;
    } catch (Exception e) { 
      System.err.println("Error creating GeoTools layer for " + getName() + ": " + e.getMessage());
      return null; 
    }
  }

  @JsonIgnore
  public GridCoverage2D getGridCoverage(File projectFile) throws IOException {
    if (cachedCoverage != null) return cachedCoverage;
    
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
        try {
          this.cachedCoverage = (GridCoverage2D) readerReference.read(null);
          // Reset transform when we get new coverage
          this.worldToGridTransform = null;
        } catch (OutOfMemoryError e) {
          System.err.println("Out of memory loading raster: " + getName());
          System.gc();
          throw new IOException("Insufficient memory to load raster: " + getName(), e);
        }
      }
      return cachedCoverage;
    } finally {
      cacheLock.unlock();
    }
  }

  @JsonIgnore
  public boolean isCacheEnabled() {
    return cacheEnabled && !isLargeRaster;
  }

  public void setCacheEnabled(boolean enabled) {
    this.cacheEnabled = enabled;
    if (!enabled) {
      clearCache();
    }
  }

  public void clearCache() {
    cacheLock.lock();
    try {
      cachedRaster = null;
      worldToGridTransform = null;
      accessCount.set(0);
      // Note: we keep cachedCoverage to avoid re-reading the file
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
      worldToGridTransform = null;
      cacheEnabled = true;
      isLargeRaster = false;
      accessCount.set(0);
    } finally {
      cacheLock.unlock();
    }
  }

  // Performance monitoring methods
  @JsonIgnore
  public int getWidth(File projectFile) throws IOException {
    GridCoverage2D coverage = getGridCoverage(projectFile);
    if (coverage == null) return 0;
    RenderedImage image = coverage.getRenderedImage();
    return image != null ? image.getWidth() : 0;
  }
  
  @JsonIgnore
  public int getHeight(File projectFile) throws IOException {
    GridCoverage2D coverage = getGridCoverage(projectFile);
    if (coverage == null) return 0;
    RenderedImage image = coverage.getRenderedImage();
    return image != null ? image.getHeight() : 0;
  }
  
  @JsonIgnore
  public double[] getValueRange(File projectFile) throws IOException {
    GridCoverage2D coverage = getGridCoverage(projectFile);
    if (coverage == null) return new double[]{0.0, 0.0};
    
    RenderedImage image = coverage.getRenderedImage();
    if (image == null) return new double[]{0.0, 0.0};
    
    int width = image.getWidth();
    int height = image.getHeight();
    
    // Only sample if image is small enough
    if ((long) width * height > 1_000_000) {
      // For large images, return approximate range
      return new double[]{0.0, 255.0}; // Default range for 8-bit images
    }
    
    double min = Double.MAX_VALUE;
    double max = Double.MIN_VALUE;
    
    // Sample strategically
    int sampleStep = Math.max(1, Math.min(width, height) / 100);
    
    // Get the transform for grid to world
    AffineTransform gridToWorld = getGridToWorldTransform(coverage);
    
    for (int x = 0; x < width; x += sampleStep) {
      for (int y = 0; y < height; y += sampleStep) {
        // Convert grid coordinates to world coordinates
        Point2D.Double gridPt = new Point2D.Double(x, y);
        Point2D.Double worldPt = new Point2D.Double();
        gridToWorld.transform(gridPt, worldPt);
        
        Double value = getValueDirect(worldPt.getX(), worldPt.getY(), projectFile);
        if (value != null) {
          if (value < min) min = value;
          if (value > max) max = value;
        }
      }
    }
    
    return new double[]{min == Double.MAX_VALUE ? 0.0 : min, 
                       max == Double.MIN_VALUE ? 255.0 : max};
  }

  private AffineTransform getGridToWorldTransform(GridCoverage2D coverage) {
    try {
      Object transform = coverage.getGridGeometry().getGridToCRS();
      if (transform instanceof AffineTransform2D) {
        return (AffineTransform2D) transform;
      } else {
        // Fallback: create a simple transform based on envelope
        ReferencedEnvelope env = coverage.getEnvelope2D();
        RenderedImage image = coverage.getRenderedImage();
        if (env != null && image != null) {
          double scaleX = env.getWidth() / image.getWidth();
          double scaleY = env.getHeight() / image.getHeight();
          double transX = env.getMinX();
          double transY = env.getMinY();
          return new AffineTransform(scaleX, 0, 0, scaleY, transX, transY);
        } else {
          return new AffineTransform();
        }
      }
    } catch (Exception e) {
      return new AffineTransform();
    }
  }
  
  @JsonIgnore
  public String getMemoryInfo() {
    StringBuilder info = new StringBuilder();
    info.append("Raster Layer: ").append(getName()).append("\n");
    info.append("Cache enabled: ").append(cacheEnabled).append("\n");
    info.append("Is large raster: ").append(isLargeRaster).append("\n");
    info.append("Access count: ").append(accessCount.get()).append("\n");
    info.append("Has cached raster: ").append(cachedRaster != null).append("\n");
    if (cachedRaster != null) {
      info.append("Cached size: ").append(cachedRaster.getWidth())
          .append("x").append(cachedRaster.getHeight()).append("\n");
    }
    return info.toString();
  }
}
