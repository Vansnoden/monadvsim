package com.monadvsim.app.models;

import java.io.File;
import com.fasterxml.jackson.annotation.JsonIgnore;


public class RasterLayer extends Layer{

  public RasterLayer() {}
  
  public RasterLayer(String name, String relativePath) { super(name, relativePath); }
  
  @Override @JsonIgnore
  public org.geotools.map.Layer getGeoToolsLayer(File projectFile){
    return null;
  }
}
