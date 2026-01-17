package com.monadvsim.app.models;

import java.io.File;
import com.fasterxml.jackson.annotation.JsonIgnore;


public class VectorLayer extends Layer{

  public VectorLayer(){}
  
  public VectorLayer(String name, String path) { super(name, path); }

  @Override @JsonIgnore
  public org.geotools.map.Layer getGeoToolsLayer(File projectFile){
    return null;
  }
}
