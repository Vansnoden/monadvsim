package com.monadvsim.app.models.utils;

import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.geometry.jts.ReferencedEnvelope;

import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Utility to read the geographic extent (bounding box) of a Shapefile.
 * Assumes the shapefile is in WGS84 (EPSG:4326). If not, reproject it first.
 */
public class VectorBoundsLoader {

    public static Rectangle2D getBoundsFromShapefile(String shapefilePath) throws IOException {
        File file = new File(shapefilePath);
        if (!file.exists()) {
            throw new IOException("Shapefile not found: " + shapefilePath);
        }

        ShapefileDataStore store = null;
        try {
            URL url = file.toURI().toURL();
            store = new ShapefileDataStore(url);
            store.setCharset(StandardCharsets.UTF_8);
            SimpleFeatureSource featureSource = store.getFeatureSource();
            ReferencedEnvelope envelope = featureSource.getBounds();

            // Optional: add a small buffer (approx 100 m)
            double buffer = 0.001;
            double minX = envelope.getMinX() - buffer;
            double minY = envelope.getMinY() - buffer;
            double width = envelope.getWidth() + 2 * buffer;
            double height = envelope.getHeight() + 2 * buffer;

            return new Rectangle2D.Double(minX, minY, width, height);

        } catch (Exception e) {
            throw new IOException("Failed to read shapefile: " + e.getMessage(), e);
        } finally {
            if (store != null) store.dispose();
        }
    }

    public static Rectangle2D createFallbackBounds(double centerLon, double centerLat, double radiusKm) {
        double degPerKm = 1.0 / 111.32;
        double bufferDeg = radiusKm * degPerKm;
        return new Rectangle2D.Double(centerLon - bufferDeg, centerLat - bufferDeg,
                2 * bufferDeg, 2 * bufferDeg);
    }
}