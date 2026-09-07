package com.harmoniasuite.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!postgres")
public class BackupAutoScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupAutoScheduler.class);

    private final BackupOps backups;
    private final Clock clock;

    @Autowired
    public BackupAutoScheduler(BackupOps backups) {
        this(backups, Clock.systemUTC());
    }

    BackupAutoScheduler(BackupOps backups, Clock clock) {
        this.backups = backups;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60000)
    public void autoBackup() {
        try {
            int interval = backups.autoIntervalMinutes();
            if (interval <= 0) {
                return;
            }
            Instant newest = null;
            List<Path> all = backups.list();
            for (Path p : all) {
                Instant modified = Files.getLastModifiedTime(p).toInstant();
                if (newest == null || modified.isAfter(newest)) {
                    newest = modified;
                }
            }
            if (newest != null && Duration.between(newest, clock.instant()).toMinutes() < interval) {
                return;
            }
            Path created = backups.create();
            log.info("Автобэкап создан: {}", created.getFileName());
        } catch (Exception e) {
            log.warn("Автобэкап не создан: {}", e.getMessage());
        }
    }
}
