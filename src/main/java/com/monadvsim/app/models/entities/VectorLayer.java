package com.monadvsim.app.models.entities;


import java.awt.Shape;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

public class VectorLayer extends Layer {
    // List of geometries (Boundaries, Buildings, etc.)
    private List<Shape> geometries;

    public VectorLayer(String name) {
        super(name);
        this.geometries = new ArrayList<>();
    }

    /**
     * Spatial Check: Is a specific coordinate inside any of our shapes?
     * Useful for checking "Are you inside a building?" or "Are you out of bounds?"
     */
    public boolean contains(double x, double y) {
        for (Shape s : geometries) {
            if (s.contains(x, y)) return true;
        }
        return false;
    }

    public void addGeometry(Shape shape) {
        this.geometries.add(shape);
    }

    public List<Shape> getGeometries() {
        return geometries;
    }

    @Override
    public void update(long tick, double deltaT) {
        // Usually static, but can be used for "moving" boundaries if needed.
    }
}