package com.harmoniasuite.service.job;

import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.service.ProjectService;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class DeleteJobHandler implements JobHandler {

    private final ProjectService projectService;

    public DeleteJobHandler(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Override
    public String action() {
        return "delete";
    }

    @Override
    public void execute(RunRequest request, JobPaths paths, Consumer<String> log) throws Exception {
        projectService.deleteProject(paths.projectId(), log);
    }
}
