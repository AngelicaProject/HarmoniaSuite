package com.harmoniasuite.service.job;

import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.service.export.MergeService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Consumer;

@Component
public class MergeJobHandler implements JobHandler {

    private final MergeService mergeService;

    public MergeJobHandler(MergeService mergeService) {
        this.mergeService = mergeService;
    }

    @Override
    public String action() {
        return "merge";
    }

    @Override
    public void execute(RunRequest request, JobPaths paths, Consumer<String> log) throws IOException {
        mergeService.merge(paths.root(), paths.output(), paths.projectId(), request.files(), log);
    }
}
