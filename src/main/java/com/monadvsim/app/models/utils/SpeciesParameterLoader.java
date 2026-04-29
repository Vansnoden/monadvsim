package com.monadvsim.app.models.utils;
import com.monadvsim.app.models.utils.SimulationLogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monadvsim.app.models.engine.SpeciesParameters;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Utility class for loading species parameters from JSON files or streams.
 */
public class SpeciesParameterLoader {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Load parameters from a file on the filesystem.
     *
     * @param filePath path to the JSON file
     * @return populated SpeciesParameters object
     * @throws IOException if file not found or parsing fails
     */
    public static SpeciesParameters loadFromFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Species parameters file not found: " + filePath);
        }
        return mapper.readValue(file, SpeciesParameters.class);
    }

    /**
     * Load parameters from an InputStream (e.g., from classpath resource).
     *
     * @param inputStream stream containing the JSON data
     * @return populated SpeciesParameters object
     * @throws IOException if parsing fails
     */
    public static SpeciesParameters loadFromStream(InputStream inputStream) throws IOException {
        return mapper.readValue(inputStream, SpeciesParameters.class);
    }

    /**
     * Returns a default instance with hard‑coded values (useful as fallback).
     *
     * @return a new SpeciesParameters object with default coefficients
     */
    public static SpeciesParameters loadDefault() {
        return new SpeciesParameters();
    }
}