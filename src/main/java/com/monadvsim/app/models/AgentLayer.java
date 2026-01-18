package com.monadvsim.app.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.feature.FeatureIterator;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AgentLayer extends Layer {
  
  private List<Agent> agents = new CopyOnWriteArrayList<>();
  private List<AgentRule> rules = new CopyOnWriteArrayList<>();
  private boolean wrapAround = true;
  private String colorHex = "#00FF00";
  
  private boolean heatmapEnabled = false;
  private boolean trailsEnabled = false;

  // Transient fields (not saved in JSON project file)
  @JsonIgnore private byte[][] terrainCache;
  @JsonIgnore private Envelope cacheBounds;
  @JsonIgnore private double gridResX, gridResY;
  @JsonIgnore private GeometryFactory gf = new GeometryFactory();
  
  // Maps internal byte IDs to the original layer names for rule checking
  @JsonIgnore private Map<Byte, String> terrainLookup = new HashMap<>();

  public AgentLayer() { 
    super("Agents", "internal://agents"); 
    this.crsCode = "EPSG:4326";
    // Initialize with one default rule
    this.rules.add(new AgentRule());
  }

  public AgentLayer(String name) { 
    super(name, "internal://agents"); 
  }
  
  /**
   * Movement is only allowed if ALL rules in the list agree.
   */
  public boolean validateMovement(double x, double y) {
    String terrainType = fastDetectTerrain(x, y);
    for (AgentRule r : rules) {
      if (!r.isAllowed(terrainType)) {
        return false;
      }
    }
    return true;
  }
  
  /**
   * Survival check against all environment readings for all rules.
   */
  public boolean checkSurvival(Map<String, Object> environment) {
    for (AgentRule r : rules) {
      for (Map.Entry<String, Object> entry : environment.entrySet()) {
        if (!r.checkSurvival(entry.getKey(), entry.getValue())) {
          return false;
        }
      }
    }
    return true;
  }
  
  public void setPopulation(int count, Project project) {
    int currentCount = agents.size();
    if (count == currentCount) return;
    if (count < currentCount) {
      agents = new CopyOnWriteArrayList<>(agents.subList(0, count));
    } else {
      int toAdd = count - currentCount;
      Envelope env = getSimulationExtent(project);
      List<Agent> newAgents = IntStream.range(0, toAdd)
      .parallel()
      .mapToObj(i -> {
        double rx, ry;
        int attempts = 0;
        do {
          rx = env.getMinX() + (Math.random() * env.getWidth());
          ry = env.getMinY() + (Math.random() * env.getHeight());
          attempts++;
        } while (!validateMovement(rx, ry) && attempts < 100);
        return new Agent(rx, ry);
      }).collect(Collectors.toList());
      this.agents.addAll(newAgents);
    }
  }
  
  public void updateAll(Project project) {
    Envelope bounds = getSimulationExtent(project);
    agents.parallelStream().forEach(a -> a.step(project, this, bounds));
    agents.removeIf(a -> !a.isAlive());
  }
  
  /**
   * Rapid O(1) lookup into the baked grid using dynamic ID mapping.
   */
  public String fastDetectTerrain(double x, double y) {
    if (terrainCache == null || cacheBounds == null) return "land";
    int col = (int) ((x - cacheBounds.getMinX()) / gridResX);
    int row = (int) ((y - cacheBounds.getMinY()) / gridResY);
    if (col >= 0 && col < terrainCache.length && row >= 0 && row < terrainCache[0].length) {
      byte val = terrainCache[col][row];
      if (val == 0) return "empty";
      return terrainLookup.getOrDefault(val, "land");
    }
    return "empty"; 
  }
  
  /**
   * Bakes vector geometry into a grid for rapid collision detection.
   */
  public void bakeTerrainCache(Project project, int resolution, Consumer<Integer> progressListener) {
    this.cacheBounds = getSimulationExtent(project);
    if (cacheBounds == null || cacheBounds.isNull()) return;
    
    this.terrainCache = new byte[resolution][resolution];
    this.terrainLookup.clear();
    this.gridResX = cacheBounds.getWidth() / resolution;
    this.gridResY = cacheBounds.getHeight() / resolution;
    
    if (gf == null) gf = new GeometryFactory();
    STRtree index = new STRtree();
    
    for (Layer l : project.getLayers()) {
      if (l instanceof VectorLayer vl && vl.isVisible()) {
        try {
          SimpleFeatureSource source = (SimpleFeatureSource) vl.getFeatureSource(project.getProjectFile());
          try (FeatureIterator<?> it = source.getFeatures().features()) {
            while (it.hasNext()) {
              SimpleFeature f = (SimpleFeature) it.next();
              Geometry g = (Geometry) f.getDefaultGeometry();
              if (g != null) {
                index.insert(g.getEnvelopeInternal(), new Object[]{g, vl.getName()});
              }
            }
          }
        } catch (Exception ignored) {}
      }
    }
    index.build();

    for (int i = 0; i < resolution; i++) {
      final int colIdx = i;
      double wx = cacheBounds.getMinX() + (i * gridResX);
      IntStream.range(0, resolution).parallel().forEach(j -> {
        double wy = cacheBounds.getMinY() + (j * gridResY);
        Point p = gf.createPoint(new Coordinate(wx, wy));
        List<?> results = index.query(p.getEnvelopeInternal());
        
        byte cellValue = 0; 
        for (Object o : results) {
          Object[] data = (Object[]) o;
          Geometry geom = (Geometry) data[0]; // Extract geometry from object array
          String layerName = (String) data[1]; 
          if (geom.contains(p)) {
            cellValue = getOrAssignId(layerName);
            break;
          }
        }
        terrainCache[colIdx][j] = cellValue;
      });
      if (i % 25 == 0 && progressListener != null) {
        progressListener.accept((int) ((i / (double) resolution) * 100));
      }
    }
    if (progressListener != null) progressListener.accept(100);
  }

  /**
   * Synchronized helper to map layer names to byte IDs during bake.
   */
  private synchronized byte getOrAssignId(String name) {
    for (Map.Entry<Byte, String> entry : terrainLookup.entrySet()) {
      if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
    }
    byte newId = (byte) (terrainLookup.size() + 1);
    terrainLookup.put(newId, name.toLowerCase());
    return newId;
  }

  public Envelope getSimulationExtent(Project project) {
      Envelope env = new Envelope();
      String targetCrs = project.getCrsCode();
      if (project.getLayers() != null) {
          for (Layer l : project.getLayers()) {
              if (l.isVisible() && !(l instanceof AgentLayer)) {
                  try {
                      ReferencedEnvelope bounds = null;
                      if (l instanceof VectorLayer vl) {
                          bounds = ((SimpleFeatureSource) vl.getFeatureSource(project.getProjectFile())).getBounds();
                      }
                      if (bounds != null) {
                          CoordinateReferenceSystem projCRS = CRS.decode(targetCrs);
                          if (!CRS.equalsIgnoreMetadata(bounds.getCoordinateReferenceSystem(), projCRS)) {
                              bounds = bounds.transform(projCRS, true);
                          }
                          env.expandToInclude(bounds);
                      }
                  } catch (Exception ignored) {}
              }
          }
      }
      if (env.isNull() || env.getWidth() <= 0) {
          return targetCrs != null && targetCrs.contains("3857") ? 
              new Envelope(-20037508, 20037508, -20037508, 20037508) : 
              new Envelope(-180, 180, -90, 90);
      }
      return env;
  }
  
  public Map<String, Object> probeEnvironment(double x, double y, Project project) {
    Map<String, Object> readings = new HashMap<>();
    for (Layer l : project.getLayers()) {
      if (l instanceof RasterLayer rl && rl.isVisible()) {
        Double val = rl.getValueAt(x, y, project.getProjectFile());
        readings.put(rl.getName(), val);
      }
      // You can expand this to include Vector layer data if needed
    }
    return readings;
  }

  @Override @JsonIgnore 
  public CoordinateReferenceSystem getCoordinateReferenceSystem() {
    try { return CRS.decode(crsCode); } catch (Exception e) { return DefaultGeographicCRS.WGS84; }
  }

  @Override @JsonIgnore 
  public org.geotools.map.Layer getGeoToolsLayer(File f) { return null; }
  
  // --- Getters & Setters ---
  public List<AgentRule> getRules() { return rules; }
  public void addRule(AgentRule rule) { this.rules.add(rule); }
  public void clearRules() { this.rules.clear(); }

  @JsonIgnore
  public AgentRule getPrimaryRule() {
      return rules.isEmpty() ? null : rules.get(0);
  }
  
  public boolean isHeatmapEnabled() { return heatmapEnabled; }
  public void setHeatmapEnabled(boolean heatmapEnabled) { this.heatmapEnabled = heatmapEnabled; }
  public boolean isTrailsEnabled() { return trailsEnabled; }
  public void setTrailsEnabled(boolean trailsEnabled) { this.trailsEnabled = trailsEnabled; }
  public List<Agent> getAgents() { return agents; }
  public void setAgents(List<Agent> agents) { this.agents = agents; }
  public boolean isWrapAround() { return wrapAround; }
  public void setWrapAround(boolean wrapAround) { this.wrapAround = wrapAround; }
  public String getColorHex() { return colorHex; }
  public void setColorHex(String colorHex) { this.colorHex = colorHex; }
  @JsonIgnore public int getAgentsCount() { return agents.size(); }
}
