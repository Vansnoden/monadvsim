package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.Layer;
import com.monadvsim.app.models.utils.SimulationLogger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.awt.geom.Rectangle2D;
import java.util.*;

/**
 * Weighted sum of raster layers → suitability score, sampled via
 * inverse-CDF over a fixed grid.
 *
 * <p>Score at a cell:
 * <pre>
 *   score = floor + Σ_k  weight_k * min(cap_k, max(0, layer_k(x,y)))^power_k
 * </pre>
 * Then multiplied by a single multiplicative jitter drawn once per cell
 * at grid-build time, so different runs give different realizations but
 * each run is internally consistent (sampling is deterministic given
 * the CDF).
 *
 * <p>Cells outside {@code studyArea} (if provided) are hard-masked to 0.
 */
public class WeightedRasterSuitability implements HabitatSuitabilityScorer {

    /** Per-layer contribution spec, assembled by the caller from config. */
    public static class LayerSpec {
        public final Layer layer;
        public final double weight;
        public final double cap;
        public final double power;

        public LayerSpec(Layer layer, double weight, double cap, double power) {
            this.layer = layer;
            this.weight = weight;
            this.cap = Math.max(0.0, cap);
            this.power = power > 0 ? power : 1.0;
        }
    }

    // ---- configuration -----------------------------------------------------
    private final List<LayerSpec> specs;
    private final Rectangle2D bounds;
    private final Geometry studyArea;
    private final int gridX, gridY;
    private final double floorSuitability;
    private final double stochasticJitter;

    // ---- lazily-built sampling state --------------------------------------
    private double[][] gridScores;
    private double[] cdf;                 // length = gridX*gridY, monotone in [0,1]
    private double cellW, cellH;
    private final Object buildLock = new Object();
    private volatile boolean built = false;

    private final long jitterSeed;

    public WeightedRasterSuitability(List<LayerSpec> specs,
                                     Rectangle2D bounds,
                                     Geometry studyArea,
                                     int gridX, int gridY,
                                     double floorSuitability,
                                     double stochasticJitter,
                                     long jitterSeed) {
        if (specs == null) specs = Collections.emptyList();
        if (bounds == null || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            throw new IllegalArgumentException("WeightedRasterSuitability requires a positive bounds rectangle");
        }
        if (gridX < 4 || gridY < 4) {
            throw new IllegalArgumentException("Grid dimensions must be >= 4");
        }
        this.specs = new ArrayList<>(specs);
        this.bounds = bounds;
        this.studyArea = studyArea;
        this.gridX = gridX;
        this.gridY = gridY;
        this.floorSuitability = Math.max(1e-4, floorSuitability); // never truly zero
        this.stochasticJitter = Math.max(0.0, Math.min(0.9, stochasticJitter));
        this.jitterSeed = jitterSeed;
    }

    @Override
    public Rectangle2D getBounds() { return bounds; }

    @Override
    public double score(double x, double y) {
        // Direct evaluation (used for diagnostics / ad-hoc checks).
        if (studyArea != null) {
            Point p = new GeometryFactory().createPoint(new Coordinate(x, y));
            if (!studyArea.contains(p)) return 0.0;
        }
        double s = floorSuitability;
        for (LayerSpec spec : specs) {
            double v = safeValue(spec.layer, x, y);
            v = Math.min(v, spec.cap);
            v = Math.pow(v, spec.power);
            s += spec.weight * v;
        }
        return Math.max(0.0, s);
    }

    @Override
    public double[] sample(Random rng) {
        buildGridIfNeeded();
        double r = rng.nextDouble();
        int idx = Arrays.binarySearch(cdf, r);
        if (idx < 0) idx = -(idx + 1);
        if (idx >= cdf.length) idx = cdf.length - 1;

        int i = idx / gridY;
        int j = idx % gridY;

        double x = bounds.getMinX() + (i + rng.nextDouble()) * cellW;
        double y = bounds.getMinY() + (j + rng.nextDouble()) * cellH;
        return new double[]{x, y};
    }

