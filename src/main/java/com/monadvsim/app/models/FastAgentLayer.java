package com.monadvsim.app.models;

import org.geotools.map.DirectLayer;
import org.geotools.map.MapContent;
import org.geotools.map.MapViewport;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.List;


public class FastAgentLayer extends DirectLayer {
  private final AgentLayer agentLayer;

  public FastAgentLayer(AgentLayer al) {
    this.agentLayer = al;
  }

  @Override
  public void draw(Graphics2D g2d, MapContent map, MapViewport viewport) {
    if (!agentLayer.isVisible()) return;
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    List<Agent> agents = agentLayer.getAgents();
    Color agentColor = Color.decode(agentLayer.getColorHex());
    AffineTransform w2s = viewport.getWorldToScreen();
    Point2D.Double wPt = new Point2D.Double();
    Point2D.Double sPt = new Point2D.Double();
    for (Agent a : agents) {
      if (!a.isAlive()) {
        g2d.setColor(Color.DARK_GRAY); // Dead agents appear as grey specks
        wPt.setLocation(a.getX(), a.getY());
        w2s.transform(wPt, sPt);
        g2d.drawRect((int)sPt.x - 2, (int)sPt.y - 2, 4, 4);
        continue;
      }
      if (agentLayer.isTrailsEnabled()) {
        g2d.setColor(new Color(agentColor.getRed(), agentColor.getGreen(), agentColor.getBlue(), 100));
        List<Point2D.Double> history = a.getHistory();
        for (int i = 1; i < history.size(); i++) {
          Point2D.Double p1 = history.get(i - 1);
          Point2D.Double p2 = history.get(i);
          Point2D.Double s1 = new Point2D.Double();
          Point2D.Double s2 = new Point2D.Double();
          w2s.transform(p1, s1);
          w2s.transform(p2, s2);
          g2d.drawLine((int)s1.x, (int)s1.y, (int)s2.x, (int)s2.y);
        }
      }
      wPt.setLocation(a.getX(), a.getY());
      w2s.transform(wPt, sPt);
      g2d.setColor(agentColor);
      g2d.fillOval((int)sPt.x - 3, (int)sPt.y - 3, 6, 6);
    }
  }

  @Override
  public ReferencedEnvelope getBounds() {
    CoordinateReferenceSystem crs = agentLayer.getCoordinateReferenceSystem();
    org.locationtech.jts.geom.Envelope env = new org.locationtech.jts.geom.Envelope(-180, 180, -90, 90);
    return new ReferencedEnvelope(env, crs);
  }
}
