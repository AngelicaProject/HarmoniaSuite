package com.harmoniasuite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HarmoniaSuiteApplicationTest {

    @Test
    @DisplayName("Spring workspace property determines the early log path")
    void springWorkspaceDeterminesLogPath(@TempDir Path workspace) {
        String previous = System.getProperty("logging.file.name");
        try {
            System.clearProperty("logging.file.name");
            HarmoniaSuiteApplication.configureLogging(new MockEnvironment()
                    .withProperty("harmonia.workspace", workspace.toString()));
            assertEquals(workspace.resolve("logs/harmonia.log").toString(),
                    System.getProperty("logging.file.name"));
        } finally {
            restoreLoggingProperty(previous);
        }
    }

    @Test
    @DisplayName("explicit logging file property is preserved")
    void explicitLoggingFileIsPreserved(@TempDir Path workspace) {
        String previous = System.getProperty("logging.file.name");
        try {
            System.setProperty("logging.file.name", "operator-configured.log");
            ConfigurableEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("harmonia.workspace", workspace.toString())));
            HarmoniaSuiteApplication.configureLogging(environment);
            assertEquals("operator-configured.log", System.getProperty("logging.file.name"));
        } finally {
            restoreLoggingProperty(previous);
        }
    }

    private static void restoreLoggingProperty(String previous) {
        if (previous == null) {
            System.clearProperty("logging.file.name");
        } else {
            System.setProperty("logging.file.name", previous);
        }
    }
}
