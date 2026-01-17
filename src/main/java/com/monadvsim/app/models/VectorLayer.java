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


public class VectorLayer extends Layer{

  public VectorLayer(){}
  
  public VectorLayer(String name, String path) { super(name, path); }
  
  public SimpleFeature getFeatureAt(Point point, File projectFile) {
    try {
      SimpleFeatureSource source = (SimpleFeatureSource) getFeatureSource(projectFile);
      if (source == null) return null;
      FilterFactory ff = CommonFactoryFinder.getFilterFactory();
      Filter filter = ff.intersects(ff.property(source.getSchema().getGeometryDescriptor().getLocalName()), ff.literal(point));
      FeatureCollection<?, SimpleFeature> collection = source.getFeatures(filter);
      try (FeatureIterator<SimpleFeature> it = collection.features()) {
        if (it.hasNext()) return it.next();
      }
    } catch (Exception e) { e.printStackTrace(); }
      return null;
  }
  
  @JsonIgnore
  public Object getFeatureSource(File projectFile) throws Exception {
    File file = resolveFile(projectFile);
    if (file == null || !file.exists()) return null;
    org.geotools.api.data.DataStore ds = org.geotools.api.data.DataStoreFinder.getDataStore(
    Collections.singletonMap("url", file.toURI().toURL()));
    return ds.getFeatureSource(ds.getTypeNames()[0]);
  }
  
  @Override @JsonIgnore
  public org.geotools.map.Layer getGeoToolsLayer(File projectFile) {
    try {
      SimpleFeatureSource source = (SimpleFeatureSource) getFeatureSource(projectFile);
      Style style = SLD.createSimpleStyle(source.getSchema());
      return new FeatureLayer(source, style);
    } catch (Exception e) { return null; }
  }

  private File resolveFile(File projectFile) {
    File f = new File(getRelativePath());
    return f.isAbsolute() ? f : new File(projectFile.getParentFile(), getRelativePath());
  }
  
}
