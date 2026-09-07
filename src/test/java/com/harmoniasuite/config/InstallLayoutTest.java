package com.harmoniasuite.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InstallLayoutTest {

    @Test
    @DisplayName("jar parent is the first jar entry of the classpath")
    void jarParentPicksFirstJar(@TempDir Path cwd, @TempDir Path app) throws Exception {
        Files.createFile(app.resolve("harmonia-suite.jar"));
        String cp = app.resolve("other").toString() + File.pathSeparator
                + app.resolve("harmonia-suite.jar").toString();
        assertEquals(app, InstallLayout.jarParent(cp, cwd));
    }

    @Test
    @DisplayName("no jar entries means no bases")
    void noJarNoBase(@TempDir Path cwd) {
        assertNull(InstallLayout.jarParent("target/classes", cwd));
        assertNull(InstallLayout.jarParent("", cwd));
        assertNull(InstallLayout.jarParent(null, cwd));
    }

    @Test
    @DisplayName("relative jar resolves from cwd")
    void relativeJarFromCwd(@TempDir Path cwd) throws Exception {
        Path app = cwd.resolve("app");
        Files.createDirectories(app);
        Files.createFile(app.resolve("harmonia-suite.jar"));
        assertEquals(app, InstallLayout.jarParent("app/harmonia-suite.jar", cwd));
    }
}
