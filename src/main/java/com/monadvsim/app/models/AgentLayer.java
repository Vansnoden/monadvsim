package com.monadvsim.app.models;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.dataformat.xml.annotation.*;
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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;



public class AgentLayer extends Layer {

  private List<Agent> agents = new CopyOnWriteArrayList<>();
  @JsonProperty("baseSpeed")
  private double baseSpeed = 0.001;
  @JacksonXmlElementWrapper(localName = "rules")
  @JacksonXmlProperty(localName = "rule")
  @JsonProperty("rules")
  private List<AgentRule> rules = new CopyOnWriteArrayList<>();
  private boolean wrapAround = false;
  private String colorHex = "#00FF00";
  private boolean heatmapEnabled = false;
  private boolean trailsEnabled = false;
  @JsonIgnore private byte[][] terrainCache;
  @JsonIgnore private Envelope cacheBounds;
  @JsonIgnore private double gridResX, gridResY;
  @JsonIgnore private GeometryFactory gf = new GeometryFactory();
  @JsonIgnore private Map<Byte, String> terrainLookup = new HashMap<>();


  public AgentLayer() {
    super("Agents", "internal://agents");
    this.crsCode = "EPSG:4326";
  }
  

  public AgentLayer(String name) {
    super(name, "internal://agents");
  }
  
  
  public boolean validateMovement(double x, double y) {
    // If there are no rules, everything is allowed (Default Wandering)
    if (rules.isEmpty()) return true;
    String terrainType = fastDetectTerrain(x, y);
    return rules.stream().allMatch(r -> r.isAllowed(terrainType));
  }


  public boolean checkSurvival(Map<String, Object> environment) {
    if (rules.isEmpty()) return true;
    return rules.stream().allMatch(r -> 
      environment.entrySet().stream()
      .allMatch(e -> r.checkSurvival(e.getKey(), e.getValue()))
    );
  }


