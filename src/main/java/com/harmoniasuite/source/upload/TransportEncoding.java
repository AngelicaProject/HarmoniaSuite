package com.harmoniasuite.source.upload;

public enum TransportEncoding {
    IDENTITY("identity"),
    ZSTD("zstd");

    private final String wireValue;

    TransportEncoding(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TransportEncoding parse(String value) {
        for (TransportEncoding encoding : values()) {
            if (encoding.wireValue.equals(value)) {
                return encoding;
            }
        }
        throw new IllegalArgumentException("transportEncoding must be identity or zstd");
    }
}
