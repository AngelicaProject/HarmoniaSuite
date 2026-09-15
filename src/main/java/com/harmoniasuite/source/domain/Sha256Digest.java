package com.harmoniasuite.source.domain;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Exactly one SHA-256 digest with defensive-copy and canonical lowercase-hex semantics. */
public final class Sha256Digest {

    private final byte[] bytes;

    private Sha256Digest(byte[] bytes) {
        if (bytes.length != 32) {
            throw new IllegalArgumentException("SHA-256 digest must contain exactly 32 bytes");
        }
        this.bytes = bytes.clone();
    }

    public static Sha256Digest of(byte[] bytes) {
        return new Sha256Digest(Objects.requireNonNull(bytes, "bytes"));
    }

    public static Sha256Digest parseHex(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() != 64 || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("SHA-256 hex digest must contain 64 characters");
        }
        return new Sha256Digest(HexFormat.of().parseHex(value));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Sha256Digest digest && Arrays.equals(bytes, digest.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return hex();
    }
}
