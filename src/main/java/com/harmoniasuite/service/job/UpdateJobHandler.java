package com.harmoniasuite.service.job;

import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.service.UpdateService;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class UpdateJobHandler implements JobHandler {

    private final UpdateService updateService;

    public UpdateJobHandler(UpdateService updateService) {
        this.updateService = updateService;
    }

    @Override
    public String action() {
        return "update";
    }

    @Override
    public void execute(RunRequest request, JobPaths paths, Consumer<String> log) throws Exception {
        updateService.runUpdate(log);
    }
}
