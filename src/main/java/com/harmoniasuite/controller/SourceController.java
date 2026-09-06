package com.harmoniasuite.controller;

import com.harmoniasuite.dto.SourceFilesDto;
import com.harmoniasuite.dto.SourcePreviewDto;
import com.harmoniasuite.service.FileSearchService;
import com.harmoniasuite.service.SourceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/source")
public class SourceController {

    private final FileSearchService fileSearchService;
    private final SourceService sources;

    public SourceController(FileSearchService fileSearchService, SourceService sources) {
        this.fileSearchService = fileSearchService;
        this.sources = sources;
    }

    @GetMapping("/files")
    public SourceFilesDto files(@RequestParam(defaultValue = "") String root)
            throws Exception {
        return fileSearchService.listCsvFiles(effectiveRoot(root));
    }

    @GetMapping("/preview")
    public SourcePreviewDto preview(@RequestParam(defaultValue = "") String root,
            @RequestParam String file,
            @RequestParam(defaultValue = "false") boolean full) throws Exception {
        return fileSearchService.previewFile(effectiveRoot(root), file, full);
    }

    private String effectiveRoot(String root) {
        if (root == null || root.isBlank()) {
            return sources.activeRoot().toString();
        }
        return root;
    }
}
