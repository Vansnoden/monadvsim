package com.monadvsim.app.models;

import java.io.File;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.style.Style;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.map.FeatureLayer;
import org.geotools.styling.SLD;
import org.locationtech.jts.geom.Point;
import java.util.Collections;
import java.awt.Color;



public class VectorLayer extends Layer{


  private String colorHex = "#00FF00";
  @JsonIgnore
  private org.geotools.map.Layer cachedGtLayer;
  @JsonIgnore
  private String lastColor = "";
  @JsonIgnore
  private org.geotools.api.data.DataStore dataStore;


  public VectorLayer(){}
  
  
  public VectorLayer(String name, String path) { super(name, path); }
  
  
  public SimpleFeature getFeatureAt(Point point, File projectFile) {
    try {
      SimpleFeatureSource source = (SimpleFeatureSource) getFeatureSource(projectFile);
      if (source == null) return null;
      FilterFactory ff = CommonFactoryFinder.getFilterFactory();
      Filter filter = ff.intersects(ff.property(source.getSchema()
      .getGeometryDescriptor().getLocalName()), ff.literal(point));
      FeatureCollection<?, SimpleFeature> collection = source.getFeatures(filter);
      try (FeatureIterator<SimpleFeature> it = collection.features()) {
        if (it.hasNext()) return it.next();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }
  
  
  public String getColorHex() { return colorHex; }
  
  
  public void setColorHex(String colorHex) { this.colorHex = colorHex; }
  
  
  @JsonIgnore
  public org.geotools.api.data.FeatureSource<?, ?> getFeatureSource(File projectFile) 
  throws Exception {
    if (dataStore == null) {
      File file = resolveFile(projectFile);
      if (file == null || !file.exists()) return null;
      dataStore = org.geotools.api.data.DataStoreFinder.getDataStore(
              Collections.singletonMap("url", file.toURI().toURL()));
    }
    return dataStore != null ? dataStore.getFeatureSource(dataStore.getTypeNames()[0]) : null;
  }
  
  
  @Override
  @JsonIgnore
  public org.geotools.map.Layer getGeoToolsLayer(File projectFile) {
  try {
    if (cachedGtLayer != null && colorHex.equals(lastColor)) {
      // Important: Check if the source inside the cached layer is still alive
      if (cachedGtLayer.getFeatureSource() != null) {
        return cachedGtLayer;
      }
    }

    // Use a generic source check first
    org.geotools.api.data.FeatureSource<?, ?> source = getFeatureSource(projectFile);
    if (source == null) return null;

    Color color = Color.decode(colorHex);
    Style style;
    
    // Use the schema to determine geometry type
    String geometryType = source.getSchema().getGeometryDescriptor().getType().getBinding().getSimpleName();

    if (geometryType.equalsIgnoreCase("Polygon") || geometryType.equalsIgnoreCase("MultiPolygon")) {
      style = SLD.createPolygonStyle(Color.BLACK, color, 1.0f);
    } else if (geometryType.equalsIgnoreCase("LineString") || geometryType.equalsIgnoreCase("MultiLineString")) {
      style = SLD.createLineStyle(color, 2.0f);
    } else {
      style = SLD.createPointStyle("Circle", color, color, 0.8f, 5.0f);
    }

    if (cachedGtLayer != null) {
      cachedGtLayer.preDispose();
    }

    // Create the new layer
    cachedGtLayer = new org.geotools.map.FeatureLayer(source, style);
    lastColor = colorHex;
    return cachedGtLayer;
    
  } catch (Exception e) {
    System.err.println("Error creating GeoTools Layer: " + e.getMessage());
    return null;
  }
}
  

  private File resolveFile(File projectFile) {
    File f = new File(getRelativePath());
    if (f.isAbsolute()) return f;
    if (projectFile == null) return null;
    return new File(projectFile.getParentFile(), getRelativePath());
  }
  
  public void dispose() {
    if (cachedGtLayer != null) cachedGtLayer.preDispose();
    if (dataStore != null) dataStore.dispose();
  }
  
}
