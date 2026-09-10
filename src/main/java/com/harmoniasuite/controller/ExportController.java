package com.harmoniasuite.controller;

import com.harmoniasuite.dto.ExportListDto;
import com.harmoniasuite.service.export.ExportService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/api/projects/{projectId}/exports")
    public ExportListDto list(@PathVariable UUID projectId) throws IOException {
        return exportService.listing(projectId);
    }

    @GetMapping("/api/projects/{projectId}/exports/csv")
    public ResponseEntity<Resource> csv(@PathVariable UUID projectId, @RequestParam String file)
            throws IOException {
        ExportService.CsvDownload download = exportService.csvFile(projectId, file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(download.length())
                .body(download.resource());
    }

    @GetMapping("/api/projects/{projectId}/exports/zip")
    public ResponseEntity<byte[]> zip(@PathVariable UUID projectId) throws IOException {
        ExportService.ZipDownload download = exportService.zip(projectId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.filename() + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(download.bytes().length)
                .body(download.bytes());
    }

    @GetMapping("/api/projects/{projectId}/exports/manifest")
    public ResponseEntity<byte[]> manifest(@PathVariable UUID projectId) throws IOException {
        byte[] json = exportService.manifest(projectId).bytes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"manifest.json\"")
                .contentType(MediaType.parseMediaType("application/json; charset=UTF-8"))
                .contentLength(json.length)
                .body(json);
    }
}
