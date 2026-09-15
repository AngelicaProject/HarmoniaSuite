package com.harmoniasuite.source.infrastructure.scheduling;

import com.harmoniasuite.source.application.SourceIngestionProcessor;
import com.harmoniasuite.source.application.port.SourceIngestionDispatcher;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Scheduling adapter; executor rejection deliberately leaves the persisted row QUEUED. */
@Component
public final class SourceIngestionExecutorDispatcher implements SourceIngestionDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceIngestionExecutorDispatcher.class);
    private final SourceIngestionProcessor processor;
    private final Executor executor;

    public SourceIngestionExecutorDispatcher(SourceIngestionProcessor processor,
                                             @Qualifier("sourceIngestionExecutor") Executor executor) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void dispatch(UUID uploadId) {
        try { executor.execute(() -> processor.processNow(uploadId)); }
        catch (RejectedExecutionException exception) {
            LOGGER.warn("source upload {} remains queued because ingestion executor is unavailable", uploadId);
        }
    }
}
