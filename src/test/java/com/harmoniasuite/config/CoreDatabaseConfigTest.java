package com.harmoniasuite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class CoreDatabaseConfigTest {

    @Test
    void createsAndMigratesCanonicalSqliteInTheTransitionDirectory(@TempDir Path workspace)
            throws Exception {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());

        CoreDatabase database = new CoreDatabaseConfig().sqliteCoreDatabase(
                properties, new WorkspacePaths(properties));
        try {
            Path canonicalPath = workspace.resolve("data/core/harmonia.db");
            assertTrue(Files.isRegularFile(canonicalPath));
            JdbcTemplate jdbc = database.jdbc();
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'source_snapshots'",
                    Integer.class));
        } finally {
            database.close();
        }
    }

    @Test
    void defaultsToCanonicalFilenameUnderCoreDirectory() {
        HarmoniaProperties properties = new HarmoniaProperties();

        assertEquals("data/core/harmonia.db", properties.getCore().getDbPath());
        assertEquals("harmonia_core", properties.getCore().getPostgresSchema());
    }
}
