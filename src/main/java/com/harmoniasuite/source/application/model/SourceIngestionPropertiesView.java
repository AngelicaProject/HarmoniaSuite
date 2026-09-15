package com.harmoniasuite.source.application.model;

public record SourceIngestionPropertiesView(long maxUploadBytes, long maxHxsBytes,
                                            long maxChunkBytes, int zstdLevel) {
}
