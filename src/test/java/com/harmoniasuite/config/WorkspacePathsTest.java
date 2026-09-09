package com.harmoniasuite.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspacePathsTest {

    @Test
    @DisplayName("absolute workspace root is normalized without changing its meaning")
    void resolvesAbsoluteRoot(@TempDir Path dir) {
        Path raw = dir.resolve("workspace").resolve("..").resolve("workspace");
        assertEquals(dir.resolve("workspace").toAbsolutePath().normalize(), WorkspacePaths.resolveRoot(raw.toString()));
    }

    @Test
    @DisplayName("command line workspace overrides the system property")
    void commandLineWorkspaceHasPriority() {
        String previous = System.getProperty("harmonia.workspace");
        try {
            System.setProperty("harmonia.workspace", "system-property-workspace");
            assertEquals("command-line-workspace",
                    WorkspacePaths.configuredWorkspace(new String[]{"--harmonia.workspace=command-line-workspace"}));
            assertEquals("system-property-workspace", WorkspacePaths.configuredWorkspace(new String[0]));
        } finally {
            if (previous == null) {
                System.clearProperty("harmonia.workspace");
            } else {
                System.setProperty("harmonia.workspace", previous);
            }
        }
    }

    @Test
    @DisplayName("Spring environment keeps command line system and environment priority")
    void springEnvironmentWorkspaceHasPriority() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("environment",
                Map.of("harmonia.workspace", "environment-workspace")));
        environment.getPropertySources().addFirst(new MapPropertySource("system",
                Map.of("harmonia.workspace", "system-workspace")));
        environment.getPropertySources().addFirst(new MapPropertySource("command-line",
                Map.of("harmonia.workspace", "command-line-workspace")));

        assertEquals("command-line-workspace", WorkspacePaths.configuredWorkspace(environment));
        environment.getPropertySources().remove("command-line");
        assertEquals("system-workspace", WorkspacePaths.configuredWorkspace(environment));
        environment.getPropertySources().remove("system");
        assertEquals("environment-workspace", WorkspacePaths.configuredWorkspace(environment));
    }
}