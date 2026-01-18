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

import java.io.File;
import java.io.IOException;
import java.awt.geom.Point2D;


public class RasterLayer extends Layer{

  @JsonIgnore
  private GridCoverage2DReader readerReference;

  public RasterLayer() {}
  public RasterLayer(String name, String relativePath) { super(name, relativePath); }

  public Double getValueAt(double x, double y, File projectFile) {
    try {
      GridCoverage coverage = getGridCoverage(projectFile);
      if (!(coverage instanceof GridCoverage2D g2d)) return null;

      // 1. Convert World Coordinates (Lat/Lon or Meters) to Grid Coordinates (Pixels)
      Point2D worldPt = new Point2D.Double(x, y);
      Point2D gridPt = g2d.getGridGeometry().getCRSToGrid2D().transform(worldPt, null);

      // 2. Sample the underlying image data directly at the pixel location
      int col = (int) gridPt.getX();
      int row = (int) gridPt.getY();

      // 3. Simple bounds check
      if (col < 0 || row < 0 || col >= g2d.getRenderedImage().getWidth() || row >= g2d.getRenderedImage().getHeight()) {
          return null;
      }

      // 4. Get the pixel value (band 0)
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
  
  @Override @JsonIgnore
  public org.geotools.map.Layer getGeoToolsLayer(File projectFile) {
    try {
      File file = resolveFile(projectFile);
      if (file == null || !file.exists()) return null;
      AbstractGridFormat format = GridFormatFinder.findFormat(file);
      if (format == null) return null;
      this.readerReference = format.getReader(file);
      StyleFactory sf = CommonFactoryFinder.getStyleFactory();
      RasterSymbolizer symbolizer = sf.createRasterSymbolizer();
      return new GridReaderLayer(readerReference, SLD.wrapSymbolizers(symbolizer));
    } catch (Exception e) { return null; }
  }

    @JsonIgnore
    public GridCoverage getGridCoverage(File projectFile) throws IOException {
      if (readerReference != null) {
            return readerReference.read((org.geotools.api.parameter.GeneralParameterValue[]) null);
      }
      File file = resolveFile(projectFile);
      if (file == null || !file.exists()) return null;
      AbstractGridFormat format = GridFormatFinder.findFormat(file);
      if (format == null) return null;
      this.readerReference = format.getReader(file);
      return readerReference.read((org.geotools.api.parameter.GeneralParameterValue[]) null);
    }
  
}
