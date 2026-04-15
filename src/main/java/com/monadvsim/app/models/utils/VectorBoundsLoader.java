package com.monadvsim.app.models.utils;

import org.geotools.api.data.FileDataStore;
import org.geotools.api.data.FileDataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.geotools.api.feature.simple.SimpleFeature;

/**
 * Utility to read the geographic extent (bounding box) and geometry of a Shapefile.
 * Assumes the shapefile is in WGS84 (EPSG:4326). If not, reproject it first.
 */
public class VectorBoundsLoader {

    /**
     * Loads the bounding envelope from a shapefile.
     *
     * @param shapefilePath path to .shp file
     * @return Rectangle2D in longitude/latitude (x=lon, y=lat)
     * @throws IOException if file not found or cannot be read
     */
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

    /**
     * Fallback method that creates a world bounds from a centroid and radius (km).
     */
    public static Rectangle2D createFallbackBounds(double centerLon, double centerLat, double radiusKm) {
        double degPerKm = 1.0 / 111.32;
        double bufferDeg = radiusKm * degPerKm;
        return new Rectangle2D.Double(centerLon - bufferDeg, centerLat - bufferDeg,
                2 * bufferDeg, 2 * bufferDeg);
    }

    /**
     * Reads the first feature's geometry (or union of all features) from a shapefile.
     * Returns a Geometry (usually a MultiPolygon) representing the entire study area.
     *
     * @param shapefilePath path to .shp file
     * @return union of all geometries in the shapefile
     * @throws IOException if file not found or cannot be read
     */
    public static Geometry getStudyAreaGeometry(String shapefilePath) throws IOException {
        File file = new File(shapefilePath);
        if (!file.exists()) {
            throw new IOException("Shapefile not found: " + shapefilePath);
        }
        FileDataStore store = FileDataStoreFinder.getDataStore(file);
        if (store == null) {
            throw new IOException("No data store found for: " + shapefilePath);
        }
        try {
            SimpleFeatureSource featureSource = store.getFeatureSource();
            SimpleFeatureCollection features = featureSource.getFeatures();
            List<Geometry> geoms = new ArrayList<>();

            // Use FeatureIterator (AutoCloseable) to iterate safely
            try (SimpleFeatureIterator iterator = features.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Geometry geom = (Geometry) feature.getDefaultGeometry();
                    if (geom != null) {
                        geoms.add(geom);
                    }
                }
            }

            if (geoms.isEmpty()) {
                throw new IOException("No geometry found in shapefile");
            }
            // Union all geometries (if multiple polygons)
            GeometryFactory factory = new GeometryFactory();
            Geometry union = geoms.get(0);
            for (int i = 1; i < geoms.size(); i++) {
                union = union.union(geoms.get(i));
            }
            return union;
        } finally {
            store.dispose();
        }
    }
}