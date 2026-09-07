package com.harmoniasuite.service;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.exception.HarmoniaSuiteNotFoundException;
import com.harmoniasuite.repository.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("!postgres")
public class BackupService implements BackupOps {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String VACUUM_INTO = "VACUUM INTO ?";
    public static final String KEY_RETENTION = "backup.retention";
    private static final int DEFAULT_KEEP = 10;
    private static final int MAX_KEEP = 100;

    private final JdbcTemplate jdbc;
    private final Path dbFile;
    private final Path backupDir;
    private final SettingsRepository settings;

    @Autowired
    public BackupService(JdbcTemplate jdbc,
            @Value("${harmonia.db-path:data/harmonia.db}") String dbPath,
            WorkspacePaths workspace,
            SettingsRepository settings) throws Exception {
        this(jdbc, workspace.resolve(dbPath), workspace.resolve(dbPath).resolveSibling("backups"), settings);
    }

    BackupService(JdbcTemplate jdbc, Path dbFile, Path backupDir, SettingsRepository settings) {
        this.jdbc = jdbc;
        this.dbFile = dbFile;
        this.backupDir = backupDir;
        this.settings = settings;
    }

    @Override
    public int retention() {
        String raw = settings.get(KEY_RETENTION);
        if (raw == null) {
            return DEFAULT_KEEP;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return Math.min(Math.max(n, 1), MAX_KEEP);
        } catch (NumberFormatException e) {
            return DEFAULT_KEEP;
        }
    }

    @Override
    public void setRetention(int n) {
        if (n < 1 || n > MAX_KEEP) {
            throw new HarmoniaSuiteBadRequestException("retention must be 1.." + MAX_KEEP);
        }
        settings.set(KEY_RETENTION, String.valueOf(n));
        try {
            prune();
        } catch (Exception e) {
            throw new IllegalStateException("retention prune failed", e);
        }
    }

    @Override
    public long usedBytes() throws Exception {
        long total = 0;
        for (Path p : list()) {
            total += Files.size(p);
        }
        return total;
    }

    @Override
    public long estimatedBytes() throws Exception {
        List<Path> all = list();
        long per = all.isEmpty() ? Files.size(dbFile) : Files.size(all.get(0));
        return (long) retention() * per;
    }

    @Override
    public Path create() throws Exception {
        Files.createDirectories(backupDir);
        Path target = backupDir.resolve("harmonia-" + LocalDateTime.now().format(STAMP) + ".db");
        jdbc.update(VACUUM_INTO, target.toAbsolutePath().toString().replace("\\", "/"));
        prune();
        return target;
    }

    @Override
    public List<Path> list() throws Exception {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (var stream = Files.list(backupDir)) {
            return stream.filter(p -> p.getFileName().toString().matches("harmonia-\\d{8}-\\d{6}\\.db"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }

    @Override
    public Path resolve(String name) throws Exception {
        if (name == null || !name.matches("harmonia-\\d{8}-\\d{6}\\.db")) {
            throw new HarmoniaSuiteBadRequestException("unknown backup");
        }
        Path target = backupDir.resolve(name).normalize();
        if (!target.startsWith(backupDir.normalize()) || !Files.isRegularFile(target)) {
            throw new HarmoniaSuiteNotFoundException("backup not found");
        }
        return target;
    }

    @Override
    public void delete(String name) throws Exception {
        Files.deleteIfExists(resolve(name));
    }

    private void prune() throws Exception {
        List<Path> all = list();
        int keep = retention();
        for (int i = keep; i < all.size(); i++) {
            Files.deleteIfExists(all.get(i));
        }
    }
}
