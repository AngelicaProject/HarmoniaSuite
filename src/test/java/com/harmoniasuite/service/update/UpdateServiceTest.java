package com.harmoniasuite.service.update;

import com.harmoniasuite.dto.UpdateState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    @Test
    @DisplayName("classifies local and divergent Git history explicitly")
    void classifiesHistory() {
        assertEquals(UpdateState.UP_TO_DATE, UpdateService.classifyHistory(0, 0));
        assertEquals(UpdateState.UPDATE_AVAILABLE, UpdateService.classifyHistory(3, 0));
        assertEquals(UpdateState.LOCAL_AHEAD, UpdateService.classifyHistory(0, 2));
        assertEquals(UpdateState.DIVERGED, UpdateService.classifyHistory(3, 2));
    }

    @Test
    @DisplayName("short sha is the first 7 characters")
    void shortensSha() {
        assertEquals("e916062", UpdateService.shortenSha("e916062abc123"));
        assertEquals("abc", UpdateService.shortenSha("abc"));
        assertEquals("", UpdateService.shortenSha(null));
    }

    @Test
    @DisplayName("unresolved commit gives empty")
    void blankCommitResolvesEmpty() {
        assertEquals("", UpdateService.resolveCommitSha(null));
        assertEquals("", UpdateService.resolveCommitSha("  "));
        assertEquals("", UpdateService.resolveCommitSha("@app.commit@"));
        assertEquals("e916062abc", UpdateService.resolveCommitSha("e916062abc"));
    }

    @Test
    @DisplayName("update build skips tests like release builds")
    void updateBuildSkipsTests() {
        List<String> command = UpdateService.mavenBuildCommand(Paths.get("D:/repo"));
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
    @DisplayName("root candidates stop at the first non-null result")
    void selectsFirstNonNullRoot() {
        List<String> attempts = new ArrayList<>();
        assertEquals("filesystem", UpdateService.firstNonNull(
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
        assertEquals("git", UpdateService.firstNonNull(
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
