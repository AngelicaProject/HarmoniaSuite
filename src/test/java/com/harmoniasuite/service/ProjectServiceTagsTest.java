package com.harmoniasuite.service;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.domain.EntryIds;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.mapping.EntryMapperImpl;
import com.harmoniasuite.mapping.ProjectMapperImpl;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.ProjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectServiceTagsTest {

    private record Fixture(ProjectService projects, UUID projectId, UUID entryId) {
    }

    private Fixture seed(Path workspace, Path dbDir, String source) {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        ProjectRepository projectRepository = new ProjectRepository(jdbc);
        EntryRepository entryRepository = new EntryRepository(jdbc);
        String now = Instant.now().toString();
        UUID projectId = projectRepository.insert("pack-one", "rawexd/en", "dir", "out", "en", "ru", now);
        projectRepository.upsertFiles(projectId, List.of("pack-one.csv"), now);
        TranslationEntry entry = new TranslationEntry();
        String cellId = EntryIds.ofCell("pack-one.csv", "10", 2);
        entry.setId(cellId);
        entry.setSource(source);
        entry.setTranslation("");
        entry.setStatus("untranslated");
        entry.setFile("pack-one.csv");
        entry.setRowKey("10");
        entry.setColumnIndex(2);
        entryRepository.batchUpsert(projectId, projectRepository.fileIdMap(projectId),
                List.of(entry), now);
        ProjectService projects = new ProjectService(new WorkspacePaths(properties), projectRepository,
                entryRepository, new EntryMapperImpl(),
                new ProjectMapperImpl());
        UUID entryId = UUID.fromString(entryRepository.findByCell(projectId, cellId).getUuid());
        return new Fixture(projects, projectId, entryId);
    }

    @Test
    @DisplayName("save rejects a translation with broken tags")
    void updateEntryRejectsBrokenTags(@TempDir Path tmp, @TempDir Path dbDir) {
        Fixture fixture = seed(tmp, dbDir, "Grants <colortype(506)>Delirium<br>");

        var error = assertThrows(IllegalArgumentException.class, () ->
                fixture.projects().updateEntry(fixture.projectId(), fixture.entryId(),
                        "Дарует <colortype(506>Бред<br>", "needs_human_review"));
        assertEquals(true, error.getMessage().contains("Битые теги"));
    }

    @Test
    @DisplayName("save accepts a different tag set with a warning")
    @SuppressWarnings("unchecked")
    void updateEntryWarnsOnDifferentTagSet(@TempDir Path tmp, @TempDir Path dbDir) {
        Fixture fixture = seed(tmp, dbDir, "Grants <colortype(506)>Delirium<br>");

        var ok = fixture.projects().updateEntry(fixture.projectId(), fixture.entryId(),
                "Дарует Бред<br>", "needs_human_review");
        assertEquals(true, ok.ok());
        assertEquals(1, ok.warnings().size());
        assertEquals(true, ok.warnings().get(0).contains("нет тега <colortype(506)> из оригинала"));
    }

    @Test
    @DisplayName("save accepts matching tags and a blank translation")
    void updateEntryAcceptsMatchingTags(@TempDir Path tmp, @TempDir Path dbDir) {
        Fixture fixture = seed(tmp, dbDir, "Grants <colortype(506)>Delirium<br>");

        var ok = fixture.projects().updateEntry(fixture.projectId(), fixture.entryId(),
                "Дарует <colortype(506)>Бред<br>", "needs_human_review");
        assertEquals(true, ok.ok());
    }
}
