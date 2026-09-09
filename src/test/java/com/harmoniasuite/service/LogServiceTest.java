package com.harmoniasuite.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogServiceTest {

    @Test
    @DisplayName("tail returns the newest requested lines and enforces the maximum")
    void tailsRecentLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("harmonia.log");
        Files.writeString(file, IntStream.rangeClosed(1, 2100)
                .mapToObj(i -> "line-" + i)
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow());

        LogService service = new LogService(file);
        String output = service.tail(9999);

        assertEquals(2000, output.lines().count());
        assertFalse(output.lines().anyMatch(line -> line.equals("line-100")));
        assertTrue(output.lines().anyMatch(line -> line.equals("line-101")));
        assertTrue(output.lines().anyMatch(line -> line.equals("line-2100")));
        assertEquals(500, LogService.normalizeTail(0));
        assertEquals(2000, LogService.normalizeTail(2001));
    }

    @Test
    @DisplayName("tail keeps a capped single line instead of returning an empty result")
    void keepsGiantSingleLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("harmonia.log");
        Files.writeString(file, "x".repeat(LogService.MAX_BYTES + 100));

        String output = new LogService(file).tail(500);

        assertEquals(LogService.MAX_BYTES, output.length());
        assertTrue(output.chars().allMatch(c -> c == 'x'));
    }

    @Test
    @DisplayName("tail masks the user name in home paths")
    void masksHomeUserName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("harmonia.log");
        Files.writeString(file, "reading C:\\Users\\testuser\\AppData\\app.db\nok\n");

        String output = new LogService(file).tail(500);

        assertTrue(output.contains("C:\\Users\\***\\AppData\\app.db"));
        assertFalse(output.contains("testuser"));
    }
}
