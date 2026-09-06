package com.harmoniasuite.controller;

import com.harmoniasuite.dto.EntriesPageDto;
import com.harmoniasuite.dto.EntryDto;
import com.harmoniasuite.dto.SaveEntryResponseDto;
import com.harmoniasuite.dto.UpdateEntryRequest;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.service.ProjectService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/entries")
public class EntriesController {

    private final ProjectService projectService;

    public EntriesController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public EntriesPageDto entries(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "") String file,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String rowKey,
            @RequestParam(defaultValue = "false") boolean untranslated,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "0") int limit) {
        EntryRepository.EntryFilter filter = new EntryRepository.EntryFilter(
                rowKey.isBlank() ? null : rowKey,
                ProjectService.normalizeStatuses(status),
                file.isBlank() ? null : file,
                q.isBlank() ? null : q,
                untranslated);
        return projectService.entries(projectId, filter, offset, limit);
    }

    @GetMapping("/{entryId}")
    public EntryDto entry(@PathVariable UUID projectId, @PathVariable UUID entryId) {
        return projectService.entry(projectId, entryId);
    }

    @GetMapping("/by-cell/{cellId}")
    public EntryDto entryByCell(@PathVariable UUID projectId, @PathVariable String cellId) {
        return projectService.entryByCell(projectId, cellId);
    }

    @PatchMapping("/{entryId}")
    public SaveEntryResponseDto saveEntry(@PathVariable UUID projectId, @PathVariable UUID entryId,
            @RequestBody UpdateEntryRequest body) {
        String translation = body == null ? "" : body.translation();
        String state = body == null ? null : body.status();
        return projectService.updateEntry(projectId, entryId, translation, state);
    }
}
