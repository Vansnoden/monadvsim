package com.monadvsim.app.models.entities;


import java.io.Serializable;
import java.util.UUID;

public abstract class Layer implements Serializable {
    private final String id;
    private String name;
    private boolean visible = true;
    
    // Crucial for .mvsim persistence: stores where the raw data lives
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

    /**
     * The 'Update' hook.
     * In headless mode, this is called by the SimulationEngine every tick.
     * RasterLayers might use this to swap time-series frames.
     * AgentLayers use this to iterate through mosquito logic.
     */
    public abstract void update(Project project);

    @Override
    public String toString() {
        return name + (visible ? "" : " (Hidden)");
    }
}
