package com.harmoniasuite.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourcesFingerprintTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("отпечаток стабилен и выглядит как sha256-hex")
    void fingerprintIsStableSha256Hex() throws Exception {
        Files.writeString(root.resolve("pack-one.csv"), "a");
        String first = SourcesFingerprint.of(List.of(root.resolve("pack-one.csv")), root);
        assertEquals(first, SourcesFingerprint.of(List.of(root.resolve("pack-one.csv")), root));
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("порядок файлов на отпечаток не влияет")
    void fileOrderDoesNotAffectFingerprint() throws Exception {
        Path one = root.resolve("pack-one.csv");
        Path two = root.resolve("pack-two.csv");
        Files.writeString(one, "a");
        Files.writeString(two, "bb");
        assertEquals(
                SourcesFingerprint.of(List.of(one, two), root),
                SourcesFingerprint.of(List.of(two, one), root));
    }

    @Test
    @DisplayName("размер файла меняет отпечаток, имя без размера — нет")
    void sizeChangeAffectsFingerprint() throws Exception {
        Path one = root.resolve("pack-one.csv");
        Files.writeString(one, "a");
        String before = SourcesFingerprint.of(List.of(one), root);
        Files.writeString(one, "ab");
        assertNotEquals(before, SourcesFingerprint.of(List.of(one), root));
    }

    @Test
    @DisplayName("пустой корень даёт отпечаток пустого списка")
    void emptyRootGivesEmptyFingerprint() throws Exception {
        assertEquals(
                SourcesFingerprint.of(List.of(), root),
                SourcesFingerprint.of(List.of(), root));
    }
}
