package com.monadvsim.app.models.entities;

import java.io.Serializable;

public class RasterLayer extends Layer implements Serializable {
    private double[][][] dataGrid; // [frame][x][y]
    private int width, height, frames;
    private int activeFrame = 0;
    private double minLon, maxLon, minLat, maxLat;

    public RasterLayer(String name, int width, int height, int frames) {
        super(name);
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.dataGrid = new double[frames][width][height];
    }
    
    /**
     * Re-allocates the grid and sets bounds. Used when loading real GIS files.
     */
    public void initialize(int width, int height, int frames) {
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.dataGrid = new double[frames][width][height];
    }

    public void setBounds(double minLon, double maxLon, double minLat, double maxLat) {
        this.minLon = minLon;
        this.maxLon = maxLon;
        this.minLat = minLat;
        this.maxLat = maxLat;
    }

    /**
     * Maps geographic Lon/Lat to the internal grid index.
     */
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
        
        return dataGrid[activeFrame][x][y];
    }

    public void setData(int frame, int x, int y, double value) {
        if (frame < frames && x < width && y < height) {
            dataGrid[frame][x][y] = value;
        }
    }

    public void setActiveFrame(int index) {
        this.activeFrame = Math.min(index, frames - 1);
    }
    
    public int getActiveFrame(){
        return this.activeFrame;
    }

    @Override
    public void update(Project project) {
        
    }

    public double[][][] getDataGrid() {
        return dataGrid;
    }

    public void setDataGrid(double[][][] dataGrid) {
        this.dataGrid = dataGrid;
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
    
    
}