package com.harmoniasuite.service.job;

import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.service.ai.GeminiProvider;
import com.harmoniasuite.service.ai.LlmTranslateService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
public class GeminiTranslateJobHandler implements JobHandler {

    private final LlmTranslateService llmTranslateService;
    private final GeminiProvider provider;

    public GeminiTranslateJobHandler(LlmTranslateService llmTranslateService, GeminiProvider provider) {
        this.llmTranslateService = llmTranslateService;
        this.provider = provider;
    }

    @Override
    public String action() {
        return "gemini";
    }

    @Override
    public void execute(RunRequest request, JobPaths paths, Consumer<String> log) throws Exception {
        llmTranslateService.translate(paths.projectId(), request.files() == null ? List.of() : request.files(),
                request.model(), request.reasoning(), provider, log);
    }
}
