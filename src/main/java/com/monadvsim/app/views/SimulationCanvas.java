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


public class SimulationCanvas extends JMapPane{

  private List<Layer> internalLayers = new ArrayList<>();

  public SimulationCanvas() {
    this.setDoubleBuffered(true);
    this.setBackground(new Color(255, 255, 255)); 
    MapContent content = new MapContent();
    try {
      CoordinateReferenceSystem crs = CRS.decode("EPSG:4326");
      content.getViewport().setCoordinateReferenceSystem(crs);
    } catch (Exception e) {
      System.err.println("Default CRS Error: " + e.getMessage());
    }
    this.setMapContent(content);
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
      CoordinateReferenceSystem crs = CRS.decode(epsgCode);
      getMapContent().getViewport().setCoordinateReferenceSystem(crs);
      // Re-zoom to the data to avoid "lost" viewports after CRS change
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
      ReferencedEnvelope existingView = this.getDisplayArea();
      for (org.geotools.map.Layer gtLayer : content.layers()) {
        gtLayer.preDispose();
      }
      content.layers().clear();
      for (Layer ly : projectLayers) {
        if (!ly.isVisible()) continue;
        if (!(ly instanceof AgentLayer)) {
          org.geotools.map.Layer gtLayer = ly.getGeoToolsLayer(projectFile);
          if (gtLayer != null) content.addLayer(gtLayer);
        }
      }
      if (existingView == null || existingView.isEmpty() 
      || Double.isNaN(existingView.getSpan(0))) {
        zoomToData();
      } else {
        this.setDisplayArea(existingView);
      }
      this.repaint();
    });
  }
  
  public void setActiveTool(CursorTool tool) {
    super.setCursorTool(tool);
  }
  
  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g); 
    if (internalLayers == null) return;
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
      e.printStackTrace();
    }
  }
  
  private void renderAgentLayer(Graphics2D g2d, AgentLayer al, AffineTransform worldToScreen) {
    Color agentColor = Color.decode(al.getColorHex());
    List<Agent> agents = al.getAgents();
    Point2D worldPt = new Point2D.Double();
    Point2D screenPt = new Point2D.Double();
    Point2D histScreenPt = new Point2D.Double();
    Point2D currentHistScreen = new Point2D.Double();
    int dotSize = 6; 
    for (Agent a : agents) {
      worldPt.setLocation(a.getX(), a.getY());
      worldToScreen.transform(worldPt, screenPt);
      if (al.isTrailsEnabled()) {
        List<Point2D.Double> history = a.getHistory();
        for (int i = 1; i < history.size(); i++) {
          worldToScreen.transform(history.get(i - 1), histScreenPt);
          worldToScreen.transform(history.get(i), currentHistScreen);
          float alpha = (float) i / history.size();
          g2d.setColor(new Color(agentColor.getRed(), agentColor.getGreen(), agentColor.getBlue(), (int)(alpha * 255)));
          g2d.drawLine((int)histScreenPt.getX(), (int)histScreenPt.getY(), (int)currentHistScreen.getX(), (int)currentHistScreen.getY());
        }
      }
      g2d.setColor(Color.BLACK);
      g2d.fillOval((int)screenPt.getX() - 1, (int)screenPt.getY() - 1, dotSize + 2, dotSize + 2);
      g2d.setColor(agentColor);
      g2d.fillOval((int)screenPt.getX(), (int)screenPt.getY(), dotSize, dotSize);
    }
  }
  
  public void zoomToData() {
    MapContent content = getMapContent();
    if (content != null && !content.layers().isEmpty()) {
      ReferencedEnvelope env = content.getMaxBounds();
      if (env != null && !env.isEmpty()) {
        this.setDisplayArea(env);
      }
    }
  }
  
}
