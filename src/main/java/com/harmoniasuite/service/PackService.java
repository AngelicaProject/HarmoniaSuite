package com.harmoniasuite.service;

import com.harmoniasuite.domain.PackMeta;
import com.harmoniasuite.dto.PackViewDto;
import com.harmoniasuite.repository.PackRepository;
import com.harmoniasuite.repository.ProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PackService {

    private final ProjectRepository projectRepository;
    private final PackRepository packStore;
    private final PackManifestService packs;

    public PackService(ProjectRepository projectRepository,
            PackRepository packStore, PackManifestService packs) {
        this.projectRepository = projectRepository;
        this.packStore = packStore;
        this.packs = packs;
    }

    public PackViewDto packView(UUID projectId) {
        projectRepository.findById(projectId);
        PackMeta pack = packs.storedView(packStore.load(projectId));
        List<String> errors = packs.validate(pack);
        return new PackViewDto(pack, errors.isEmpty() ? packs.build(pack) : null, errors);
    }

    @Transactional
    public PackViewDto savePack(UUID projectId, PackMeta body) {
        projectRepository.findById(projectId);
        String now = Instant.now().toString();
        if (body != null) {
            packStore.save(projectId, body, now);
        }
        PackMeta pack = packs.storedView(packStore.load(projectId));
        List<String> errors = packs.validate(pack);
        return new PackViewDto(pack, errors.isEmpty() ? packs.build(pack) : null, errors);
    }
}
