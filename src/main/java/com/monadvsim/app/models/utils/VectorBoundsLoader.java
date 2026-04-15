package com.monadvsim.app.models.utils;

import org.geotools.api.data.FileDataStore;
import org.geotools.api.data.FileDataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
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
import org.geotools.api.geometry.MismatchedDimensionException;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;

/**
 * Utility to read the geographic extent (bounding box) and geometry of a Shapefile.
 * Automatically reprojects the geometry to EPSG:4326 (WGS84) if needed.
 */
public class VectorBoundsLoader {

    private static final String TARGET_CRS = "EPSG:4326";

    /**
     * Loads the bounding envelope from a shapefile, transformed to EPSG:4326.
     */
    public static Rectangle2D getBoundsFromShapefile(String shapefilePath) throws IOException, FactoryException, TransformException {
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
            CoordinateReferenceSystem sourceCRS = envelope.getCoordinateReferenceSystem();
            CoordinateReferenceSystem targetCRS = CRS.decode(TARGET_CRS, true);

            System.out.println("Source CRS: " + sourceCRS.getName());
            System.out.println("Target CRS: " + targetCRS.getName());

            // Transform envelope to WGS84 if needed
            if (sourceCRS != null && !CRS.equalsIgnoreMetadata(sourceCRS, targetCRS)) {
                envelope = envelope.transform(targetCRS, true);
                System.out.println("Transformed envelope: " + envelope);
            } else {
                System.out.println("No transformation needed (already WGS84).");
            }

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
     * Reads all geometries from a shapefile and returns their union, transformed to EPSG:4326.
     */
    public static Geometry getStudyAreaGeometry(String shapefilePath) throws IOException, FactoryException, TransformException {
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
            CoordinateReferenceSystem sourceCRS = featureSource.getSchema().getCoordinateReferenceSystem();
            CoordinateReferenceSystem targetCRS = CRS.decode(TARGET_CRS, true);

            MathTransform transform = null;
            if (sourceCRS != null && !CRS.equalsIgnoreMetadata(sourceCRS, targetCRS)) {
                transform = CRS.findMathTransform(sourceCRS, targetCRS, true);
                System.out.println("Geometry will be transformed from " + sourceCRS.getName() + " to " + targetCRS.getName());
            }

            List<Geometry> geoms = new ArrayList<>();
            try (SimpleFeatureIterator iterator = features.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    Geometry geom = (Geometry) feature.getDefaultGeometry();
                    if (geom != null) {
                        if (transform != null) {
                            geom = org.geotools.geometry.jts.JTS.transform(geom, transform);
                        }
                        geoms.add(geom);
                    }
                }
            }

            if (geoms.isEmpty()) {
                throw new IOException("No geometry found in shapefile");
            }
            // Union all geometries
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