package com.harmoniasuite.config;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class WorkspacePaths {

    private final Path root;

    public WorkspacePaths(HarmoniaProperties properties) {
        String configured = properties.getWorkspace();
        if (".".equals(configured) && !isDev() && System.getenv("APPDATA") != null) {
            this.root = Paths.get(System.getenv("APPDATA"), "HarmoniaSuite").toAbsolutePath().normalize();
            try {
                Files.createDirectories(root);
            } catch (Exception ignored) {
            }
        } else {
            this.root = Paths.get(configured).toAbsolutePath().normalize();
        }
    }

    private static boolean isDev() {
        return System.getProperty("java.class.path", "").contains("target/classes");
    }

    public Path root() {
        return root;
    }

    public Path resolve(String value) {
        Path path = Paths.get(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return root.resolve(path).normalize();
    }
}
