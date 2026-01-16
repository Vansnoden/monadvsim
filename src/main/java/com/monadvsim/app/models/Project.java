package com.monadvsim.app.models;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.dataformat.xml.annotation.*;


@JacksonXmlRootElement(localName = "monadProject")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Project{

  @JacksonXmlProperty(isAttribute = true)
  private String name = "New Project";
  @JacksonXmlProperty
  private String crs = "EPSG:4326";
  @JacksonXmlElementWrapper(localName = "layers")
  @JacksonXmlProperty(localName = "layer")
  private List<Layer> layers = new ArrayList<>();
  @JsonIgnore
  private transient File projectFile = null; // transient ensures Java's default serializer ignores it
  
  public Project(){}
  
  public String getName(){ 
    return this.name; 
  }
  
  public void setName(String name){ 
    this.name=name; 
  }
  
  public void setProjectFile(File newFile){
    this.projectFile = newFile;
  }

  @JsonIgnore
  public String getCrs(){
    return crs; 
  }
  
  @JsonIgnore
  public void setCrs(String crs){ 
    this.crs=crs;
  }

  @JsonIgnore
  public File getProjectFile() { return projectFile; }
    
  @JsonIgnore
  public void setProjectFile(File pf) { this.projectFile = pf; }
  
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
