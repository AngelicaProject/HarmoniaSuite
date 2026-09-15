package com.harmoniasuite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.harmoniasuite.config.CoreDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyDatabasePreservationApplicationContextTest {

    private static final byte[] SENTINEL = new byte[]{0x48, 0x41, 0x52, 0x4d, 0x4f, 0x4e, 0x49, 0x41};
    private static final Path WORKSPACE = createWorkspace();

    @Autowired
    private CoreDatabase coreDatabase;

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("harmonia.workspace", WORKSPACE::toString);
    }

    @Test
    void startsWithoutOpeningOrChangingPreexistingLegacyDatabase() throws Exception {
        Path legacyPath = WORKSPACE.resolve("data/harmonia.db");
        Path canonicalPath = WORKSPACE.resolve("data/core/harmonia.db");

        assertTrue(Files.isRegularFile(canonicalPath));
        assertTrue(Files.isRegularFile(legacyPath));
        assertArrayEquals(SENTINEL, Files.readAllBytes(legacyPath));
        assertTrue(coreDatabase.jdbc().queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'source_snapshots'",
                Integer.class) > 0);
    }

    private static Path createWorkspace() {
        try {
            Path workspace = Files.createTempDirectory("harmonia-legacy-preservation-");
            Files.createDirectories(workspace.resolve("data"));
            Files.write(workspace.resolve("data/harmonia.db"), SENTINEL);
            return workspace;
        } catch (Exception exception) {
            throw new IllegalStateException("test workspace could not be created", exception);
        }
    }
}
