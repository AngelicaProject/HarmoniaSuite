package com.harmoniasuite.service.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelaunchScriptsTest {

    @Test
    @DisplayName("Exe relaunch script is utf-16 and waits, copies the built jar, starts the exe")
    void writesExeScript(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("тест-каталог");
        Files.createDirectories(root);
        Path built = root.resolve("built.jar");
        Path appJar = root.resolve("app.jar");
        Path exe = root.resolve("app.exe");

        Path script = RelaunchScripts.writeExeScript(root, 12345, built, appJar, exe,
                root.resolve("relaunch.log"));
        try {
            String body = utf16Body(script);
            assertTrue(body.contains("wait 12345"), body);
            assertTrue(body.contains("CopyFile \"" + built + "\", \"" + appJar + "\", True"), body);
            assertTrue(body.contains("sh.Run \"" + exe + "\", 1, False"), body);
            assertTrue(body.contains("тест-каталог"), body);
            assertTrue(body.contains("HARMONIA_NO_BROWSER"), body);
            assertTrue(body.contains("DeleteFile WScript.ScriptFullName"), body);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    @DisplayName("Replayed relaunch script keeps quoted arguments out of a shell wrapper")
    void writesWindowsReplayScript(@TempDir Path dir) throws Exception {
        Path script = RelaunchScripts.writeReplayVbs(dir, 7,
                List.of("C:\\Program Files\\java.exe", "--value=one&two%PATH%", "тест"),
                dir, dir.resolve("relaunch.log"));
        try {
            String body = utf16Body(script);
            String commandLine = "\"C:\\Program Files\\java.exe\" \"--value=one&two%PATH%\" \"тест\"";
            assertTrue(body.contains("wait 7"), body);
            assertTrue(body.contains("sh.Run \"" + commandLine.replace("\"", "\"\"") + "\", 1, False"), body);
            assertTrue(body.contains("CurrentDirectory = \"" + dir + "\""), body);
            assertFalse(body.contains("cmd /c"), body);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    @DisplayName("Posix relaunch script logs the wait, execs the command and removes itself")
    void writesPosixReplayScript(@TempDir Path dir) throws Exception {
        Path script = RelaunchScripts.writeReplaySh(dir, 7,
                List.of("java", "-jar", "/srv/built.jar"), dir, dir.resolve("relaunch.log"));
        try {
            String body = Files.readString(script, StandardCharsets.UTF_8);
            assertTrue(body.startsWith("#!/bin/sh\n"), body);
            assertTrue(body.contains("\"7\" >> \"$log\""), body);
            assertTrue(body.contains("while kill -0 7 2>/dev/null; do sleep 1; done"), body);
            assertTrue(body.contains("rm -- \"$0\""), body);
            assertTrue(body.contains("exec 'java' '-jar' '/srv/built.jar'"), body);
            assertFalse(body.contains("\r"), body);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    /** VBScript reads a BOM-prefixed UTF-16 file; CRLF keeps it runnable in any editor. */
    private static String utf16Body(Path script) throws Exception {
        byte[] raw = Files.readAllBytes(script);
        assertTrue(raw.length > 2 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xFE);
        String body = new String(raw, StandardCharsets.UTF_16LE);
        assertTrue(body.contains("\r\n"));
        assertFalse(body.replace("\r\n", "").contains("\n"));
        return body;
    }
}
