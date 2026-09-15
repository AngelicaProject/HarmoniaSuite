package com.harmoniasuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.harmoniasuite.config.CoreDatabase;
import com.harmoniasuite.source.store.SourceSnapshotImporter;
import com.harmoniasuite.source.store.JdbcSourceSnapshotStore;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreDatabaseApplicationContextTest {

    private static final Path WORKSPACE = createWorkspace();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CoreDatabase coreDatabase;

    @Autowired
    private JdbcTemplate legacyJdbc;

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("harmonia.workspace", WORKSPACE::toString);
    }

    @Test
    void startsLegacyAndCanonicalInfrastructureIndependently() {
        Path legacyPath = WORKSPACE.resolve("data/harmonia.db");
        Path canonicalPath = WORKSPACE.resolve("data/core/harmonia.db");

        assertTrue(Files.isRegularFile(legacyPath));
        assertTrue(Files.isRegularFile(canonicalPath));
        assertEquals(1, context.getBeansOfType(DataSource.class).size());
        assertEquals(1, context.getBeansOfType(JdbcTemplate.class).size());
        assertEquals(1, context.getBeansOfType(PlatformTransactionManager.class).size());
        assertNotNull(context.getBean(JdbcSourceSnapshotStore.class));
        assertNotNull(context.getBean(SourceSnapshotStore.class));
        assertNotNull(context.getBean(SourceSnapshotImporter.class));
        assertEquals(17, legacyJdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class));
        assertEquals(2, coreDatabase.jdbc().queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class));
        assertEquals(1, coreDatabase.jdbc().queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'source_snapshots'",
                Integer.class));
        assertTrue(!legacyPath.equals(canonicalPath));
    }

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("harmonia-core-context-");
        } catch (Exception exception) {
            throw new IllegalStateException("test workspace could not be created", exception);
        }
    }
}
