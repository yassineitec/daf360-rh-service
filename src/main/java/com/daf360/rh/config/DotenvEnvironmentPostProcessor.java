package com.daf360.rh.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads a .env file from the working directory into Spring's Environment.
 * Runs before any YAML/properties files are processed, so ${VAR} placeholders resolve correctly.
 * The .env file is optional — if absent, defaults in application.yml take effect.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "dotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        File envFile = resolveEnvFile();
        if (envFile == null || !envFile.exists()) return;

        Map<String, Object> props = parse(envFile);
        if (props.isEmpty()) return;

        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
    }

    private File resolveEnvFile() {
        // 1. Working directory (mvn spring-boot:run from project root)
        File cwd = new File(System.getProperty("user.dir"), ".env");
        if (cwd.exists()) return cwd;
        // 2. One level up (in case launched from target/)
        File parent = new File(System.getProperty("user.dir")).getParentFile();
        if (parent != null) {
            File up = new File(parent, ".env");
            if (up.exists()) return up;
        }
        return cwd; // let caller check exists()
    }

    private Map<String, Object> parse(File file) {
        Map<String, Object> props = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key   = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                // Strip surrounding quotes
                if (value.length() >= 2) {
                    char first = value.charAt(0), last = value.charAt(value.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\''))
                        value = value.substring(1, value.length() - 1);
                }
                props.put(key, value);
            }
        } catch (IOException e) {
            // Silently skip — .env is optional
        }
        return props;
    }
}
