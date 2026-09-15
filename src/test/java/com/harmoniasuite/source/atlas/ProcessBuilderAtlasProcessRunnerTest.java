package com.harmoniasuite.source.infrastructure.atlas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessBuilderAtlasProcessRunnerTest {

    private final ProcessBuilderAtlasProcessRunner runner = new ProcessBuilderAtlasProcessRunner();

    @Test
    @DisplayName("stdout and stderr are captured separately")
    void capturesSeparateStreams() {
        AtlasProcessResult result = runner.run(probe("streams"), Duration.ofSeconds(5), 1024, 1024);

        assertEquals(0, result.exitCode());
        assertEquals("atlas stdout", result.stdout());
        assertEquals("atlas stderr", result.stderr());
    }

    @Test
    @DisplayName("stdout is bounded and terminates a noisy child")
    void boundsStdout() {
        AtlasException exception = assertThrows(AtlasException.class,
                () -> runner.run(probe("stdout", "200000"), Duration.ofSeconds(5), 64, 1024));

        assertEquals(AtlasException.Reason.OUTPUT_LIMIT, exception.getReason());
    }

    @Test
    @DisplayName("stderr is bounded and terminates a noisy child")
    void boundsStderr() {
        AtlasException exception = assertThrows(AtlasException.class,
                () -> runner.run(probe("stderr", "200000"), Duration.ofSeconds(5), 1024, 64));

        assertEquals(AtlasException.Reason.OUTPUT_LIMIT, exception.getReason());
    }

    @Test
    @DisplayName("timeout terminates the child process")
    void timeoutTerminatesChild() {
        long started = System.nanoTime();

        AtlasException exception = assertThrows(AtlasException.class,
                () -> runner.run(probe("sleep", "10000"), Duration.ofMillis(100), 1024, 1024));

        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals(AtlasException.Reason.TIMEOUT, exception.getReason());
        assertTrue(elapsedMs < 3_000, "timeout should not leave a long-lived child: " + elapsedMs);
    }

    @Test
    @DisplayName("interruption terminates the child and restores interrupt status")
    void interruptionTerminatesChild() throws InterruptedException {
        AtomicReference<AtlasException.Reason> reason = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                runner.run(probe("sleep", "10000"), Duration.ofSeconds(30), 1024, 1024);
            } catch (AtlasException exception) {
                reason.set(exception.getReason());
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        worker.start();
        Thread.sleep(200);
        worker.interrupt();
        worker.join(3_000);

        assertFalse(worker.isAlive());
        assertEquals(AtlasException.Reason.INTERRUPTED, reason.get());
        assertTrue(interrupted.get());
    }

    @Test
    @DisplayName("failed process launch is typed")
    void failedLaunch() {
        AtlasException exception = assertThrows(AtlasException.class,
                () -> runner.run(List.of("definitely-not-a-real-atlas-executable"),
                        Duration.ofSeconds(1), 1024, 1024));

        assertEquals(AtlasException.Reason.LAUNCH_FAILURE, exception.getReason());
    }

    private static List<String> probe(String mode, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AtlasProcessProbe.class.getName());
        command.add(mode);
        command.addAll(Arrays.asList(arguments));
        return command;
    }

    private static Path javaExecutable() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName)
                .toAbsolutePath().normalize();
    }
}
