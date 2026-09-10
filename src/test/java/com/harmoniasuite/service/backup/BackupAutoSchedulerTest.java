package com.harmoniasuite.service.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.repository.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class BackupAutoSchedulerTest {

    @TempDir
    Path tmp;

    private BackupService service(String dir) {
        JdbcTemplate jdbc = TestDatabases.sqlite(tmp.resolve(dir));
        return new BackupService(jdbc, tmp.resolve(dir + "/test.db"), tmp.resolve(dir + "-backups"),
                new SettingsRepository(jdbc));
    }

    private BackupAutoScheduler scheduler(BackupService service, Instant now) {
        return new BackupAutoScheduler(service, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("auto backup does nothing when disabled")
    void skipsWhenDisabled() throws Exception {
        BackupService service = service("live-auto1");
        scheduler(service, Instant.now()).autoBackup();
        assertEquals(List.of(), service.list());
    }

    @Test
    @DisplayName("auto backup creates a snapshot when none exists")
    void createsWhenEmpty() throws Exception {
        BackupService service = service("live-auto2");
        service.setAutoIntervalMinutes(60);
        scheduler(service, Instant.now()).autoBackup();
        assertEquals(1, service.list().size());
    }

    @Test
    @DisplayName("auto backup skips a fresh snapshot")
    void skipsFreshSnapshot() throws Exception {
        BackupService service = service("live-auto3");
        service.setAutoIntervalMinutes(60);
        Instant now = Instant.now();
        service.create();
        scheduler(service, now).autoBackup();
        assertEquals(1, service.list().size());
    }

    @Test
    @DisplayName("auto backup creates a snapshot when the newest is older than the interval")
    void createsWhenStale() throws Exception {
        BackupService service = service("live-auto4");
        service.setAutoIntervalMinutes(60);
        Instant now = Instant.now();
        service.create();
        Path only = service.list().get(0);
        Path aged = only.resolveSibling("harmonia-20000101-000000.db");
        Files.move(only, aged);
        Files.setLastModifiedTime(aged, FileTime.from(now.minusSeconds(3600)));
        scheduler(service, now).autoBackup();
        assertEquals(2, service.list().size());
    }
}
