package com.monadvsim.app.models.engine;

import java.awt.geom.Rectangle2D;
import java.util.Map;
import java.util.Random;

/**
 * Pluggable scoring function for spatially seeding agents.
 *
 * <p>Implementations return a non-negative suitability score for any
 * position inside {@link #getBounds()}, and provide a weighted sampler
 * that draws positions proportional to suitability. This keeps the
 * seeding logic decoupled from the underlying signals (population
 * density, building density, elevation, land cover, ...) so a new
 * signal can be added by dropping in a new implementation without
 * touching {@code App} or the seeding loops.
 */
public interface HabitatSuitabilityScorer {

    /**
     * Suitability at (x, y). Must be {@code >= 0}. A value of 0 means the
     * position is entirely excluded from sampling.
     */
    double score(double x, double y);

    /**
     * Draw a position weighted by {@link #score(double, double)}. The
     * returned array is {@code {x, y}}. Implementations may cache an
     * internal cumulative distribution for speed.
     */
    double[] sample(Random rng);

    /** Domain of the scorer. Sampling never leaves this rectangle. */
    Rectangle2D getBounds();

    /**
     * Optional diagnostics for logging: min / max / mean score,
     * fraction of zero cells, etc. Never null.
     */
    Map<String, Object> getStats();
}