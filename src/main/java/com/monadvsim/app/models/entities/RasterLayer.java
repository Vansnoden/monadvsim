package com.monadvsim.app.models.entities;


import java.io.Serializable;

public class RasterLayer extends Layer implements Serializable {
    // The data grid: [time_frame][x_coordinate][y_coordinate]
    private double[][][] dataGrid;
    private int currentFrame = 0;
    
    // Geographical bounds for coordinate mapping
    private double minX, maxX, minY, maxY;
    private int width, height;

    public RasterLayer(String name, int width, int height, int timeFrames) {
        super(name);
        this.width = width;
        this.height = height;
        this.dataGrid = new double[timeFrames][width][height];
    }

    /**
     * Samples the value at a world coordinate.
     * This is the "Sensing" mechanism for mosquitoes.
     */
    public double getValueAt(double worldX, double worldY) {
        int gridX = worldToGridX(worldX);
        int gridY = worldToGridY(worldY);

        if (gridX >= 0 && gridX < width && gridY >= 0 && gridY < height) {
            return dataGrid[currentFrame][gridX][gridY];
        }
        return 0.0;
    }

    private int worldToGridX(double x) {
        return (int) ((x - minX) / (maxX - minX) * width);
    }

    private int worldToGridY(double y) {
        return (int) ((y - minY) / (maxY - minY) * height);
    }

    public void setActiveFrame(int frameIndex) {
        if (frameIndex >= 0 && frameIndex < dataGrid.length) {
            this.currentFrame = frameIndex;
        }
    }

    public void setData(int frame, int x, int y, double value) {
        this.dataGrid[frame][x][y] = value;
    }

    public void setBounds(double minX, double maxX, double minY, double maxY) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override
    public void update(long tick, double deltaT) {
        // TimeManager usually calls setActiveFrame, but logic can be added here
    }
}