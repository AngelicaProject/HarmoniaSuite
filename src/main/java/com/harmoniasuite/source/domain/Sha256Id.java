package com.harmoniasuite.source.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Public logical identifier encoded as {@code sha256:<64 lowercase hex characters>}. */
public record Sha256Id(String value) {

    private static final Pattern FORMAT = Pattern.compile("sha256:[0-9a-f]{64}");

    public Sha256Id {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("SHA-256 ID must use lowercase hexadecimal");
        }
    }

    public static Sha256Id parse(String value) {
        return new Sha256Id(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
