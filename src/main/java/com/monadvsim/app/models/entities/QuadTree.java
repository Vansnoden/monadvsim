package com.monadvsim.app.models.entities;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

public class QuadTree {
    private final int CAPACITY = 32; // Maximum agents per leaf before splitting
    private Rectangle2D boundary;
    private List<Agent> agents;
    private QuadTree northWest, northEast, southWest, southEast;
    private boolean divided = false;

    public QuadTree(Rectangle2D boundary) {
        this.boundary = boundary;
        this.agents = new ArrayList<>();
    }

    // Subdivide the node into four quadrants
    private void subdivide() {
        double x = boundary.getX();
        double y = boundary.getY();
        double w = boundary.getWidth() / 2;
        double h = boundary.getHeight() / 2;

        northWest = new QuadTree(new Rectangle2D.Double(x, y, w, h));
        northEast = new QuadTree(new Rectangle2D.Double(x + w, y, w, h));
        southWest = new QuadTree(new Rectangle2D.Double(x, y + h, w, h));
        southEast = new QuadTree(new Rectangle2D.Double(x + w, y + h, w, h));
        
        divided = true;

        // Move current agents into new children
        for (Agent a : agents) {
            insertIntoChildren(a);
        }
        agents.clear();
    }

    public boolean insert(Agent agent) {
        if (!boundary.contains(agent.getX(), agent.getY())) {
            return false;
        }

        if (!divided && agents.size() < CAPACITY) {
            agents.add(agent);
            return true;
        }

        if (!divided) {
            subdivide();
        }

        return insertIntoChildren(agent);
    }

    private boolean insertIntoChildren(Agent a) {
        return northWest.insert(a) || northEast.insert(a) || 
               southWest.insert(a) || southEast.insert(a);
    }

    /**
     * Spatial Query: Find all agents within a specific circular range.
     * Theoretical use: Mosquito searching for humans or water tanks.
     */
    public List<Agent> query(Rectangle2D range, List<Agent> found) {
        if (found == null) found = new ArrayList<>();
        
        if (!boundary.intersects(range)) {
            return found;
        }

        if (divided) {
            northWest.query(range, found);
            northEast.query(range, found);
            southWest.query(range, found);
            southEast.query(range, found);
        } else {
            for (Agent a : agents) {
                if (range.contains(a.getX(), a.getY())) {
                    found.add(a);
                }
            }
        }
        return found;
    }

    public void clear() {
        agents.clear();
        if (divided) {
            northWest.clear();
            northEast.clear();
            southWest.clear();
            southEast.clear();
        }
        divided = false;
    }
}
