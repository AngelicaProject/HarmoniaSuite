package com.harmoniasuite.service.job;

import com.harmoniasuite.dto.RunRequest;

import java.util.function.Consumer;

public interface JobHandler {

    String action();

    void execute(RunRequest request, JobPaths paths, Consumer<String> log) throws Exception;
}
