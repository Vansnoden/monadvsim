package com.monadvsim.app.views;

import com.monadvsim.app.models.Agent;
import com.monadvsim.app.models.AgentLayer;
import com.monadvsim.app.models.Layer;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.map.MapContent;
import org.geotools.renderer.lite.StreamingRenderer;
import org.geotools.swing.JMapPane;
import org.geotools.swing.tool.CursorTool;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;



public class SimulationCanvas extends JMapPane {
  
  static {
      System.setProperty("org.geotools.referencing.forceXY", "true");
  }

  private List<Layer> internalLayers = new ArrayList<>();
  private String currentEpsgCode = "EPSG:4326"; // Store current CRS state


  public SimulationCanvas() {
    this.setDoubleBuffered(true);
    this.setBackground(Color.WHITE);
    MapContent content = new MapContent();
    this.setMapContent(content);
    updateViewportCRS(currentEpsgCode);
    setupRenderer();
  }


  private void setupRenderer() {
    Map<String, Object> hints = new HashMap<>();
    hints.put("screenDevice", "true");
    hints.put("memoryCache", "true");
    StreamingRenderer renderer = new StreamingRenderer();
    renderer.setRendererHints(hints);
    this.setRenderer(renderer);
  }
  

  public void updateViewportCRS(String epsgCode) {
    if (getMapContent() == null) return;
    try {
      this.currentEpsgCode = epsgCode;
      CoordinateReferenceSystem crs = CRS.decode(epsgCode, true);
      getMapContent().getViewport().setCoordinateReferenceSystem(crs);
      zoomToData();
      this.repaint();
    } catch (Exception e) {
      System.err.println("CRS Update Failed: " + e.getMessage());
    }
  }
  

  public void updateLayers(List<Layer> projectLayers, File projectFile) {
    this.internalLayers = projectLayers;
    SwingUtilities.invokeLater(() -> {
      MapContent content = getMapContent();
      if (content == null) return;

      // Capture view
      ReferencedEnvelope existingView = this.getDisplayArea();

      // 1. Clear layers safely
      content.layers().clear();

      // 2. Set CRS
      try {
          content.getViewport().setCoordinateReferenceSystem(CRS.decode(currentEpsgCode));
      } catch (Exception ignored) {}

      // 3. Add Vector Layers
      boolean hasVectorLayers = false;
      for (int i = projectLayers.size() - 1; i >= 0; i--) {
        Layer ly = projectLayers.get(i);
        if (!ly.isVisible()) continue;
        
        if (!(ly instanceof AgentLayer)) {
            org.geotools.map.Layer gtLayer = ly.getGeoToolsLayer(projectFile);
          if (gtLayer != null && gtLayer.getFeatureSource() != null) {
            content.addLayer(gtLayer);
            hasVectorLayers = true;
          }
        }
      }

      // 4. THE FIX: Handle Viewport and Refresh
      if (!hasVectorLayers) {
        this.repaint();
        return;
      }

      // Check if we have a valid view to restore, otherwise zoom to data
      if (existingView == null || existingView.isEmpty() || 
        Double.isNaN(existingView.getSpan(0)) || existingView.getWidth() <= 0) {
        
        // This is likely the "New Layer" or "Empty Canvas" state
        zoomToData(); 
      } else {
        // Force the pane to re-render the specific area
        this.setDisplayArea(new ReferencedEnvelope(existingView));
      }

      // Give the StreamingRenderer a kick
      this.repaint(); 
    });
  }
  

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    if (internalLayers == null || getMapContent() == null) return;
    Graphics2D g2d = (Graphics2D) g;
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    try {
      AffineTransform screenToWorld = getScreenToWorldTransform();
      if (screenToWorld == null) return;
      AffineTransform worldToScreen = screenToWorld.createInverse();
      for (Layer layer : internalLayers) {
        if (layer instanceof AgentLayer al && al.isVisible()) {
          renderAgentLayer(g2d, al, worldToScreen);
        }
      }
    } catch (NoninvertibleTransformException e) {
      // Silently fail during window resizing
    }
  }
  

  private void renderAgentLayer(Graphics2D g2d, AgentLayer al, AffineTransform worldToScreen) {
    Composite originalComposite = g2d.getComposite();
    // Apply Global Layer Opacity
    if (al.getOpacity() < 1.0f) {
      g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, al.getOpacity()));
    }
    Color agentColor = Color.decode(al.getColorHex());
    List<Agent> agents = al.getAgents();
    // Reusable point objects to reduce pressure on Garbage Collector
    Point2D worldPt = new Point2D.Double();
    Point2D screenPt = new Point2D.Double();
    Point2D p1 = new Point2D.Double();
    Point2D p2 = new Point2D.Double();
    int dotSize = 6;
    for (Agent a : agents) {
      worldPt.setLocation(a.getX(), a.getY());
      worldToScreen.transform(worldPt, screenPt);
      // Render Trails
      if (al.isTrailsEnabled()) {
        List<Point2D.Double> history = a.getHistory();
        g2d.setColor(agentColor);
        for (int i = 1; i < history.size(); i++) {
          worldToScreen.transform(history.get(i - 1), p1);
          worldToScreen.transform(history.get(i), p2);
          // Fade trail based on age
          float trailAlpha = (float) i / history.size();
          g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 
          al.getOpacity() * trailAlpha));
          g2d.drawLine((int)p1.getX(), (int)p1.getY(), (int)p2.getX(), (int)p2.getY());
        }
        // Reset composite for the main agent dot
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 
        al.getOpacity()));
      }
      // Render Agent Dot
      g2d.setColor(Color.BLACK);
      g2d.fillOval((int)screenPt.getX() - 1, 
      (int)screenPt.getY() - 1,
      dotSize + 2, 
      dotSize + 2);
      g2d.setColor(agentColor);
      g2d.fillOval((int)screenPt.getX(), (int)screenPt.getY(), dotSize, dotSize);
    }
    g2d.setComposite(originalComposite); // Restore
  }


  public void zoomToData() {
    MapContent content = getMapContent();
    if (content != null && !content.layers().isEmpty()) {
      ReferencedEnvelope env = content.getMaxBounds();
      if (env != null && !env.isEmpty() && !Double.isNaN(env.getSpan(0))) {
        this.setDisplayArea(env);
      }
    }
  }


  public void setActiveTool(CursorTool tool) {
    super.setCursorTool(tool);
  }
  
  public void forceRefresh() {
    // This ensures a complete repaint of the canvas
    repaint();
    
    // Also ensure the MapContent knows it needs redrawing
    if (getMapContent() != null) {
        getMapContent().dispose(); // Force GeoTools to refresh
        // Recreate MapContent if needed
    }
  }
  
  public void refreshAgentDisplay() {
    // This is lighter weight - just repaint the agents
    repaint();
  }

}
