package com.harmoniasuite.controller;

import com.harmoniasuite.domain.PackMeta;
import com.harmoniasuite.dto.PackViewDto;
import com.harmoniasuite.service.PackService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/pack")
public class PackController {

    private final PackService packService;

    public PackController(PackService packService) {
        this.packService = packService;
    }

    @GetMapping
    public PackViewDto pack(@PathVariable UUID projectId) {
        return packService.packView(projectId);
    }

    @PutMapping
    public PackViewDto savePack(@PathVariable UUID projectId, @RequestBody PackMeta pack)
            throws IOException {
        return packService.savePack(projectId, pack);
    }
}
