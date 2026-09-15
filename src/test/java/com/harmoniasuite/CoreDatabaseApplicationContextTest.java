package com.harmoniasuite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.harmoniasuite.config.CoreDatabase;
import com.harmoniasuite.source.store.JdbcSourceSnapshotStore;
import com.harmoniasuite.source.store.SourceSnapshotImporter;
import com.harmoniasuite.source.store.SourceSnapshotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreDatabaseApplicationContextTest {

    private static final Path WORKSPACE = createWorkspace();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CoreDatabase coreDatabase;

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("harmonia.workspace", WORKSPACE::toString);
    }

    @Test
    void startsCanonicalInfrastructureWithoutLegacyDatabase() {
        Path legacyPath = WORKSPACE.resolve("data/harmonia.db");
        Path canonicalPath = WORKSPACE.resolve("data/core/harmonia.db");

        assertFalse(Files.exists(legacyPath));
        assertTrue(Files.isRegularFile(canonicalPath));
        assertEquals(0, context.getBeansOfType(javax.sql.DataSource.class).size());
        assertEquals(0, context.getBeansOfType(JdbcTemplate.class).size());
        assertEquals(0, context.getBeansOfType(PlatformTransactionManager.class).size());
        assertNotNull(coreDatabase);
        assertNotNull(context.getBean(JdbcSourceSnapshotStore.class));
        assertNotNull(context.getBean(SourceSnapshotStore.class));
        assertNotNull(context.getBean(SourceSnapshotImporter.class));
        assertEquals(2, coreDatabase.jdbc().queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class));
        assertEquals(1, coreDatabase.jdbc().queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'source_snapshots'",
                Integer.class));
    }

    @Test
    void exposesCanonicalSourceRoutesOnlyForSourceData() {
        var mappings = context.getBeansOfType(
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                .values().stream().findFirst().orElseThrow().getHandlerMethods();
        String mappingText = mappings.keySet().stream().map(Object::toString).reduce("", String::concat);

        assertTrue(mappingText.contains("/api/source-snapshots"));
        Map.ofEntries(Map.entry("/api/projects", "projects"), Map.entry("/api/entries", "entries"),
                Map.entry("/api/rows", "rows"), Map.entry("/api/jobs", "jobs"),
                Map.entry("/api/source/", "legacy source"), Map.entry("/api/pack", "pack"),
                Map.entry("/api/export", "export"), Map.entry("/api/delta", "delta"),
                Map.entry("/api/backup", "backup"), Map.entry("/api/ai", "ai"),
                Map.entry("/api/update", "update"))
                .forEach((path, label) -> assertFalse(mappingText.contains(path), label));
    }

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("harmonia-core-context-");
        } catch (Exception exception) {
            throw new IllegalStateException("test workspace could not be created", exception);
        }
    }
}
