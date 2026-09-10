package com.harmoniasuite.service.update;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateRelaunchTest {

    @Test
    @DisplayName("classpath mode: target/classes means dev, else jar")
    void detectsLaunchMode() {
        assertEquals("dev", UpdateRelaunch.launchMode("target/classes;C:/lib/app.jar"));
        assertEquals("dev", UpdateRelaunch.launchMode("C:\\repo\\target\\classes;C:\\lib\\app.jar"));
        assertEquals("jar", UpdateRelaunch.launchMode("C:/dist/harmonia-suite.jar"));
    }

    @Test
    @DisplayName("jar relaunch replaces only the jar")
    void relaunchReplacesJar() {
        List<String> command = UpdateRelaunch.relaunchCommand(
                Paths.get("D:/repo"), "C:/java/bin/java.exe",
                List.of("-Xmx1G", "-jar", "D:/old/app.jar", "--server.port=18765"), null);
        assertEquals(List.of("C:/java/bin/java.exe", "-Xmx1G", "-jar",
                Paths.get("D:/repo/target/harmonia-suite.jar").toString(), "--server.port=18765"), command);
    }

    @Test
    @DisplayName("exe relaunch uses the bundled runtime and built jar")
    void relaunchExeUsesRuntime() {
        List<String> command = UpdateRelaunch.relaunchCommand(
                Paths.get("D:/repo"), "C:/install/HarmoniaSuite.exe",
                List.of("--server.port=18765"), Paths.get("C:/install/runtime/bin/java.exe"));
        assertEquals(List.of(Paths.get("C:/install/runtime/bin/java.exe").toString(), "-jar",
                Paths.get("D:/repo/target/harmonia-suite.jar").toString(), "--server.port=18765"), command);
    }

    @Test
    @DisplayName("relaunch without a jar replays the command as is")
    void relaunchFallsBackToReplay() {
        List<String> command = UpdateRelaunch.relaunchCommand(
                Paths.get("D:/repo"), "C:/java/bin/java.exe", List.of("-version"), null);
        assertEquals(List.of("C:/java/bin/java.exe", "-version"), command);
    }

    @Test
    @DisplayName("no exe path outside install")
    void noExeOutsideInstall() {
        assertNull(UpdateRelaunch.exeInstallPath());
    }

    @Test
    @DisplayName("Detached relaunch script inherits stdin instead of a write-only redirect")
    void detachedScriptInheritsStdin() {
        ProcessBuilder builder = UpdateRelaunch.detachRedirects(new ProcessBuilder("probe"));
        assertSame(ProcessBuilder.Redirect.INHERIT, builder.redirectInput());
        assertSame(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput());
        assertSame(ProcessBuilder.Redirect.DISCARD, builder.redirectError());
    }

    @Test
    @DisplayName("built jar lives in the target directory of the source checkout")
    void buildsJarPath() {
        assertEquals(Paths.get("D:/repo/target/harmonia-suite.jar"), UpdateRelaunch.builtJar(Paths.get("D:/repo")));
    }

    @Test
    @DisplayName("jar run rebuilds its launch arguments when the process reports none")
    void rebuildsJarLaunchArgs() {
        assertEquals(List.of("-jar", "C:/dist/harmonia-suite.jar"),
                UpdateRelaunch.launchArgs(List.of(), "C:/dist/harmonia-suite.jar"));
        assertEquals(List.of("-Xmx1G"),
                UpdateRelaunch.launchArgs(List.of("-Xmx1G"), "C:/dist/harmonia-suite.jar"));
        assertEquals(List.of(),
                UpdateRelaunch.launchArgs(List.of(), "C:/repo/target/classes;C:/lib/a.jar"));
        assertEquals(List.of(), UpdateRelaunch.launchArgs(List.of(), ""));
    }

    @Test
    @DisplayName("script host that reports its wait step counts as started")
    void detectsStartedScript(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("relaunch.log");
        // The log is written by the script host in the console charset, not in UTF-8.
        Files.write(log, "10.09.2026 16:21:39 привет консоль\r\n".getBytes("Cp1251"));
        Files.write(log, " wait 4242\r\n".getBytes(StandardCharsets.ISO_8859_1), StandardOpenOption.APPEND);

        assertDoesNotThrow(() -> UpdateRelaunch.awaitStart(log, 4242L, Duration.ofMillis(100)));
    }

    @Test
    @DisplayName("script host that never reports its wait step fails the update instead of killing it")
    void rejectsSilentScript(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("relaunch.log");
        Files.writeString(log, "wait 1\r\n", StandardCharsets.ISO_8859_1);

        assertThrows(IOException.class,
                () -> UpdateRelaunch.awaitStart(log, 4242L, Duration.ofMillis(100)));
        assertThrows(IOException.class,
                () -> UpdateRelaunch.awaitStart(dir.resolve("missing.log"), 4242L, Duration.ofMillis(100)));
    }

    @Test
    @DisplayName("stale relaunch scripts are pruned, fresh ones and other files are kept")
    void prunesStaleScripts(@TempDir Path dir) throws Exception {
        Path stale = Files.writeString(dir.resolve("harmonia-update-1.vbs"), "x");
        Path fresh = Files.writeString(dir.resolve("harmonia-update-2.vbs"), "x");
        Path foreign = Files.writeString(dir.resolve("other.vbs"), "x");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(foreign, FileTime.fromMillis(1_000_000));

        UpdateRelaunch.prune(dir, System.currentTimeMillis() - Duration.ofHours(1).toMillis());

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(foreign));
    }
}
