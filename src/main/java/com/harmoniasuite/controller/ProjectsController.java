package com.harmoniasuite.controller;

import com.harmoniasuite.dto.CreateProjectRequest;
import com.harmoniasuite.dto.CreateProjectResponseDto;
import com.harmoniasuite.dto.FileTreeDto;
import com.harmoniasuite.dto.OverviewDto;
import com.harmoniasuite.dto.PendingByFileDto;
import com.harmoniasuite.dto.ProjectFilesDto;
import com.harmoniasuite.dto.ProjectsResponseDto;
import com.harmoniasuite.service.project.ProjectService;
import com.harmoniasuite.service.project.EntryService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectsController {

    private final ProjectService projectService;
    private final EntryService entryService;

    public ProjectsController(ProjectService projectService, EntryService entryService) {
        this.projectService = projectService;
        this.entryService = entryService;
    }

    @GetMapping
    public ProjectsResponseDto projects() {
        return new ProjectsResponseDto(projectService.listProjects());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateProjectResponseDto createProject(@Valid @RequestBody CreateProjectRequest body)
            throws IOException {
        return projectService.createProject(body.id(), body.root());
    }

    @GetMapping("/{projectId}/overview")
    public OverviewDto overview(@PathVariable UUID projectId) {
        return projectService.overview(projectId);
    }

    @GetMapping("/{projectId}/files/tree")
    public FileTreeDto fileTree(@PathVariable UUID projectId) {
        return projectService.fileTree(projectId);
    }

    @GetMapping("/{projectId}/translate/pending-by-file")
    public PendingByFileDto pendingByFile(@PathVariable UUID projectId) {
        return new PendingByFileDto(entryService.pendingByFile(projectId));
    }

    @GetMapping("/{projectId}/files")
    public ProjectFilesDto listFiles(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "false") boolean hideReady,
            @RequestParam(defaultValue = "false") boolean readyOnly,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "0") int limit) {
        return projectService.listFiles(
                projectId, q.isBlank() ? null : q, hideReady, readyOnly, offset, limit);
    }
}
