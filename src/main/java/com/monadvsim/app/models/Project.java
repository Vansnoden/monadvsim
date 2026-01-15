package com.monadvsim.app.models;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


public class Project{
  private String name = "New Project";
  private String crs = "EPSG:4326";
  private List<Layer> layers = new ArrayList<>();
  private transient File projectFile = null;
  
  public Project(){}
  
  public String getName(){ 
    return this.name; 
  }
  
  public void setName(String name){ 
    this.name=name; 
  }

  public String getCrs(){
    return crs; 
  }
  
  public void setCrs(String crs){ 
    this.crs=crs;
  }

  public String getCrsCode(){ 
    return this.crs; 
  }
  
  public void setCrsCode(String code){
    this.crs=code; 
  }
  
  public List<Layer> getLayers() { 
    if (layers == null) {
      layers = new ArrayList<>();
    }
    return layers; 
  }
  
  public void setLayers(List<Layer> layers) { 
    this.layers = layers; 
  }
}
