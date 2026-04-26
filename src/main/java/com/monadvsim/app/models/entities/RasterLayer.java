package com.monadvsim.app.models.entities;

import com.monadvsim.app.models.utils.SimulationLogger;

import java.io.Serializable;

/**
 * Static Raster Data
 * Stores grid-based environmental data (elevation, population, buildings).
 * Supports multi-frame temporal data.
 * Provides coordinate-to-grid value lookup with basic spatial interpolation.
 */
public class RasterLayer extends Layer implements Serializable {

    private double[][][] dataGrid;   // [frame][x][y]
    private int width, height, frames;
    private int activeFrame = 0;
    private double minLon, maxLon, minLat, maxLat;

    /**
     * Constructor that does NOT allocate the dataGrid.
     * Used by subclasses that manage their own storage (e.g., memory‑mapped).
     */
    protected RasterLayer(String name) {
        super(name);
        this.width = 0;
        this.height = 0;
        this.frames = 0;
        this.dataGrid = null;
    }

    /**
     * Standard constructor – allocates the full 3D array on the heap.
     */
    public RasterLayer(String name, int width, int height, int frames) {
        this(name);
        initialize(width, height, frames);
    }

    /**
     * Initialises or re‑initialises the raster with the given dimensions.
     * Allocates a new dataGrid.
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

//    @Override
//    public double getValueAt(double lon, double lat) {
//        if (dataGrid == null) return Double.NaN;
//        if (maxLon == minLon || maxLat == minLat) return 0.0;
//
//        double xFrac = (lon - minLon) / (maxLon - minLon);
//        double yFrac = (lat - minLat) / (maxLat - minLat);
//
//        int x = (int) (xFrac * (width - 1));
//        int y = (int) (yFrac * (height - 1));
//
//        if (x < 0 || x >= width || y < 0 || y >= height) return 0.0;
//        return dataGrid[activeFrame][x][y];
//    }
    @Override
    public double getValueAt(double lon, double lat) {
        if (dataGrid == null) return 0.0; // treat missing grid as zero
        if (maxLon == minLon || maxLat == minLat) return 0.0;

        double xFrac = (lon - minLon) / (maxLon - minLon);
        double yFrac = (lat - minLat) / (maxLat - minLat);

        int x = (int) (xFrac * (width - 1));
        int y = (int) (yFrac * (height - 1));

        if (x < 0 || x >= width || y < 0 || y >= height) return 0.0;
        double value = dataGrid[activeFrame][x][y];
        // Treat NaN as 0.0
        return Double.isNaN(value) ? 0.0 : value;
    }

    @Override
    public void update(Project project) {
        // Nothing to update by default
    }

    public void setData(int frame, int x, int y, double value) {
        if (dataGrid == null) return;
        if (frame < frames && x < width && y < height) {
            dataGrid[frame][x][y] = value;
        }
    }

    public void setActiveFrame(int index) {
        this.activeFrame = Math.min(index, frames - 1);
    }

    public int getActiveFrame() {
        return activeFrame;
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