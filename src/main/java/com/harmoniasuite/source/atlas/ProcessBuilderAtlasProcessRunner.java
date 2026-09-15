package com.harmoniasuite.source.atlas;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs Atlas directly with ProcessBuilder and bounded, independent stream capture. */
@Component
public class ProcessBuilderAtlasProcessRunner implements AtlasProcessRunner {

    private static final long TERMINATION_GRACE_MS = 250;
    private static final int BUFFER_SIZE = 8192;

    private final ThreadFactory readerThreadFactory = new ReaderThreadFactory();

    @Override
    public AtlasProcessResult run(List<String> arguments, Duration timeout,
                                 int maxStdoutBytes, int maxStderrBytes) {
        validateArguments(arguments, timeout, maxStdoutBytes, maxStderrBytes);

        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(List.copyOf(arguments));
            processBuilder.redirectErrorStream(false);
            process = processBuilder.start();
        } catch (IOException | SecurityException exception) {
            throw new AtlasException(AtlasException.Reason.LAUNCH_FAILURE,
                    "Atlas process could not be started", exception);
        }

        AtomicBoolean outputLimitExceeded = new AtomicBoolean(false);
        ExecutorService readers = Executors.newFixedThreadPool(2, readerThreadFactory);
        Future<CapturedOutput> stdout = readers.submit(() -> capture(
                process.getInputStream(), maxStdoutBytes,
                () -> {
                    outputLimitExceeded.set(true);
                    terminate(process);
                }));
        Future<CapturedOutput> stderr = readers.submit(() -> capture(
                process.getErrorStream(), maxStderrBytes,
                () -> {
                    outputLimitExceeded.set(true);
                    terminate(process);
                }));

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminate(process);
                throw new AtlasException(AtlasException.Reason.TIMEOUT,
                        "Atlas process exceeded the verification timeout");
            }

            CapturedOutput capturedStdout = getCapture(stdout);
            CapturedOutput capturedStderr = getCapture(stderr);
            if (outputLimitExceeded.get()
                    || capturedStdout.limitExceeded()
                    || capturedStderr.limitExceeded()) {
                throw new AtlasException(AtlasException.Reason.OUTPUT_LIMIT,
                        "Atlas process output exceeded the configured limit");
            }
            return new AtlasProcessResult(process.exitValue(),
                    capturedStdout.text(), capturedStderr.text());
        } catch (InterruptedException exception) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new AtlasException(AtlasException.Reason.INTERRUPTED,
                    "Atlas verification was interrupted", exception);
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
            readers.shutdownNow();
            awaitReaderShutdown(readers);
        }
    }

    private static void validateArguments(List<String> arguments, Duration timeout,
                                          int maxStdoutBytes, int maxStderrBytes) {
        if (arguments == null || arguments.isEmpty()
                || arguments.stream().anyMatch(argument -> argument == null || argument.isEmpty())) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Atlas process arguments must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Atlas verification timeout must be positive");
        }
        if (maxStdoutBytes < 1 || maxStderrBytes < 1) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Atlas output limits must be positive");
        }
    }

    private static CapturedOutput getCapture(Future<CapturedOutput> capture)
            throws InterruptedException {
        try {
            return capture.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof AtlasException atlasException) {
                throw atlasException;
            }
            throw new AtlasException(AtlasException.Reason.PROCESS_IO,
                    "Atlas process output could not be captured", cause);
        }
    }

    private static CapturedOutput capture(InputStream stream, int maxBytes, Runnable onLimitExceeded) {
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, BUFFER_SIZE));
            byte[] buffer = new byte[BUFFER_SIZE];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = maxBytes - total;
                if (read > remaining) {
                    if (remaining > 0) {
                        output.write(buffer, 0, remaining);
                    }
                    onLimitExceeded.run();
                    return new CapturedOutput(output.toString(StandardCharsets.UTF_8), true);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return new CapturedOutput(output.toString(StandardCharsets.UTF_8), false);
        } catch (IOException exception) {
            throw new AtlasException(AtlasException.Reason.PROCESS_IO,
                    "Atlas process output could not be read", exception);
        }
    }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = descendantsOf(process);
        destroyDescendants(descendants, false);
        process.destroy();
        waitBriefly(process);
        if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            destroyDescendants(descendants, true);
            process.destroyForcibly();
            waitBriefly(process);
        }
    }

    private static List<ProcessHandle> descendantsOf(Process process) {
        try {
            return process.descendants().toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static void destroyDescendants(List<ProcessHandle> descendants, boolean forcibly) {
        for (ProcessHandle descendant : descendants) {
            if (forcibly) {
                descendant.destroyForcibly();
            } else {
                descendant.destroy();
            }
        }
    }

    private static void waitBriefly(Process process) {
        try {
            process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitReaderShutdown(ExecutorService readers) {
        try {
            readers.awaitTermination(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record CapturedOutput(String text, boolean limitExceeded) {
    }

    private static final class ReaderThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "atlas-output-reader-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
