package com.houqing.tools.video_compressor_backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** SQLite and multipart temp need their directories to exist before the context starts. */
public class DataDirEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String dir = env.getProperty("app.data-dir", "./data");
        try {
            Path root = Path.of(dir).toAbsolutePath().normalize();
            for (String sub : new String[]{"", "tmp", "storage", "work", "logs"}) {
                Files.createDirectories(root.resolve(sub));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory " + dir, e);
        }
    }
}
