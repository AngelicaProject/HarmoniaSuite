package com.harmoniasuite.service.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Renders the detached relaunch scripts from the templates in {@code resources/update}. */
final class RelaunchScripts {

    static final String SCRIPT_PREFIX = "harmonia-update-";

    private static final String EXE_TEMPLATE = "update/relaunch-exe.vbs";
    private static final String WINDOWS_TEMPLATE = "update/relaunch-windows.vbs";
    private static final String POSIX_TEMPLATE = "update/relaunch.sh";
    private static final char BOM = '\uFEFF';

    private RelaunchScripts() {
    }

    /** Installed exe: wait for this process, copy the built jar over the installed one, start the exe. */
    static Path writeExeScript(Path dir, long pid, Path built, Path appJar, Path exe, Path log)
            throws IOException {
        String body = render(EXE_TEMPLATE, Map.of(
                "log", vbsText(log.toString()),
                "pid", String.valueOf(pid),
                "built", vbsText(built.toString()),
                "appJar", vbsText(appJar.toString()),
                "exe", vbsText(exe.toString())));
        return write(dir, ".vbs", body);
    }

    static Path writeReplayVbs(Path dir, long pid, List<String> command, Path cwd, Path log)
            throws IOException {
        String body = render(WINDOWS_TEMPLATE, Map.of(
                "log", vbsText(log.toString()),
                "pid", String.valueOf(pid),
                "cwd", vbsText(cwd.toString()),
                "command", vbsText(commandLine(command))));
        return write(dir, ".vbs", body);
    }

    static Path writeReplaySh(Path dir, long pid, List<String> command, Path cwd, Path log)
            throws IOException {
        String body = render(POSIX_TEMPLATE, Map.of(
                "log", shellLiteral(log.toString()),
                "pid", String.valueOf(pid),
                "cwd", shellLiteral(cwd.toString()),
                "command", command.stream()
                        .map(RelaunchScripts::shellLiteral)
                        .collect(Collectors.joining(" "))));
        return write(dir, ".sh", body);
    }

    private static Path write(Path dir, String suffix, String body) throws IOException {
        boolean vbs = ".vbs".equals(suffix);
        Files.createDirectories(dir);
        Path script = Files.createTempFile(dir, SCRIPT_PREFIX, suffix);
        try {
            String lineEnded = body.replace("\r\n", "\n");
            String text = vbs
                    ? BOM + lineEnded.replace("\n", "\r\n")
                    : lineEnded;
            Files.write(script, text.getBytes(vbs ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8));
            return script;
        } catch (IOException e) {
            Files.deleteIfExists(script);
            throw e;
        }
    }

    private static String render(String resource, Map<String, String> values) throws IOException {
        String body = read(resource);
        for (Map.Entry<String, String> value : values.entrySet()) {
            body = body.replace("{{" + value.getKey() + "}}", value.getValue());
        }
        if (body.contains("{{")) {
            throw new IOException("unfilled placeholder in " + resource);
        }
        return body;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = RelaunchScripts.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IOException("missing script template " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String commandLine(List<String> command) {
        return command.stream()
                .map(RelaunchScripts::windowsLiteral)
                .collect(Collectors.joining(" "));
    }

    /** The templates quote every value themselves; only embedded quotes need doubling. */
    private static String vbsText(String value) {
        return value.replace("\"", "\"\"");
    }

    private static String shellLiteral(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** Windows command-line quoting: double the backslashes in front of a quote. */
    private static String windowsLiteral(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\') {
                backslashes++;
            } else if (current == '"') {
                appendBackslashes(quoted, backslashes * 2 + 1);
                quoted.append('"');
                backslashes = 0;
            } else {
                appendBackslashes(quoted, backslashes);
                quoted.append(current);
                backslashes = 0;
            }
        }
        appendBackslashes(quoted, backslashes * 2);
        return quoted.append('"').toString();
    }

    private static void appendBackslashes(StringBuilder target, int count) {
        target.append("\\".repeat(count));
    }
}
