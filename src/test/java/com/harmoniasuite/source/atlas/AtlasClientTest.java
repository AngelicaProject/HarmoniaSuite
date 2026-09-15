package com.harmoniasuite.source.atlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasClientTest {

    private static final String SNAPSHOT_ID = "sha256:" + "a".repeat(64);
    private static final String CONTENT_ID = "sha256:" + "b".repeat(64);

    @TempDir
    Path tempDirectory;

    private AtlasProperties properties;
    private RecordingRunner runner;
    private Path hxsPath;

    @BeforeEach
    void setUp() throws IOException {
        properties = new AtlasProperties();
        properties.setExecutable(javaExecutable().toString());
        runner = new RecordingRunner(new AtlasProcessResult(0, validJson(), "diagnostics"));
        Path pathWithSpecialCharacters = tempDirectory.resolve("folder with spaces")
                .resolve("秘伝 source.hxs");
        Files.createDirectories(pathWithSpecialCharacters.getParent());
        hxsPath = Files.createFile(pathWithSpecialCharacters);
    }

    @Test
    @DisplayName("valid inspect JSON becomes strongly typed verified metadata")
    void validInspection() {
        AtlasInspection inspection = client().inspect(hxsPath);

        assertEquals(new AtlasInspection(1, "7.2.0", "en", "full", SNAPSHOT_ID,
                CONTENT_ID, "0.1.0", "7.7.0", 7912, 1801071, 2474141), inspection);
        assertEquals(List.of(javaExecutable().toString(), "inspect",
                hxsPath.toAbsolutePath().normalize().toString(), "--json"), runner.arguments);
        assertEquals(Duration.ofMillis(300_000), runner.timeout);
        assertEquals(1_048_576, runner.maxStdoutBytes);
        assertEquals(65_536, runner.maxStderrBytes);
    }

    @Test
    @DisplayName("inspect preserves paths with spaces and non-ASCII characters as one argv element")
    void specialPathIsOneArgument() {
        client().inspect(hxsPath);

        assertEquals(4, runner.arguments.size());
        assertEquals(hxsPath.toAbsolutePath().normalize().toString(), runner.arguments.get(2));
        assertTrue(runner.arguments.get(2).contains(" "));
        assertTrue(runner.arguments.get(2).contains("秘伝"));
    }

    @Test
    @DisplayName("nonzero inspect exit fails closed")
    void nonzeroExit() {
        runner.result = new AtlasProcessResult(7, validJson(), "not exposed");

        AtlasException exception = assertThrows(AtlasException.class,
                () -> client().inspect(hxsPath));

        assertEquals(AtlasException.Reason.NON_ZERO_EXIT, exception.getReason());
        assertTrue(!exception.getMessage().contains("not exposed"));
    }

    @Test
    @DisplayName("process launch failure is propagated as a typed error")
    void launchFailure() {
        runner.failure = new AtlasException(AtlasException.Reason.LAUNCH_FAILURE,
                "Atlas process could not be started");

        AtlasException exception = assertThrows(AtlasException.class,
                () -> client().inspect(hxsPath));

        assertEquals(AtlasException.Reason.LAUNCH_FAILURE, exception.getReason());
    }

    @Test
    @DisplayName("timeout is propagated as a typed error")
    void timeout() {
        runner.failure = new AtlasException(AtlasException.Reason.TIMEOUT,
                "Atlas process exceeded the verification timeout");

        AtlasException exception = assertThrows(AtlasException.class,
                () -> client().inspect(hxsPath));

        assertEquals(AtlasException.Reason.TIMEOUT, exception.getReason());
    }

    @Test
    @DisplayName("interruption is propagated as a typed error")
    void interruption() {
        runner.failure = new AtlasException(AtlasException.Reason.INTERRUPTED,
                "Atlas verification was interrupted");

        AtlasException exception = assertThrows(AtlasException.class,
                () -> client().inspect(hxsPath));

        assertEquals(AtlasException.Reason.INTERRUPTED, exception.getReason());
    }

    @Test
    @DisplayName("empty stdout is rejected")
    void emptyStdout() {
        runner.result = new AtlasProcessResult(0, "", "");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("malformed JSON is rejected")
    void malformedJson() {
        runner.result = new AtlasProcessResult(0, "{", "");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("multiple JSON documents are rejected")
    void multipleJsonDocuments() {
        runner.result = new AtlasProcessResult(0, validJson() + validJson(), "");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("missing required field is rejected")
    void missingField() {
        runner.result = new AtlasProcessResult(0,
                validJson().replace("\"luminaVersion\": \"7.7.0\",\n", ""), "");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("unknown HXS version is rejected")
    void unknownHxsVersion() {
        runner.result = resultWith("\"hxsVersion\": 2");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("non-full scope is rejected")
    void wrongScope() {
        runner.result = resultWith("\"scope\": \"partial\"");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("invalid snapshot ID is rejected")
    void invalidSnapshotId() {
        runner.result = resultWith("\"snapshotId\": \"sha256:ABC\"");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("invalid content ID is rejected")
    void invalidContentId() {
        runner.result = resultWith("\"contentId\": \"sha256:ABC\"");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("negative counts are rejected")
    void negativeCounts() {
        runner.result = resultWith("\"rowCount\": -1");

        assertReason(AtlasException.Reason.INVALID_OUTPUT);
    }

    @Test
    @DisplayName("missing or unusable executable fails before process launch")
    void missingExecutable() {
        properties.setExecutable(tempDirectory.resolve("missing-atlas").toString());

        AtlasException exception = assertThrows(AtlasException.class,
                () -> client().inspect(hxsPath));

        assertEquals(AtlasException.Reason.CONFIGURATION, exception.getReason());
        assertTrue(runner.arguments.isEmpty());
    }

    @Test
    @DisplayName("version success returns trimmed Atlas output")
    void versionSuccess() {
        runner.result = new AtlasProcessResult(0, "harmonia-atlas 0.1.0\r\n", "");

        assertEquals("harmonia-atlas 0.1.0", client().version());
        assertEquals(List.of(javaExecutable().toString(), "--version"), runner.arguments);
    }

    @Test
    @DisplayName("version failure is typed")
    void versionFailure() {
        runner.result = new AtlasProcessResult(1, "", "version failure");

        AtlasException exception = assertThrows(AtlasException.class, () -> client().version());

        assertEquals(AtlasException.Reason.NON_ZERO_EXIT, exception.getReason());
    }

    @Test
    @DisplayName("version empty output is rejected")
    void versionEmptyOutput() {
        runner.result = new AtlasProcessResult(0, " \n", "");

        AtlasException exception = assertThrows(AtlasException.class, () -> client().version());

        assertEquals(AtlasException.Reason.INVALID_OUTPUT, exception.getReason());
    }

    private void assertReason(AtlasException.Reason reason) {
        AtlasException exception = assertThrows(AtlasException.class,
                () -> client().inspect(hxsPath));
        assertEquals(reason, exception.getReason());
    }

    private AtlasProcessResult resultWith(String replacement) {
        String updated = validJson().replaceFirst("\\\"hxsVersion\\\": 1|\\\"scope\\\": \\\"full\\\"|"
                + "\\\"snapshotId\\\": \\\"" + SNAPSHOT_ID + "\\\"|"
                + "\\\"contentId\\\": \\\"" + CONTENT_ID + "\\\"|"
                + "\\\"rowCount\\\": 1801071", replacement);
        return new AtlasProcessResult(0, updated, "");
    }

    private AtlasClient client() {
        return new AtlasClient(properties, runner, new ObjectMapper());
    }

    private static Path javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName)
                .toAbsolutePath().normalize();
    }

    private static String validJson() {
        return """
                {
                  "hxsVersion": 1,
                  "gameVersion": "7.2.0",
                  "language": "en",
                  "scope": "full",
                  "snapshotId": "%s",
                  "contentId": "%s",
                  "extractorVersion": "0.1.0",
                  "luminaVersion": "7.7.0",
                  "sheetCount": 7912,
                  "rowCount": 1801071,
                  "stringCellCount": 2474141
                }
                """.formatted(SNAPSHOT_ID, CONTENT_ID);
    }

    private static final class RecordingRunner implements AtlasProcessRunner {

        private AtlasProcessResult result;
        private AtlasException failure;
        private List<String> arguments = new ArrayList<>();
        private Duration timeout;
        private int maxStdoutBytes;
        private int maxStderrBytes;

        private RecordingRunner(AtlasProcessResult result) {
            this.result = result;
        }

        @Override
        public AtlasProcessResult run(List<String> arguments, Duration timeout,
                                      int maxStdoutBytes, int maxStderrBytes) {
            this.arguments = List.copyOf(arguments);
            this.timeout = timeout;
            this.maxStdoutBytes = maxStdoutBytes;
            this.maxStderrBytes = maxStderrBytes;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
