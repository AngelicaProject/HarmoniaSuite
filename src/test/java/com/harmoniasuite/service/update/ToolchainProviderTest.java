package com.harmoniasuite.service.update;

import com.harmoniasuite.exception.UpdateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolchainProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("javac major parses from version output")
    void parsesJavacMajorVersion() {
        assertEquals(21, ToolchainProvider.parseJavacMajorVersion("javac 21.0.3"));
        assertEquals(11, ToolchainProvider.parseJavacMajorVersion("javac 11.0.24"));
        assertEquals(-1, ToolchainProvider.parseJavacMajorVersion("garbage"));
        assertEquals(-1, ToolchainProvider.parseJavacMajorVersion(null));
    }

    @Test
    @DisplayName("MinGit is picked from release assets")
    void picksMinGitDownloadUrl() {
        assertEquals("https://example.com/m.zip", ToolchainProvider.minGitDownloadUrl(List.of(
                Map.of("name", "MinGit-2.51.0-busybox-64-bit.zip", "browser_download_url", "https://example.com/bb.zip"),
                Map.of("name", "Git-2.51.0-64-bit.exe", "browser_download_url", "https://example.com/g.exe"),
                Map.of("name", "MinGit-2.51.0-64-bit.zip", "browser_download_url", "https://example.com/m.zip"))));
        assertEquals(null, ToolchainProvider.minGitDownloadUrl(List.of(
                Map.of("name", "MinGit-2.51.0-32-bit.zip", "browser_download_url", "https://example.com/m32.zip"))));
        assertEquals(null, ToolchainProvider.minGitDownloadUrl(null));
    }

    @Test
    @DisplayName("Node.js requirement comes from the checkout and matches exactly")
    void readsNodeVersionRequirement() throws Exception {
        Path frontend = Files.createDirectories(tempDir.resolve("frontend"));
        Files.writeString(frontend.resolve(".node-version"), "v24.15.0\n");
        assertEquals("24.15.0", ToolchainProvider.requiredNodeVersion(tempDir));
        assertEquals(true, ToolchainProvider.isCompatibleNodeVersion("v24.15.0", "24.15.0"));
        assertEquals(false, ToolchainProvider.isCompatibleNodeVersion("v24.14.0", "24.15.0"));
    }

    @Test
    @DisplayName("invalid or missing Node.js requirement is rejected")
    void rejectsInvalidOrMissingNodeVersion() throws Exception {
        Path frontend = Files.createDirectories(tempDir.resolve("frontend"));
        Path versionFile = frontend.resolve(".node-version");
        Files.writeString(versionFile, "latest\n");

        assertThrows(UpdateException.class, () -> ToolchainProvider.requiredNodeVersion(tempDir));

        Files.delete(versionFile);
        assertThrows(UpdateException.class, () -> ToolchainProvider.requiredNodeVersion(tempDir));
    }

    @Test
    @DisplayName("npm executable resolves beside the selected Node.js home")
    void resolvesNpmExecutable() throws Exception {
        String npmName = UpdateRelaunch.isWindows() ? "npm.cmd" : "npm";
        Path npm = Files.createFile(tempDir.resolve(npmName));

        assertEquals(npm.toString(), ToolchainProvider.npmBinary(tempDir.toString()));
        assertThrows(UpdateException.class,
                () -> ToolchainProvider.npmBinary(tempDir.resolve("missing").toString()));
    }

    @Test
    @DisplayName("Node.js home is prepended to a child process PATH")
    void prependsNodeHomeToPath() {
        assertEquals("node" + File.pathSeparator + "system",
                ToolchainProvider.prependToPath("node", "system"));
        assertEquals("node", ToolchainProvider.prependToPath("node", null));
        assertEquals("system", ToolchainProvider.prependToPath(null, "system"));
    }
}
