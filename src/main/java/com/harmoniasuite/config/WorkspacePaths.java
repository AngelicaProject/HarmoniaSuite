package com.harmoniasuite.config;

import org.springframework.stereotype.Component;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class WorkspacePaths {

    private final Path root;

    public WorkspacePaths(HarmoniaProperties properties) {
        this.root = resolveRoot(properties.getWorkspace());
    }

    public static Path resolveRoot(String configured) {
        String value = configured == null || configured.isBlank() ? "." : configured;
        if (".".equals(value) && !isDev() && System.getenv("APPDATA") != null) {
            Path installed = Paths.get(System.getenv("APPDATA"), "HarmoniaSuite").toAbsolutePath().normalize();
            try {
                Files.createDirectories(installed);
            } catch (Exception ignored) {
            }
            return installed;
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    public static String configuredWorkspace(String[] args) {
        String configured = System.getProperty("harmonia.workspace");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("HARMONIA_WORKSPACE");
        }
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--harmonia.workspace=")) {
                    return arg.substring("--harmonia.workspace=".length());
                }
                if ("--harmonia.workspace".equals(arg) && i + 1 < args.length) {
                    return args[i + 1];
                }
            }
        }
        return configured == null || configured.isBlank() ? "." : configured;
    }

    public static String configuredWorkspace(ConfigurableEnvironment environment) {
        String configured = environment.getProperty("harmonia.workspace");
        return configured == null || configured.isBlank() ? "." : configured;
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
