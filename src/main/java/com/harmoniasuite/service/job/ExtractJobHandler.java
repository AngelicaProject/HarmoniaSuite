package com.harmoniasuite.service.job;

import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.service.source.ExtractService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

@Component
public class ExtractJobHandler implements JobHandler {

    private final ExtractService extractService;

    public ExtractJobHandler(ExtractService extractService) {
        this.extractService = extractService;
    }

    @Override
    public String action() {
        return "extract";
    }

    @Override
    public void execute(RunRequest request, JobPaths paths, Consumer<String> log) throws IOException {
        boolean force = request.force() != null && request.force();
        if (force) {
            extractService.syncSources(paths.root(), paths.projectId(), null, log);
        } else {
            extractService.syncSourcesAuto(paths.root(), paths.projectId(), log);
        }
    }
}
