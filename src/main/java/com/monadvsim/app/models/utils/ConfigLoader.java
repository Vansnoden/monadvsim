package com.monadvsim.app.models.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.monadvsim.app.models.config.SimulationConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class ConfigLoader {

    /**
     * YAML mapper used for all config loading.
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled on purpose: YAML
     * configs frequently carry fields that a given code revision does not
     * yet know about (or has retired). Strict failure means the whole run
     * silently falls back to hard-coded defaults, which is much harder to
     * debug than a warning. Unknown keys are logged and ignored.
     */
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static SimulationConfig loadConfig(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IOException("Config path is null or empty");
        }

        // 1. Classpath, exact path
        if (path.startsWith("/")) {
            try (InputStream is = ConfigLoader.class.getResourceAsStream(path)) {
                if (is != null) {
                    SimulationLogger.info("[ConfigLoader] Loaded from classpath: %s", path);
                    return YAML_MAPPER.readValue(is, SimulationConfig.class);
                }
            }
        }

        // 2. Classpath, leading "/" added
        if (!path.startsWith("/")) {
            try (InputStream is = ConfigLoader.class.getResourceAsStream("/" + path)) {
                if (is != null) {
                    SimulationLogger.info("[ConfigLoader] Loaded from classpath: /%s", path);
                    return YAML_MAPPER.readValue(is, SimulationConfig.class);
                }
            } catch (IOException e) {
                // fall through
            }
        }

        // 3. File system, exact path
        File file = new File(path);
        if (file.exists()) {
            SimulationLogger.info("[ConfigLoader] Loaded from file: %s", file.getAbsolutePath());
            return YAML_MAPPER.readValue(file, SimulationConfig.class);
        }

        // 4. File system, "config/" prefix
        File prefixed = new File("config/" + path);
        if (prefixed.exists()) {
            SimulationLogger.info("[ConfigLoader] Loaded from file: %s", prefixed.getAbsolutePath());
            return YAML_MAPPER.readValue(prefixed, SimulationConfig.class);
        }

        throw new IOException("Config file not found: " + path);
    }

    /** Legacy classpath-only loader. */
    public static SimulationConfig loadFromYaml(String resourcePath) throws IOException {
        try (InputStream is = ConfigLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Config not found on classpath: " + resourcePath);
            return YAML_MAPPER.readValue(is, SimulationConfig.class);
        }
    }

    /** Legacy file-system-only loader. */
    public static SimulationConfig loadFromFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) throw new IOException("Config file not found: " + filePath);
        return YAML_MAPPER.readValue(file, SimulationConfig.class);
    }
}