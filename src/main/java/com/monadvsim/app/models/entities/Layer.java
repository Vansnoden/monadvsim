package com.monadvsim.app.models.entities;


import java.io.Serializable;
import java.util.UUID;

public abstract class Layer implements Serializable {
    
    private final String id;
    private String name;
    private boolean visible = true;
    protected String filePath;

    
    public Layer(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
    }

    public String getId() { return id; }
    
    
    public String getName() { return name; }
    
    
    public void setName(String name) { this.name = name; }
    
    
    public boolean isVisible() { return visible; }
    
    
    public void setVisible(boolean visible) { this.visible = visible; }

    
    public String getFilePath() { return filePath; }
    
    
    public void setFilePath(String path) { this.filePath = path; }

    
    public abstract void update(Project project);
   
    
    public abstract double getValueAt(double x, double y);

    
    @Override
    public String toString() {
        return name + (visible ? "" : " (Hidden)");
    }
}
