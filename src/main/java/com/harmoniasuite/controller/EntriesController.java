package com.harmoniasuite.controller;

import com.harmoniasuite.dto.EntriesPageDto;
import com.harmoniasuite.dto.EntryDto;
import com.harmoniasuite.dto.SaveEntryResponseDto;
import com.harmoniasuite.dto.UpdateEntryRequest;
import com.harmoniasuite.service.project.EntryService;
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

    private final EntryService entryService;

    public EntriesController(EntryService entryService) {
        this.entryService = entryService;
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
        return entryService.search(projectId,
                new EntryService.EntrySearch(file, q, status, rowKey, untranslated, offset, limit));
    }

    @GetMapping("/{entryId}")
    public EntryDto entry(@PathVariable UUID projectId, @PathVariable UUID entryId) {
        return entryService.find(projectId, entryId);
    }

    @GetMapping("/by-cell/{cellId}")
    public EntryDto entryByCell(@PathVariable UUID projectId, @PathVariable String cellId) {
        return entryService.findByCell(projectId, cellId);
    }

    @PatchMapping("/{entryId}")
    public SaveEntryResponseDto saveEntry(@PathVariable UUID projectId, @PathVariable UUID entryId,
            @RequestBody UpdateEntryRequest body) {
        return entryService.update(projectId, entryId, body);
    }
}
