package com.monadvsim.app.models.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.monadvsim.app.models.config.SimulationConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class ConfigLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * Load configuration from the given path.
     * Automatically detects if it's a classpath resource or file system path.
     * 
     * @param path - Path to the config file
     * @return SimulationConfig object
     * @throws IOException if the file cannot be loaded
     */
    public static SimulationConfig loadConfig(String path) throws IOException {
        // Try classpath first (if path starts with "/")
        if (path.startsWith("/")) {
            try (InputStream is = ConfigLoader.class.getResourceAsStream(path)) {
                if (is != null) {
                    return YAML_MAPPER.readValue(is, SimulationConfig.class);
                }
            }
        }
        
        // Try classpath with leading "/" added
        if (!path.startsWith("/")) {
            try (InputStream is = ConfigLoader.class.getResourceAsStream("/" + path)) {
                if (is != null) {
                    return YAML_MAPPER.readValue(is, SimulationConfig.class);
                }
            } catch (IOException e) {
                // Continue to file system
            }
        }
        
        // Try file system
        File file = new File(path);
        if (file.exists()) {
            return YAML_MAPPER.readValue(file, SimulationConfig.class);
        }
        
        // Try with "config/" prefix
        File configFile = new File("config/" + path);
        if (configFile.exists()) {
            return YAML_MAPPER.readValue(configFile, SimulationConfig.class);
        }
        
        throw new IOException("Config file not found: " + path);
    }

    /**
     * Load configuration from classpath resource (legacy method).
     */
    public static SimulationConfig loadFromYaml(String resourcePath) throws IOException {
        try (InputStream is = ConfigLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Config file not found: " + resourcePath);
            }
            return YAML_MAPPER.readValue(is, SimulationConfig.class);
        }
    }
    
    /**
     * Load configuration from file system path.
     */
    public static SimulationConfig loadFromFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Config file not found: " + filePath);
        }
        return YAML_MAPPER.readValue(file, SimulationConfig.class);
    }
}