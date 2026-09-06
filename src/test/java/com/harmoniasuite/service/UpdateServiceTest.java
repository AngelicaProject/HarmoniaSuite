package com.harmoniasuite.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateServiceTest {

    @Test
    @DisplayName("sha из ls-remote — первый токен первой строки")
    void parsesLsRemoteSha() {
        assertEquals("e916062abc", UpdateService.parseLsRemote("e916062abc\trefs/heads/main\n"));
        assertEquals("", UpdateService.parseLsRemote(""));
        assertEquals("", UpdateService.parseLsRemote(null));
    }

    @Test
    @DisplayName("короткий sha — первые 7 символов")
    void shortensSha() {
        assertEquals("e916062", UpdateService.shortSha("e916062abc123"));
        assertEquals("abc", UpdateService.shortSha("abc"));
        assertEquals("", UpdateService.shortSha(null));
    }

    @Test
    @DisplayName("режим по classpath: target/classes — dev, иначе jar")
    void detectsLaunchMode() {
        assertEquals("dev", UpdateService.launchMode("target/classes;C:/lib/app.jar"));
        assertEquals("jar", UpdateService.launchMode("C:/dist/harmonia-suite.jar"));
    }

    @Test
    @DisplayName("неподставленный коммит даёт пусто")
    void blankCommitResolvesEmpty() {
        assertEquals("", UpdateService.resolveCommit(null));
        assertEquals("", UpdateService.resolveCommit("  "));
        assertEquals("", UpdateService.resolveCommit("@app.commit@"));
        assertEquals("e916062abc", UpdateService.resolveCommit("e916062abc"));
    }

    @Test
    @DisplayName("мажор javac парсится из вывода версии")
    void parsesJavaMajor() {
        assertEquals(21, UpdateService.parseJavaMajor("javac 21.0.3"));
        assertEquals(11, UpdateService.parseJavaMajor("javac 11.0.24"));
        assertEquals(-1, UpdateService.parseJavaMajor("garbage"));
        assertEquals(-1, UpdateService.parseJavaMajor(null));
    }

    @Test
    @DisplayName("MinGit выбирается из ассетов релиза")
    void picksMinGitAsset() {
        assertEquals("https://example.com/m.zip", UpdateService.pickMinGitUrl(List.of(
                Map.of("name", "MinGit-2.51.0-busybox-64-bit.zip", "browser_download_url", "https://example.com/bb.zip"),
                Map.of("name", "Git-2.51.0-64-bit.exe", "browser_download_url", "https://example.com/g.exe"),
                Map.of("name", "MinGit-2.51.0-64-bit.zip", "browser_download_url", "https://example.com/m.zip"))));
        assertEquals(null, UpdateService.pickMinGitUrl(List.of(
                Map.of("name", "MinGit-2.51.0-32-bit.zip", "browser_download_url", "https://example.com/m32.zip"))));
        assertEquals(null, UpdateService.pickMinGitUrl(null));
    }

    @Test
    @DisplayName("перезапуск через -jar меняет только jar")
    void relaunchReplacesJar() {
        List<String> command = UpdateService.relaunchCommand(
                Paths.get("D:/repo"), "C:/java/bin/java.exe",
                List.of("-Xmx1G", "-jar", "D:/old/app.jar", "--server.port=18765"), null);
        assertEquals(List.of("C:/java/bin/java.exe", "-Xmx1G", "-jar",
                Paths.get("D:/repo/target/harmonia-suite.jar").toString(), "--server.port=18765"), command);
    }

    @Test
    @DisplayName("перезапуск exe идёт через bundled runtime и собранный jar")
    void relaunchExeUsesRuntime() {
        List<String> command = UpdateService.relaunchCommand(
                Paths.get("D:/repo"), "C:/install/HarmoniaSuite.exe",
                List.of("--server.port=18765"), Paths.get("C:/install/runtime/bin/java.exe"));
        assertEquals(List.of(Paths.get("C:/install/runtime/bin/java.exe").toString(), "-jar",
                Paths.get("D:/repo/target/harmonia-suite.jar").toString(), "--server.port=18765"), command);
    }

    @Test
    @DisplayName("перезапуск без jar повторяет команду как есть")
    void relaunchFallsBackToReplay() {
        List<String> command = UpdateService.relaunchCommand(
                Paths.get("D:/repo"), "C:/java/bin/java.exe", List.of("-version"), null);
        assertEquals(List.of("C:/java/bin/java.exe", "-version"), command);
    }
}
