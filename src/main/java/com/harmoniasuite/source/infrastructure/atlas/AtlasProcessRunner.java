package com.harmoniasuite.source.infrastructure.atlas;

import java.time.Duration;
import java.util.List;

/** Internal seam between Atlas command orchestration and operating-system process execution. */
public interface AtlasProcessRunner {

    AtlasProcessResult run(List<String> arguments, Duration timeout,
                           int maxStdoutBytes, int maxStderrBytes);
}
