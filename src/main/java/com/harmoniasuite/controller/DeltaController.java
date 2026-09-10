package com.harmoniasuite.controller;

import com.harmoniasuite.dto.DeltaExportDto;
import com.harmoniasuite.dto.DeltaImportRequest;
import com.harmoniasuite.dto.DeltaImportResultDto;
import com.harmoniasuite.service.project.DeltaService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/delta")
public class DeltaController {

    private final DeltaService deltaService;

    public DeltaController(DeltaService deltaService) {
        this.deltaService = deltaService;
    }

    @GetMapping
    public DeltaExportDto exportDelta(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "") String sinceUpdatedAt,
            @RequestParam(defaultValue = "") String sinceCellId,
            @RequestParam(defaultValue = "") String files,
            @RequestParam(defaultValue = "20000") int limit,
            @RequestParam(defaultValue = "") String author) {
        return deltaService.exportDelta(projectId, sinceUpdatedAt, sinceCellId, files, limit, author);
    }

    @PostMapping("/preview")
    public DeltaImportResultDto preview(@PathVariable UUID projectId,
            @RequestBody DeltaImportRequest body) {
        return deltaService.preview(projectId, body);
    }

    @PostMapping("/import")
    public DeltaImportResultDto importDelta(@PathVariable UUID projectId,
            @RequestBody DeltaImportRequest body) {
        return deltaService.importDelta(projectId, body);
    }
}