    // -----------------------------------------------------------------------
    // Grid build
    // -----------------------------------------------------------------------
    private void buildGridIfNeeded() {
        if (built) return;
        synchronized (buildLock) {
            if (built) return;
            long t0 = System.currentTimeMillis();

            gridScores = new double[gridX][gridY];
            cellW = bounds.getWidth()  / gridX;
            cellH = bounds.getHeight() / gridY;

            GeometryFactory gf = new GeometryFactory();
            Random noise = new Random(jitterSeed);

            double total = 0.0;
            int zeroCells = 0;
            int maskedCells = 0;
            double minS = Double.POSITIVE_INFINITY;
            double maxS = 0.0;

            for (int i = 0; i < gridX; i++) {
                double cx = bounds.getMinX() + (i + 0.5) * cellW;
                for (int j = 0; j < gridY; j++) {
                    double cy = bounds.getMinY() + (j + 0.5) * cellH;

                    if (studyArea != null) {
                        Point p = gf.createPoint(new Coordinate(cx, cy));
                        if (!studyArea.contains(p)) {
                            gridScores[i][j] = 0.0;
                            maskedCells++;
                            continue;
                        }
                    }

                    double s = floorSuitability;
                    for (LayerSpec spec : specs) {
                        double v = safeValue(spec.layer, cx, cy);
                        v = Math.min(v, spec.cap);
                        v = Math.pow(v, spec.power);
                        s += spec.weight * v;
                    }

                    if (stochasticJitter > 0) {
                        double jitter = 1.0 + (noise.nextDouble() * 2.0 - 1.0) * stochasticJitter;
                        s *= jitter;
                    }

                    if (s < 0) s = 0;
                    gridScores[i][j] = s;
                    total += s;
                    if (s < minS) minS = s;
                    if (s > maxS) maxS = s;
                    if (s == 0.0) zeroCells++;
                }
            }

            if (total <= 0.0) {
                SimulationLogger.severe(
                    "[Suitability] Total score is zero — no cells are sampleable. "
                    + "Falling back to uniform across study area.");
                for (int i = 0; i < gridX; i++) {
                    for (int j = 0; j < gridY; j++) {
                        gridScores[i][j] = (gridScores[i][j] == 0.0 && maskedCells > 0) ? 0.0 : 1.0;
                        total += gridScores[i][j];
                    }
                }
                if (total <= 0) total = 1.0;
            }

            cdf = new double[gridX * gridY];
            double cum = 0.0;
            int idx = 0;
            for (int i = 0; i < gridX; i++) {
                for (int j = 0; j < gridY; j++) {
                    cum += gridScores[i][j];
                    cdf[idx++] = cum / total;
                }
            }

            built = true;
            long dt = System.currentTimeMillis() - t0;
            SimulationLogger.info(
                "[Suitability] Grid built: %dx%d cells in %d ms; "
                + "min=%.4f, max=%.4f, zeroCells=%d, maskedCells=%d",
                gridX, gridY, dt,
                Double.isFinite(minS) ? minS : 0.0, maxS, zeroCells, maskedCells);
        }
    }

    private static double safeValue(Layer layer, double x, double y) {
        if (layer == null) return 0.0;
        double v = layer.getValueAt(x, y);
        if (Double.isNaN(v) || v < 0) return 0.0;
        return v;
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("layers", specs.size());
        s.put("gridX", gridX);
        s.put("gridY", gridY);
        s.put("floorSuitability", floorSuitability);
        s.put("stochasticJitter", stochasticJitter);
        s.put("built", built);
        if (built && gridScores != null) {
            double min = Double.POSITIVE_INFINITY, max = 0, sum = 0;
            int zero = 0;
            for (double[] row : gridScores) {
                for (double v : row) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                    sum += v;
                    if (v == 0) zero++;
                }
            }
            s.put("minScore", min);
            s.put("maxScore", max);
            s.put("meanScore", sum / (gridX * (double) gridY));
            s.put("zeroCells", zero);
        }
        return s;
    }
}