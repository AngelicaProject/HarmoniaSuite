package com.harmoniasuite.service.update;

import com.harmoniasuite.dto.UpdateState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    @Test
    @DisplayName("ls-remote sha is the first token of the first line")
    void parsesLsRemoteSha() {
        assertEquals("e916062abc", UpdateService.parseLsRemote("e916062abc\trefs/heads/main\n"));
        assertEquals("", UpdateService.parseLsRemote(""));
        assertEquals("", UpdateService.parseLsRemote(null));
    }

    @Test
    @DisplayName("classifies local and divergent Git history explicitly")
    void classifiesHistory() {
        assertEquals(UpdateState.UP_TO_DATE, UpdateService.historyState(0, 0));
        assertEquals(UpdateState.UPDATE_AVAILABLE, UpdateService.historyState(3, 0));
        assertEquals(UpdateState.LOCAL_AHEAD, UpdateService.historyState(0, 2));
        assertEquals(UpdateState.DIVERGED, UpdateService.historyState(3, 2));
    }

    @Test
    @DisplayName("short sha is the first 7 characters")
    void shortensSha() {
        assertEquals("e916062", UpdateService.shortSha("e916062abc123"));
        assertEquals("abc", UpdateService.shortSha("abc"));
        assertEquals("", UpdateService.shortSha(null));
    }

    @Test
    @DisplayName("unresolved commit gives empty")
    void blankCommitResolvesEmpty() {
        assertEquals("", UpdateService.resolveCommit(null));
        assertEquals("", UpdateService.resolveCommit("  "));
        assertEquals("", UpdateService.resolveCommit("@app.commit@"));
        assertEquals("e916062abc", UpdateService.resolveCommit("e916062abc"));
    }

    @Test
    @DisplayName("javac major parses from version output")
    void parsesJavaMajor() {
        assertEquals(21, UpdateService.parseJavaMajor("javac 21.0.3"));
        assertEquals(11, UpdateService.parseJavaMajor("javac 11.0.24"));
        assertEquals(-1, UpdateService.parseJavaMajor("garbage"));
        assertEquals(-1, UpdateService.parseJavaMajor(null));
    }

    @Test
    @DisplayName("MinGit is picked from release assets")
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
    @DisplayName("update build skips tests like release builds")
    void updateBuildSkipsTests() {
        List<String> command = UpdateService.buildCommand(Paths.get("D:/repo"));
        assertTrue(command.get(0).contains("mvnw"));
        assertEquals(List.of("-B", "-DskipTests", "clean", "package"), command.subList(1, command.size()));
    }

    @Test
    @DisplayName("scrub masks credentials in URLs but keeps ordinary URLs")
    void scrubsUrlCredentials() {
        assertEquals("https://***@example.com/path", UpdateService.scrub("https://user:pass@example.com/path"));
        assertEquals("https://example.com/path", UpdateService.scrub("https://example.com/path"));
        assertNull(UpdateService.scrub(null));
        assertEquals("  ", UpdateService.scrub("  "));
    }

    @Test
    @DisplayName("failure formatting keeps only the recent capped output")
    void formatsFailureTail() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            lines.add("line-" + i);
        }
        String failure = UpdateService.formatFailure(List.of("git", "pull"), 128, lines);
        assertTrue(failure.startsWith("git pull: код 128"));
        assertTrue(failure.contains("line-25"));
        assertFalse(failure.contains("line-5"));

        String huge = UpdateService.formatFailure("git pull", 1, List.of("x".repeat(5000)));
        assertTrue(huge.length() < 4200);
        assertEquals("git pull: код 1", UpdateService.formatFailure("git pull", 1, List.of()));
    }

    @Test
    @DisplayName("root candidates stop at the first available result")
    void selectsFirstAvailableRoot() {
        List<String> attempts = new ArrayList<>();
        assertEquals("filesystem", UpdateService.firstAvailable(
                () -> {
                    attempts.add("git");
                    return null;
                },
                () -> {
                    attempts.add("filesystem");
                    return "filesystem";
                },
                () -> {
                    attempts.add("unexpected");
                    return "unexpected";
                }));
        assertEquals(List.of("git", "filesystem"), attempts);

        attempts.clear();
        assertEquals("git", UpdateService.firstAvailable(
                () -> {
                    attempts.add("git");
                    return "git";
                },
                () -> {
                    attempts.add("filesystem");
                    return "filesystem";
                }));
        assertEquals(List.of("git"), attempts);
    }
}
