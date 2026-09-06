package com.harmoniasuite.config;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class WorkspacePaths {

    private final Path root;

    public WorkspacePaths(HarmoniaProperties properties) {
        this.root = Paths.get(properties.getWorkspace()).toAbsolutePath().normalize();
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
