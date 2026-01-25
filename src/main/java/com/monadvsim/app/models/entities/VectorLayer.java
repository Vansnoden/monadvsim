package com.monadvsim.app.models.entities;


import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;


public class VectorLayer extends Layer {
    
    private List<Shape> geometries;

    
    public VectorLayer(String name) {
        super(name);
        this.geometries = new ArrayList<>();
    }

    
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
    public void update(Project project) {
        // usually static so do not change between ticks
    }

    
    @Override
    public double getValueAt(double x, double y) {
        return 0.0;
    }
}