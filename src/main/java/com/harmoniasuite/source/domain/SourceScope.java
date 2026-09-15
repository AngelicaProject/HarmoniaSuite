package com.harmoniasuite.source.domain;

public enum SourceScope {
    FULL("full");

    private final String wireValue;

    SourceScope(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SourceScope parse(String value) {
        if (FULL.wireValue.equals(value)) {
            return FULL;
        }
        throw new IllegalArgumentException("scope must be full");
    }
}
