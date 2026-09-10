package com.harmoniasuite.service.job;

import com.harmoniasuite.dto.RunRequest;
import com.harmoniasuite.service.source.SourceService;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class SyncSourcesJobHandler implements JobHandler {

    private final SourceService sources;

    public SyncSourcesJobHandler(SourceService sources) {
        this.sources = sources;
    }

    @Override
    public String action() {
        return "sync-sources";
    }

    @Override
    public void execute(RunRequest request, JobPaths paths, Consumer<String> log) throws Exception {
        sources.sync(log);
    }
}
