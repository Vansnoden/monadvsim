package com.monadvsim.app.models;


public class AgentLayer extends Layer{

  public AgentLayer() { 
    super("Agents", "internal://agents"); 
    this.crsCode = "EPSG:4326";
  }

  public AgentLayer(String name) { 
    super(name, "internal://agents"); 
  }
}
