package com.harmoniasuite.source.application.port;

@FunctionalInterface
public interface SourceIngestionDispatcher {

    void dispatch(java.util.UUID uploadId);
}
