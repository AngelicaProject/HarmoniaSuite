package com.harmoniasuite.controller;

import com.harmoniasuite.dto.BackupDto;
import com.harmoniasuite.dto.BackupListDto;
import com.harmoniasuite.dto.UpdateBackupSettingsRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.service.backup.BackupOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupOps backups;

    public BackupController(BackupOps backups) {
        this.backups = backups;
    }

    @GetMapping
    public BackupListDto list() throws Exception {
        List<BackupDto> items = backups.list().stream().map(BackupController::toDto).toList();
        return new BackupListDto(items, backups.retention(), backups.autoIntervalMinutes(),
                backups.usedBytes(), backups.estimatedBytes());
    }

    @PutMapping("/settings")
    public BackupListDto settings(@RequestBody UpdateBackupSettingsRequest body) throws Exception {
        if (body.retention() == null && body.autoIntervalMinutes() == null) {
            throw new HarmoniaSuiteBadRequestException("retention or auto_interval_minutes is required");
        }
        if (body.retention() != null) {
            backups.setRetention(body.retention());
        }
        if (body.autoIntervalMinutes() != null) {
            backups.setAutoIntervalMinutes(body.autoIntervalMinutes());
        }
        return list();
    }

    @PostMapping
    public BackupDto create() throws Exception {
        return toDto(backups.create());
    }

    @PostMapping("/open-folder")
    public void openFolder() throws Exception {
        backups.openFolder();
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) throws Exception {
        Path file = backups.resolve(name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getFileName().toString()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{name}")
    public void delete(@PathVariable String name) throws Exception {
        backups.delete(name);
    }

    private static BackupDto toDto(Path file) {
        try {
            return new BackupDto(file.getFileName().toString(), Files.size(file),
                    Files.getLastModifiedTime(file).toInstant().toString());
        } catch (Exception e) {
            throw new IllegalStateException("backup stat failed", e);
        }
    }
}
