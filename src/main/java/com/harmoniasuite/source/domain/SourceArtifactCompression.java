package com.harmoniasuite.source.domain;

public enum SourceArtifactCompression {
    ZSTD("zstd");

    private final String wireValue;

    SourceArtifactCompression(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
