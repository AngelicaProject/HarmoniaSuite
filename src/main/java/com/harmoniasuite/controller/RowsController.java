package com.harmoniasuite.controller;

import com.harmoniasuite.dto.EntryDto;
import com.harmoniasuite.dto.RowGroupsPageDto;
import com.harmoniasuite.service.ProjectService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/rows")
public class RowsController {

    private final ProjectService projectService;

    public RowsController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public RowGroupsPageDto rows(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "") String file,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "") String q) {
        return projectService.rowGroups(projectId, file, q, offset, limit);
    }

    @GetMapping("/position")
    public long position(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "") String file,
            @RequestParam int rowIndex,
            @RequestParam(defaultValue = "") String q) {
        return projectService.rowGroupPosition(projectId, file, rowIndex, q);
    }

    @GetMapping("/next")
    public EntryDto next(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "") String file,
            @RequestParam(defaultValue = "-1") int afterRow,
            @RequestParam(defaultValue = "-1") int afterCol,
            @RequestParam(defaultValue = "") String q) {
        return projectService.nextNeedsWork(projectId, file, afterRow, afterCol, q);
    }
}
