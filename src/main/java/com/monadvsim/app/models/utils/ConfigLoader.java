package com.monadvsim.app.models.utils;
import com.monadvsim.app.models.utils.SimulationLogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.monadvsim.app.models.config.SimulationConfig;
import java.io.IOException;
import java.io.InputStream;

public class ConfigLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public static SimulationConfig loadFromYaml(String resourcePath) throws IOException {
        try (InputStream is = ConfigLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Config file not found: " + resourcePath);
            }
            return YAML_MAPPER.readValue(is, SimulationConfig.class);
        }
    }
}