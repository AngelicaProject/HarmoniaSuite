package com.harmoniasuite.service.job;

import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.service.LlmTranslateService;
import com.harmoniasuite.service.OpenRouterProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
public class OpenRouterTranslateJobHandler implements JobHandler {

    private final LlmTranslateService llmTranslateService;
    private final OpenRouterProvider provider;

    public OpenRouterTranslateJobHandler(LlmTranslateService llmTranslateService, OpenRouterProvider provider) {
        this.llmTranslateService = llmTranslateService;
        this.provider = provider;
    }

    @Override
    public String action() {
        return "openrouter";
    }

    @Override
    public void execute(RunRequest request, JobPaths paths, Consumer<String> log) throws Exception {
        llmTranslateService.translate(paths.projectId(), request.files() == null ? List.of() : request.files(),
                request.model(), request.reasoning(), provider, log);
    }
}
