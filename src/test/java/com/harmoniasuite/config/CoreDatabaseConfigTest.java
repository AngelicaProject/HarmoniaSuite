package com.harmoniasuite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
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
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'source_snapshots'",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'source_artifacts'",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'source_upload_sessions'",
                    Integer.class));
            assertEquals("BLOB", jdbc.queryForObject(
                    "SELECT type FROM pragma_table_info('source_artifacts') WHERE name = 'artifact_hash'",
                    String.class));
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

    @Test
    void runtimeConfigurationUsesOnlyCanonicalMigrationLocations() throws IOException {
        String application = new String(
                getClass().getClassLoader().getResourceAsStream("application.yml").readAllBytes(),
                StandardCharsets.UTF_8);
        String postgres = new String(
                getClass().getClassLoader().getResourceAsStream("application-postgres.yml")
                        .readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(application.contains("db-path: data/core/harmonia.db"));
        assertTrue(application.contains("postgres-schema: harmonia_core"));
        assertTrue(!application.contains("db-path: data/harmonia.db"));
        assertTrue(!application.contains("spring:\n  flyway:"));
        assertTrue(!postgres.contains("flyway:"));
        assertEquals("classpath:db/core/migration/sqlite",
                privateMigrationLocation("SQLITE_MIGRATIONS"));
        assertEquals("classpath:db/core/migration/postgresql",
                privateMigrationLocation("POSTGRES_MIGRATIONS"));
    }

    private static String privateMigrationLocation(String fieldName) {
        try {
            var field = CoreDatabaseConfig.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("migration location constant is unavailable", exception);
        }
    }
}
