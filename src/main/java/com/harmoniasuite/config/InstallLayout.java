package com.harmoniasuite.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public final class InstallLayout {

    private InstallLayout() {
    }

    public static Path appDir() {
        if (System.getProperty("java.class.path", "").contains("target/classes")) {
            return null;
        }
        Path fromClasspath = jarParent(System.getProperty("java.class.path", ""),
                Paths.get(System.getProperty("user.dir")));
        if (fromClasspath != null) {
            return fromClasspath;
        }
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            try {
                Path exe = Paths.get(appPath);
                Path dir = Files.isDirectory(exe) ? exe : exe.getParent();
                if (dir != null) {
                    Path app = dir.resolve("app");
                    if (Files.isDirectory(app)) {
                        return app;
                    }
                    if (Files.isDirectory(dir)) {
                        return dir;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        try {
            var location = InstallLayout.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            if ("file".equalsIgnoreCase(location.getScheme())) {
                Path path = Paths.get(location);
                Path dir = Files.isDirectory(path) ? path : path.getParent();
                if (dir != null) {
                    return dir;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static Path jarParent(String classPath, Path cwd) {
        if (classPath == null || classPath.isBlank()) {
            return null;
        }
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Paths.get(entry.strip());
                if (!path.isAbsolute()) {
                    path = cwd.resolve(path).normalize();
                }
                Path fileName = path.getFileName();
                if (fileName != null && fileName.toString().toLowerCase().endsWith(".jar")
                        && path.getParent() != null) {
                    return path.getParent();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
