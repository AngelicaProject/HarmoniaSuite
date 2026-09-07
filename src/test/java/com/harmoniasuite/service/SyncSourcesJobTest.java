package com.harmoniasuite.service;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.dto.JobDto;
import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.repository.JobRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.repository.SettingsRepository;
import com.harmoniasuite.service.job.JobHandler;
import com.harmoniasuite.service.job.JobPaths;
import com.harmoniasuite.service.job.SyncSourcesJobHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncSourcesJobTest {

    private record Fixture(JobService jobs) {
    }

    private Fixture seed(Path workspace, Path dbDir) {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        WorkspacePaths paths = new WorkspacePaths(properties);
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        SourceService sources = new SourceService(paths, new SettingsRepository(jdbc));
        JobHandler noop = new JobHandler() {
            @Override
            public String action() {
                return "extract";
            }

            @Override
            public void execute(RunRequest request, JobPaths jobPaths, Consumer<String> log) {
            }
        };
        JobService jobs = new JobService(new JobRepository(), new ProjectRepository(jdbc),
                Runnable::run, paths, List.of(new SyncSourcesJobHandler(sources), noop), sources);
        return new Fixture(jobs);
    }

    @Test
    @DisplayName("sync-sources starts without a projectId")
    void syncSourcesStartsWithoutProjectId(@TempDir Path workspace, @TempDir Path dbDir) {
        Fixture fixture = seed(workspace, dbDir);
        JobDto job = fixture.jobs()
                .start(new RunRequest("sync-sources", null, null, null, null, null, null, null));
        assertEquals("sync-sources", job.action());
    }

    @Test
    @DisplayName("project actions without a projectId are rejected")
    void projectActionsRequireProjectId(@TempDir Path workspace, @TempDir Path dbDir) {
        Fixture fixture = seed(workspace, dbDir);
        assertThrows(HarmoniaSuiteBadRequestException.class, () -> fixture.jobs()
                .start(new RunRequest("extract", null, null, null, null, null, null, null)));
    }
}
