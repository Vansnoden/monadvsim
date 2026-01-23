package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.Agent;
import com.monadvsim.app.models.entities.QuadTree;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

public class SpatialRegistry {
    private QuadTree tree;
    private Rectangle2D worldBounds;

    public SpatialRegistry(Rectangle2D worldBounds) {
        this.worldBounds = worldBounds;
        this.tree = new QuadTree(worldBounds);
    }

    public void update(List<Agent> allAgents) {
        tree.clear();
        for (Agent a : allAgents) {
            tree.insert(a);
        }
    }

    /**
     * Query for nearby agents (e.g., search for breeding sites).
     */
    public List<Agent> getNearbyAgents(double x, double y, double radius) {
        Rectangle2D searchArea = new Rectangle2D.Double(
            x - radius, y - radius, radius * 2, radius * 2
        );
        return tree.query(searchArea, new ArrayList<>());
    }
}