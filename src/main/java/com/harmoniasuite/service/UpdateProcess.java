package com.harmoniasuite.service;

import com.harmoniasuite.exception.UpdateErrorCode;
import com.harmoniasuite.exception.UpdateException;
import com.harmoniasuite.util.ScrubSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class UpdateProcess {

    private static final int FAILURE_TAIL_LINES = 20;
    private static final int FAILURE_TAIL_CHARS = 4096;
    private static final Pattern SENSITIVE_ENV = Pattern.compile(
            "(?i).*(api[_-]?key|access[_-]?token|auth[_-]?token|password|passwd|secret|private[_-]?key).*");

    static Map<String, String> sanitizedEnvironment(Map<String, String> overrides) {
        Map<String, String> result = new HashMap<>(System.getenv());
        result.keySet().removeIf(UpdateProcess::isSensitiveEnvironmentName);
        result.remove("JAVA_TOOL_OPTIONS");
        result.remove("JDK_JAVA_OPTIONS");
        result.remove("MAVEN_OPTS");
        result.remove("MAVEN_ARGS");
        if (overrides != null) {
            overrides.forEach((name, value) -> {
                if (!isSensitiveEnvironmentName(name)) {
                    result.put(name, value);
                }
            });
        }
        return result;
    }

    private static boolean isSensitiveEnvironmentName(String name) {
        return name != null && (SENSITIVE_ENV.matcher(name).matches()
                || name.equals("JAVA_TOOL_OPTIONS")
                || name.equals("JDK_JAVA_OPTIONS")
                || name.equals("MAVEN_OPTS")
                || name.equals("MAVEN_ARGS"));
    }

    static Result runProcess(Path dir, List<String> command, Consumer<String> log, Map<String, String> env)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(sanitizedEnvironment(env));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GCM_INTERACTIVE", "never");
        builder.environment().put("GIT_CONFIG_COUNT", "2");
        builder.environment().put("GIT_CONFIG_KEY_0", "credential.helper");
        builder.environment().put("GIT_CONFIG_VALUE_0", "");
        builder.environment().put("GIT_CONFIG_KEY_1", "http.https://github.com/.extraheader");
        builder.environment().put("GIT_CONFIG_VALUE_1", "");
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> {
                    String safe = scrub(line);
                    lines.add(safe);
                    log.accept(safe);
                });
            }
            return new Result(process.waitFor(), lines);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateErrorCode.INTERRUPTED, e);
        } catch (IOException e) {
            process.destroyForcibly();
            throw e;
        } catch (RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    static String captureOutput(Path dir, Consumer<String> log, String... command) throws IOException {
        Result result = runProcess(dir, List.of(command), log, Map.of());
        if (result.code() != 0) {
            throw new IOException(formatFailure(List.of(command), result.code(), result.output()));
        }
        return String.join("\n", result.output()).strip();
    }

    static void requireSuccess(Path dir, List<String> command, Consumer<String> log) throws IOException {
        Result result = runProcess(dir, command, log, Map.of());
        if (result.code() != 0) {
            throw new IOException(formatFailure(command, result.code(), result.output()));
        }
    }

    static String scrub(String value) {
        return ScrubSupport.scrub(value);
    }

    static String formatFailure(List<String> command, int code, List<String> lines) {
        String cmd = command == null ? "" : String.join(" ", command);
        return formatFailure(cmd, code, lines);
    }

    static String formatFailure(String command, int code, List<String> lines) {
        String prefix = scrub(command == null ? "" : command) + ": код " + code;
        if (lines == null || lines.isEmpty()) {
            return prefix;
        }
        StringBuilder tail = new StringBuilder();
        int start = Math.max(0, lines.size() - FAILURE_TAIL_LINES);
        for (int i = start; i < lines.size(); i++) {
            String line = scrub(lines.get(i));
            if (line == null) {
                continue;
            }
            if (tail.length() > 0) {
                tail.append('\n');
            }
            tail.append(line);
        }
        if (tail.length() == 0) {
            return prefix;
        }
        if (tail.length() > FAILURE_TAIL_CHARS) {
            tail.delete(0, tail.length() - FAILURE_TAIL_CHARS);
        }
        return prefix + ": " + tail;
    }

    public record Result(int code, List<String> output) {
    }
}
