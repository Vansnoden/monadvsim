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

    @Override
    public void update(long tick, double deltaT) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }
}