package com.monadvsim.app.models;

import com.fasterxml.jackson.annotation.*;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import java.io.File;
import java.io.Serializable;


@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME, 
    include = JsonTypeInfo.As.PROPERTY, 
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = VectorLayer.class, name = "vector"),
    @JsonSubTypes.Type(value = RasterLayer.class, name = "raster"),
    @JsonSubTypes.Type(value = AgentLayer.class, name = "agents")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Layer implements Serializable {
  
  protected String name;
  protected boolean visible = true;
  protected String relativePath;
  protected String crsCode = "EPSG:4326";
  protected float opacity = 1.0f;

  public Layer(){}
  
  public Layer(String name, String relativePath) {
    this.name = name;
    this.relativePath = relativePath;
  }
  
  @JsonIgnore
  public CoordinateReferenceSystem getCoordinateReferenceSystem() {
  try {
      if (crsCode == null || crsCode.isEmpty()) {
        return DefaultGeographicCRS.WGS84;
      }
      return CRS.decode(crsCode);
    } catch (Exception e) {
      return DefaultGeographicCRS.WGS84;
    }
  }
  
  @JsonIgnore
  public abstract org.geotools.map.Layer getGeoToolsLayer(File projectFile);
  
  public String getName() { return name; }
  
  public void setName(String name) { this.name = name; }

  public boolean isVisible() { return visible; }
  
  public void setVisible(boolean visible) { this.visible = visible; }

  public String getRelativePath() { return relativePath; }
  
  public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

  public String getCrsCode() { return crsCode; }
  
  public void setCrsCode(String crsCode) { this.crsCode = crsCode; }

  public float getOpacity() { return opacity; }
  
  public void setOpacity(float opacity) { this.opacity = opacity; }

}
