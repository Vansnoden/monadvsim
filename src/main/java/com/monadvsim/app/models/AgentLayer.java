package com.monadvsim.app.models;


import java.io.File;
import com.fasterxml.jackson.annotation.JsonIgnore;


public class AgentLayer extends Layer{

  public AgentLayer() { 
    super("Agents", "internal://agents"); 
    this.crsCode = "EPSG:4326";
  }

  public AgentLayer(String name) { 
    super(name, "internal://agents"); 
  }
  
  @Override @JsonIgnore
  public org.geotools.map.Layer getGeoToolsLayer(File projectFile){
    return null;
  }
}
