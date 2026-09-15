package com.harmoniasuite.source.atlas;

import java.util.Objects;

/** Bounded, separate output from one Atlas process invocation. */
public record AtlasProcessResult(int exitCode, String stdout, String stderr) {

    public AtlasProcessResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }
}
