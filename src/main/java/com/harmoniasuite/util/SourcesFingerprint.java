package com.harmoniasuite.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SourcesFingerprint {

    private SourcesFingerprint() {
    }

    public static String of(List<Path> files, Path root) throws IOException {
        List<String> lines = new ArrayList<>(files.size());
        for (Path file : files) {
            Path absolute = file.toAbsolutePath().normalize();
            String relative = root.relativize(absolute).toString().replace('\\', '/');
            lines.add(relative + ":" + Files.size(absolute));
        }
        Collections.sort(lines);
        return sha256(String.join("\n", lines));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