  public void setPopulation(int count, Project project) {
    int currentCount = agents.size();
    if (count == currentCount) return;
    if (count < currentCount) {
      agents = new CopyOnWriteArrayList<>(agents.subList(0, count));
    } else {
      Envelope env = getSimulationExtent(project);
      List<Agent> newAgents = IntStream.range(0, count - currentCount)
      .parallel().mapToObj(i -> {
        double rx, ry;
        int attempts = 0;
        do {
          rx = env.getMinX() + (Math.random() * env.getWidth());
          ry = env.getMinY() + (Math.random() * env.getHeight());
        } while (!validateMovement(rx, ry) && ++attempts < 100);
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
  

  public String fastDetectTerrain(double x, double y) {
    if (terrainCache == null || cacheBounds == null) return "land";
    int col = (int) ((x - cacheBounds.getMinX()) / gridResX);
    int row = (int) ((y - cacheBounds.getMinY()) / gridResY);
    if (col >= 0 && col < terrainCache.length && row >= 0 && row < terrainCache[0].length){
      byte val = terrainCache[col][row];
      return (val == 0) ? "empty" : terrainLookup.getOrDefault(val, "land");
    }
    return "empty";
  }

  
  private void streamLayers(STRtree index, Project project){
    project.getLayers().stream().filter(l -> l instanceof VectorLayer vl && vl.isVisible())
    .forEach(l -> {
      try {
        SimpleFeatureSource src = (SimpleFeatureSource) ((VectorLayer)l)
        .getFeatureSource(project.getProjectFile());
        try (FeatureIterator<?> it = src.getFeatures().features()) {
          while (it.hasNext()) {
            SimpleFeature f = (SimpleFeature) it.next();
            Geometry g = (Geometry) f.getDefaultGeometry();
            if (g != null){
              index.insert(g.getEnvelopeInternal(), new Object[]{g, l.getName()});
            }
          }
        }
      } catch (Exception ignored) {}
    });
    index.build();
  }
  
  
  private void initSpatialIndexing(STRtree index, int res, Consumer<Integer> progress){
    for (int i = 0; i < res; i++) {
      final int col = i;
      double wx = cacheBounds.getMinX() + (i * gridResX);
      IntStream.range(0, res).parallel().forEach(j -> {
        Point p = gf.createPoint(new Coordinate(wx, cacheBounds.getMinY() + (j * gridResY)));
        List<?> results = index.query(p.getEnvelopeInternal());
        for (Object o : results) {
          Object[] data = (Object[]) o;
          if (((Geometry) data[0]).contains(p)) {
            terrainCache[col][j] = getOrAssignId((String) data[1]);
            break;
          }
        }
      });
      if (i % 25 == 0 && progress != null) progress.accept((int) ((i / (double) res) * 100));
    }
  }
  

  public void bakeTerrainCache(Project project, int res, Consumer<Integer> progress) {
    this.cacheBounds = getSimulationExtent(project);
    if (cacheBounds == null || cacheBounds.isNull()) return;
    this.terrainCache = new byte[res][res];
    this.terrainLookup.clear();
    this.gridResX = cacheBounds.getWidth() / res;
    this.gridResY = cacheBounds.getHeight() / res;
    STRtree index = new STRtree();
    this.streamLayers(index, project);
    this.initSpatialIndexing(index, res, progress);
    if (progress != null) progress.accept(100);
  }


  private synchronized byte getOrAssignId(String name) {
    for (Map.Entry<Byte, String> e : terrainLookup.entrySet()) {
      if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
    }
    byte newId = (byte) (terrainLookup.size() + 1);
    terrainLookup.put(newId, name.toLowerCase());
    return newId;
  }


  public Envelope getSimulationExtent(Project project) {
    Envelope env = new Envelope();
    for (Layer l : project.getLayers()) {
      if (l.isVisible() && !(l instanceof AgentLayer)) {
        try {
          ReferencedEnvelope b = null;
          if (l instanceof VectorLayer vl) {
            b = ((SimpleFeatureSource) vl
            .getFeatureSource(project.getProjectFile())).getBounds();
          }
          if (b != null) {
            CoordinateReferenceSystem projCRS = CRS.decode(project.getCrsCode());
            if (!CRS.equalsIgnoreMetadata(b.getCoordinateReferenceSystem(), projCRS)){
              b = b.transform(projCRS, true);
            } 
            env.expandToInclude(b);
          }
        } catch (Exception ignored) {}
      }
    }
    if (env.isNull()) return project.getCrsCode().contains("3857") ? 
    new Envelope(-20037508, 20037508, -20037508, 20037508) : new Envelope(-180, 180, -90, 90);
    return env;
  }
  

  @Override 
  @JsonIgnore 
  public CoordinateReferenceSystem getCoordinateReferenceSystem() {
    try {   
      return CRS.decode(crsCode); 
    } catch (Exception e) { 
      return DefaultGeographicCRS.WGS84; 
    }
  }


  @Override 
  @JsonIgnore 
  public org.geotools.map.Layer getGeoToolsLayer(File f) { 
    return null; 
  }

  // Getters & Setters
  
  @JsonIgnore 
  public List<AgentRule> getRules() { 
    return rules; 
  }
  
  
  public void addRule(AgentRule rule) { 
    this.rules.add(rule); 
  }
  
  
  @JsonIgnore 
  public AgentRule getPrimaryRule() { 
    return rules.isEmpty() ? null : rules.get(0); 
  }
  
  
  public double getBaseSpeed() { 
    return baseSpeed; 
  }
  
  
  public void setBaseSpeed(double baseSpeed) { 
    this.baseSpeed = baseSpeed; 
  }
  
  
  public List<Agent> getAgents() { 
    return agents; 
  }
  
  
  public boolean isWrapAround() { 
    return wrapAround; 
  }
  
  
  public void setWrapAround(boolean wrapAround) { 
    this.wrapAround = wrapAround; 
  }
  
  
  public String getColorHex() { 
    return colorHex; 
  }
  
  
  public void setColorHex(String colorHex) { 
    this.colorHex = colorHex; 
  }
  
  
  public boolean isHeatmapEnabled() { 
    return heatmapEnabled; 
  }
  
  
  public void setHeatmapEnabled(boolean heatmapEnabled) { 
    this.heatmapEnabled = heatmapEnabled; 
  }
  
  
  public boolean isTrailsEnabled() { 
    return trailsEnabled; 
  }
  
  
  public void setTrailsEnabled(boolean trailsEnabled) { 
    this.trailsEnabled = trailsEnabled; 
  }
  
  
  @JsonIgnore 
  public int getAgentsCount() { 
    return agents.size(); 
  }
  
  
  @JsonIgnore 
  public byte[][] getTerrainCache() { 
    return terrainCache; 
  }
  
  @JsonIgnore
  public Map<String, Object> probeEnvironment(double x, double y, Project project) {
    Map<String, Object> environmentReadings = new HashMap<>();
    for (Layer l : project.getLayers()) {
      if (l instanceof RasterLayer rl && rl.isVisible()) {
        try {
          Object value = rl.getValueAt(x, y, project.getProjectFile());
          if (value != null) {
            environmentReadings.put(rl.getName(), value);
          }
        } catch (Exception e) {
          System.err.println("Could not probe layer: " 
          + rl.getName() + " at " + x + "," + y);
        }
      }
    }
    return environmentReadings;
  }
}
