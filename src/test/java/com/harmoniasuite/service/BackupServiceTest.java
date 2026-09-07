package com.harmoniasuite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.repository.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class BackupServiceTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("backup creates a restorable snapshot containing project rows")
    void backupContainsRows() throws Exception {
        JdbcTemplate jdbc = TestDatabases.sqlite(tmp.resolve("live"));
        jdbc.update("INSERT INTO projects(name, project_dir, output_dir) VALUES ('pack-one', 'projects/pack-one', 'projects/pack-one/exported_csv')");
        Path dbFile = tmp.resolve("live/test.db");
        Path backupDir = tmp.resolve("backups");

        new BackupService(jdbc, dbFile, backupDir, new SettingsRepository(jdbc)).create();

        Path backup = Files.list(backupDir).findFirst().orElseThrow();
        javax.sql.DataSource restored = com.harmoniasuite.db.SqliteDataSources.create(backup);
        Integer count = new JdbcTemplate(restored).queryForObject("SELECT COUNT(*) FROM projects", Integer.class);
        assertEquals(1, count);
    }

    @Test
    @DisplayName("resolve rejects path traversal and unknown names")
    void resolveRejectsTraversal() throws Exception {
        JdbcTemplate jdbc = TestDatabases.sqlite(tmp.resolve("live2"));
        BackupService service = new BackupService(jdbc, tmp.resolve("live2/test.db"), tmp.resolve("backups2"),
                new SettingsRepository(jdbc));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("../harmonia.db"));
        assertThrows(HarmoniaSuiteNotFoundException.class, () -> service.resolve("harmonia-20260101-000000.db"));
    }

    @Test
    @DisplayName("setRetention rejects out-of-range values and prunes immediately")
    void retentionPrunesImmediately() throws Exception {
        JdbcTemplate jdbc = TestDatabases.sqlite(tmp.resolve("live3"));
        BackupService service = new BackupService(jdbc, tmp.resolve("live3/test.db"), tmp.resolve("backups3"),
                new SettingsRepository(jdbc));
        assertEquals(10, service.retention());
        assertThrows(IllegalArgumentException.class, () -> service.setRetention(0));
        assertThrows(IllegalArgumentException.class, () -> service.setRetention(101));
        service.setRetention(2);
        service.create();
        Thread.sleep(1100);
        service.create();
        Thread.sleep(1100);
        service.create();
        assertEquals(2, service.list().size());
    }
}
