package com.monadvsim.app.models;

import org.locationtech.jts.geom.Geometry;
import java.io.Serializable;

public class CachedFeature implements Serializable {
    private final Geometry geometry;
    private final String terrainLabel;
    private final double value; // Useful if the rule is based on Raster values (e.g. slope)

    public CachedFeature(Geometry geometry, String terrainLabel, double value) {
        this.geometry = geometry;
        this.terrainLabel = terrainLabel;
        this.value = value;
    }

    public Geometry getGeometry() { return geometry; }
    public String getTerrainLabel() { return terrainLabel; }
    public double getValue() { return value; }
}
