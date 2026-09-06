package com.harmoniasuite.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class EntryIds {

    private EntryIds() {
    }

    public static String ofCell(String file, String rowKey, int columnIndex) {
        return of((file == null ? "" : file) + "\0" + (rowKey == null ? "" : rowKey) + "\0" + columnIndex, "c_");
    }

    private static String of(String source, String prefix) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
