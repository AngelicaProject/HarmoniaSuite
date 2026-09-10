package com.harmoniasuite.service.backup;

import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class UnsupportedBackupService implements BackupOps {

    private static final String MESSAGE = "backup is supported for the embedded SQLite database only";

    private static HarmoniaSuiteBadRequestException unsupported() {
        return new HarmoniaSuiteBadRequestException(MESSAGE);
    }

    @Override
    public Path create() throws Exception {
        throw unsupported();
    }

    @Override
    public List<Path> list() throws Exception {
        throw unsupported();
    }

    @Override
    public Path resolve(String name) throws Exception {
        throw unsupported();
    }

    @Override
    public void delete(String name) throws Exception {
        throw unsupported();
    }

    @Override
    public int retention() {
        throw unsupported();
    }

    @Override
    public void setRetention(int n) {
        throw unsupported();
    }

    @Override
    public int autoIntervalMinutes() {
        throw unsupported();
    }

    @Override
    public void setAutoIntervalMinutes(int minutes) {
        throw unsupported();
    }

    @Override
    public long usedBytes() throws Exception {
        throw unsupported();
    }

    @Override
    public long estimatedBytes() throws Exception {
        throw unsupported();
    }

    @Override
    public void openFolder() throws Exception {
        throw unsupported();
    }
}
