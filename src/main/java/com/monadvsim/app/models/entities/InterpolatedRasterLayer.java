package com.monadvsim.app.models.entities;


import com.monadvsim.app.models.engine.TimeManager;


public class InterpolatedRasterLayer extends Layer{
    
    private double[][][] startDataGrid; // [frame][x][y]
    private double[][][] endDataGrid; // [frame][x][y]
    private int width, height, frames;
    private int activeFrame = 0;
    private double minLon, maxLon, minLat, maxLat;
    private final TimeManager timeManager;
    private final long totalSimulationTicks;

    
    public InterpolatedRasterLayer(String name, 
            int width, int height, int frames,
            TimeManager timeManager, long totalTicks) {
        super(name);
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.timeManager = timeManager;
        this.startDataGrid = new double[frames][width][height];
        this.endDataGrid = new double[frames][width][height];
        this.totalSimulationTicks = totalTicks;
    }

    
    public void setBounds(double minLon, double maxLon,
            double minLat, double maxLat) {
        this.minLon = minLon;
        this.maxLon = maxLon;
        this.minLat = minLat;
        this.maxLat = maxLat;
    }

    
    @Override
    public double getValueAt(double lon, double lat) {
        if (maxLon == minLon || maxLat == minLat) return 0.0;
        
        // Transform Lon/Lat to 0.0 - 1.0 range
        double xFrac = (lon - minLon) / (maxLon - minLon);
        double yFrac = (lat - minLat) / (maxLat - minLat);

        // Map to array indices
        int x = (int) (xFrac * (width - 1));
        int y = (int) (yFrac * (height - 1));

        // Boundary safety
        if (x < 0 || x >= width || y < 0 || y >= height) return 0.0;
        
        // Get the current progress of time (0.0 to 1.0)
        double progress = (double) timeManager.getTickCount()/ totalSimulationTicks;
        
        // Clamp progress to [0, 1] to prevent extrapolation errors
        progress = Math.min(1.0, Math.max(0.0, progress));
        
        double initialValue = startDataGrid[activeFrame][x][y];
        double endValue = endDataGrid[activeFrame][x][y];
        
        // Linear interpolation formula: start + (diff * progress)
        double interpolatedValue = initialValue + (endValue - initialValue) * progress;
        
        return interpolatedValue;
    }
    
    
    public void setActiveFrame(int index) {
        this.activeFrame = Math.min(index, frames - 1);
    }

    
    public int getWidth() {
        return width;
    }

    
    public void setWidth(int width) {
        this.width = width;
    }

    
    public int getHeight() {
        return height;
    }

    
    public void setHeight(int height) {
        this.height = height;
    }

    
    public int getFrames() {
        return frames;
    }

    
    public void setFrames(int frames) {
        this.frames = frames;
    }

    
    public double getMinLon() {
        return minLon;
    }

    
    public void setMinLon(double minLon) {
        this.minLon = minLon;
    }

    
    public double getMaxLon() {
        return maxLon;
    }

    
    public void setMaxLon(double maxLon) {
        this.maxLon = maxLon;
    }

    
    public double getMinLat() {
        return minLat;
    }

    
    public void setMinLat(double minLat) {
        this.minLat = minLat;
    }

    
    public double getMaxLat() {
        return maxLat;
    }

    
    public void setMaxLat(double maxLat) {
        this.maxLat = maxLat;
    }

    @Override
    public void update(Project project) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }


}
