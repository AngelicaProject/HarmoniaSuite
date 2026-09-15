package com.harmoniasuite.source.upload;

@FunctionalInterface
public interface SourceIngestionQueue {

    void enqueue(String uploadId);
}
