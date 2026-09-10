package com.harmoniasuite.service.update;

import com.harmoniasuite.config.InstallLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the freshly built version once this process is gone. */
final class UpdateRelaunch {

    static final String BUILT_JAR = "harmonia-suite.jar";

    private static final String WORK_DIR = "harmonia-suite";
    private static final String LOG_FILE = "relaunch.log";
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STALE_SCRIPT_AGE = Duration.ofHours(1);
    private static final long POLL_MILLIS = 50;
    private static final Logger logger = LoggerFactory.getLogger(UpdateRelaunch.class);

    private UpdateRelaunch() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static String detectLaunchMode(String classPath) {
        String normalized = classPath == null ? "" : classPath.replace('\\', '/');
        return normalized.contains("target/classes") ? "dev" : "jar";
    }

    static List<String> relaunchCommand(Path root) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String javaBin = info.command().orElse(defaultJavaBin());
        List<String> args = launchArgs(
                info.arguments().map(List::of).orElse(List.of()),
                System.getProperty("java.class.path", ""));
        return relaunchCommand(root, javaBin, args, bundledRuntimeJava());
    }

    /** Windows reports no launch arguments for the current process, so a jar run rebuilds them. */
    static List<String> launchArgs(List<String> reported, String classPath) {
        if (!reported.isEmpty()) {
            return reported;
        }
        if (!"jar".equals(detectLaunchMode(classPath)) || classPath.isBlank()) {
            return reported;
        }
        return List.of("-jar", classPath);
    }

    static Path builtJar(Path root) {
        return root.resolve("target").resolve(BUILT_JAR);
    }

    static List<String> relaunchCommand(Path root, String javaBin, List<String> args, Path runtimeJava) {
        Path builtJar = builtJar(root);
        int jarFlag = args.indexOf("-jar");
        if (jarFlag >= 0) {
            List<String> command = new ArrayList<>();
            command.add(javaBin);
            command.addAll(args.subList(0, jarFlag));
            command.add("-jar");
            command.add(builtJar.toString());
            if (jarFlag + 2 <= args.size()) {
                command.addAll(args.subList(jarFlag + 2, args.size()));
            }
            return command;
        }
        if (runtimeJava != null) {
            List<String> command = new ArrayList<>();
            command.add(runtimeJava.toString());
            command.add("-jar");
            command.add(builtJar.toString());
            command.addAll(args);
            return command;
        }
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.addAll(args);
        return command;
    }

    private static String defaultJavaBin() {
        return Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
    }

    static Path exeInstallPath() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            return null;
        }
        try {
            Path exe = Paths.get(appPath);
            return Files.isRegularFile(exe) ? exe : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes the relaunch script for this install shape, starts it detached and checks that it
     * reached its wait step — a script host that never ran would otherwise only show up as the
     * application disappearing.
     */
    static void relaunch(Path root, Consumer<String> log) throws IOException {
        long pid = ProcessHandle.current().pid();
        Path dir = workDir();
        Path logFile = dir.resolve(LOG_FILE);
        Files.deleteIfExists(logFile);
        pruneScripts(dir);
        Path exe = exeInstallPath();
        Path appDir = InstallLayout.appDir();
        Path script;
        if (exe != null && appDir != null) {
            script = RelaunchScripts.writeExeScript(dir, pid, builtJar(root),
                    appDir.resolve(BUILT_JAR), exe, logFile);
        } else if (isWindows()) {
            script = RelaunchScripts.writeReplayVbs(dir, pid, relaunchCommand(root), userDir(), logFile);
        } else {
            script = RelaunchScripts.writeReplaySh(dir, pid, relaunchCommand(root), userDir(), logFile);
        }
        try {
            log.accept("Перезапуск");
            detach(script);
            awaitStart(logFile, pid, START_TIMEOUT);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(script);
            throw e;
        }
    }

    static void detach(Path script) throws IOException {
        List<String> command = isWindows()
                ? List.of("wscript", "//Nologo", "//B", script.toString())
                : List.of("sh", script.toString());
        detachRedirects(new ProcessBuilder(command)).start();
    }

    /** Redirect.DISCARD is write-only, so stdin must stay inheritable. */
    static ProcessBuilder detachRedirects(ProcessBuilder builder) {
        return builder
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    /** Waits for the script host to report its wait step; a silent script means the app would die. */
    static void awaitStart(Path logFile, long pid, Duration timeout) throws IOException {
        String marker = " wait " + pid;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (hasStarted(logFile, marker)) {
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("ожидание перезапуска прервано", e);
            }
        }
        throw new IOException("скрипт перезапуска не запустился: " + logFile);
    }

    private static boolean hasStarted(Path logFile, String marker) {
        try {
            byte[] content = Files.readAllBytes(logFile);
            return new String(content, StandardCharsets.ISO_8859_1).contains(marker);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Path workDir() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir")).resolve(WORK_DIR);
        Files.createDirectories(dir);
        return dir;
    }

    /** Scripts left behind by updates that never got that far are dead weight. */
    private static void pruneScripts(Path dir) {
        Path tempRoot = Paths.get(System.getProperty("java.io.tmpdir"));
        long cutoff = System.currentTimeMillis() - STALE_SCRIPT_AGE.toMillis();
        // Older builds wrote their scripts and log straight into the temp root.
        prune(tempRoot, cutoff);
        deleteQuietly(tempRoot.resolve("harmonia-relaunch.log"));
        prune(dir, cutoff);
    }

    static void prune(Path dir, long cutoff) {
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .startsWith(RelaunchScripts.SCRIPT_PREFIX))
                    .filter(path -> lastModifiedMillis(path) < cutoff)
                    .forEach(UpdateRelaunch::deleteQuietly);
        } catch (IOException e) {
            logger.debug("Не удалось удалить старые скрипты обновления", e);
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.debug("Не удалось удалить скрипт перезапуска {}", path, e);
        }
    }

    private static Path userDir() {
        return Paths.get(System.getProperty("user.dir"));
    }

    private static Path bundledRuntimeJava() {
        Path app = InstallLayout.appDir();
        if (app == null || app.getParent() == null) {
            return null;
        }
        Path java = app.getParent().resolve("runtime").resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java");
        return Files.isRegularFile(java) ? java : null;
    }
}
