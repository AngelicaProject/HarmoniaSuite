package com.harmoniasuite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.config.AppVersion;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.InstallLayout;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.util.ScrubSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UpdateService {

    private static final String REMOTE = "origin";
    private static final String BRANCH = "main";
    private static final int MAX_SUBJECTS = 10;
    private static final String BUILT_JAR = "harmonia-suite.jar";
    private static final String MINGIT_RELEASES_URL =
            "https://api.github.com/repos/git-for-windows/git/releases/latest";
    private static final String TEMURIN_URL =
            "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse";
    private static final Pattern JAVAC_VERSION = Pattern.compile("javac (\\d+)");
    private static final int FAILURE_TAIL_LINES = 20;
    private static final int FAILURE_TAIL_CHARS = 4096;
    private static final int HTTP_BODY_CHARS = 4096;
    private static final Logger logger = LoggerFactory.getLogger(UpdateService.class);

    private final HarmoniaProperties properties;
    private final ObjectMapper objectMapper;

    public UpdateService(HarmoniaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        String version = AppVersion.resolve(properties.getApp().getVersion(), "dev");
        map.put("version", version);
        String gitBin = probeGit();
        Path root = gitBin != null ? repoRoot(gitBin) : repoRootFs();
        if (root == null) {
            map.put("supported", false);
            return map;
        }
        gitStatus(root, gitBin, map);
        return map;
    }

    public void runUpdate(Consumer<String> log) throws Exception {
        String gitBin = ensureGit(log, objectMapper);
        Path root = repoRoot(gitBin);
        if (root == null) {
            root = repoRootFs();
        }
        if (root == null) {
            throw new HarmoniaSuiteBadRequestException("Обновление недоступно: нет исходников");
        }
        runGitUpdate(root, log, gitBin, ensureJava(log));
    }

    static String parseLsRemote(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String[] parts = output.strip().split("\\s+");
        return parts.length > 0 ? parts[0] : "";
    }

    static String shortSha(String sha) {
        if (sha == null) {
            return "";
        }
        return sha.length() >= 7 ? sha.substring(0, 7) : sha;
    }

    static String launchMode(String classPath) {
        return classPath != null && classPath.contains("target/classes") ? "dev" : "jar";
    }

    public static String resolveCommit(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("@")) {
            return "";
        }
        return raw.strip();
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

    static String pickMinGitUrl(List<Map<String, String>> assets) {
        if (assets == null) {
            return null;
        }
        for (Map<String, String> asset : assets) {
            String name = asset.get("name");
            String lower = name == null ? "" : name.toLowerCase();
            if (name != null && name.startsWith("MinGit-") && lower.endsWith("64-bit.zip")
                    && !lower.contains("busybox")) {
                return asset.get("browser_download_url");
            }
        }
        return null;
    }

    static int parseJavaMajor(String javacOutput) {
        if (javacOutput == null) {
            return -1;
        }
        Matcher matcher = JAVAC_VERSION.matcher(javacOutput);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static List<String> relaunchCommand(Path root) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String javaBin = info.command().orElse(defaultJavaBin());
        List<String> args = new ArrayList<>(info.arguments().map(List::of).orElse(List.of()));
        return relaunchCommand(root, javaBin, args, bundledRuntimeJava());
    }

    static List<String> relaunchCommand(Path root, String javaBin, List<String> args, Path runtimeJava) {
        Path builtJar = root.resolve("target").resolve(BUILT_JAR);
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

    private void gitStatus(Path root, String gitBin, Map<String, Object> map) {
        map.put("supported", true);
        map.put("mode", launchMode(System.getProperty("java.class.path", "")));
        if (gitBin == null) {
            map.put("needsToolchain", true);
            map.put("updateAvailable", false);
            return;
        }
        try {
            String current = out(root, gitBin, "rev-parse", "HEAD");
            String[] up = upstream(root, gitBin);
            String latest = parseLsRemote(out(root, gitBin, "ls-remote", up[0], up[1]));
            if (latest.isBlank()) {
                throw new IOException("ветка не найдена");
            }
            map.put("currentSha", current);
            map.put("latestSha", latest);
            int behind = 0;
            List<String> subjects = List.of();
            if (!latest.equals(current)) {
                exec(root, List.of(gitBin, "fetch", "--quiet", up[0], up[1]));
                behind = Integer.parseInt(out(root, gitBin, "rev-list", "--count", "HEAD..FETCH_HEAD"));
                subjects = subjects(root, gitBin);
            }
            map.put("behindBy", behind);
            map.put("subjects", subjects);
            map.put("updateAvailable", behind > 0);
        } catch (Exception e) {
            map.put("supported", false);
            map.put("reason", e.getMessage());
        }
    }

    static List<String> buildCommand(Path root) {
        Path mvn = root.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        return List.of(mvn.toString(), "-B", "-DskipTests", "package");
    }

    private void runGitUpdate(Path root, Consumer<String> log, String gitBin, String javaHome) throws Exception {
        if (!out(root, log, gitBin, "status", "--porcelain").isBlank()) {
            throw new HarmoniaSuiteBadRequestException("Обновление отменено: есть локальные изменения");
        }
        String current = out(root, log, gitBin, "rev-parse", "HEAD");
        String[] up = upstream(root, gitBin, log);
        String latest = parseLsRemote(out(root, log, gitBin, "ls-remote", up[0], up[1]));
        if (latest.equals(current)) {
            log.accept("Уже актуально");
            return;
        }
        log.accept("Тяну " + shortSha(latest));
        String phase = "git pull";
        List<String> phaseOutput = new ArrayList<>();
        try {
            List<String> pullCommand = List.of(gitBin, "pull", "--ff-only", up[0], up[1]);
            int code = run(root, pullCommand, line -> {
                phaseOutput.add(line);
                log.accept(line);
            });
            if (code != 0) {
                throw new IOException(formatFailure(pullCommand, code, phaseOutput));
            }
            log.accept("Сборка");
            phase = "сборка";
            phaseOutput.clear();
            List<String> buildCommand = buildCommand(root);
            code = run(root, buildCommand, line -> {
                phaseOutput.add(line);
                log.accept(line);
            }, Map.of("JAVA_HOME", javaHome));
            if (code != 0) {
                throw new IOException(formatFailure(buildCommand, code, phaseOutput));
            }
        } catch (Exception e) {
            String fullOutput = String.join("\n", phaseOutput);
            if (fullOutput.isBlank()) {
                logger.error("Обновление: фаза {} завершилась ошибкой", phase, e);
            } else {
                logger.error("Обновление: фаза {} завершилась ошибкой; полный вывод:\n{}", phase, fullOutput, e);
            }
            rollback(root, log, gitBin, current, e);
            throw e;
        }
        if (!"jar".equals(launchMode(System.getProperty("java.class.path", "")))) {
            log.accept("Готово. Перезапусти из IDEA (Stop+Run)");
            return;
        }
        Path relaunchScript = null;
        try {
            Path exe = exeInstallPath();
            if (exe != null && InstallLayout.appDir() != null) {
                relaunchScript = writeRelaunchVbs(ProcessHandle.current().pid(),
                        root.resolve("target").resolve(BUILT_JAR),
                        InstallLayout.appDir().resolve(BUILT_JAR), exe);
                log.accept("Перезапуск");
                wdetach(relaunchScript);
            } else {
                relaunchScript = writeRelaunch(relaunchCommand(root));
                log.accept("Перезапуск");
                detach(relaunchScript);
            }
        } catch (Exception e) {
            if (relaunchScript != null) {
                try {
                    Files.deleteIfExists(relaunchScript);
                } catch (Exception cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                    logger.warn("Не удалось удалить скрипт перезапуска: {}",
                            scrub(cleanupFailure.getMessage() == null
                                    ? cleanupFailure.toString() : cleanupFailure.getMessage()));
                }
            }
            String message = scrub(e.getMessage() == null ? e.toString() : e.getMessage());
            log.accept("Перезапуск не удался: " + message);
            logger.error("Перезапуск обновления не удался", e);
            rollback(root, log, gitBin, current, e);
            throw e;
        }
        Runtime.getRuntime().halt(0);
    }

    private void rollback(Path root, Consumer<String> log, String gitBin, String current, Exception original) {
        List<String> command = List.of(gitBin, "reset", "--hard", current);
        try {
            log.accept("Откат на " + shortSha(current));
            List<String> output = new ArrayList<>();
            int code = run(root, command, line -> {
                output.add(line);
                log.accept(line);
            });
            if (code != 0) {
                throw new IOException(formatFailure(command, code, output));
            }
        } catch (Exception resetFailure) {
            String message = scrub(resetFailure.getMessage() == null
                    ? resetFailure.toString() : resetFailure.getMessage());
            log.accept("Откат не удался: " + message);
            logger.error("Откат обновления не удался", resetFailure);
            original.addSuppressed(resetFailure);
        }
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

    static Path writeRelaunchVbs(long pid, Path built, Path appJar, Path exe) throws IOException {
        String log = Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("harmonia-relaunch.log").toString().replace("\"", "");
        String nl = "\r\n";
        String body = "On Error Resume Next" + nl
                + "Set fso = CreateObject(\"Scripting.FileSystemObject\")" + nl
                + "Set lg = fso.OpenTextFile(\"" + log + "\", 8, True)" + nl
                + "lg.WriteLine Now & \" wait " + pid + "\"" + nl
                + "Set wmi = GetObject(\"winmgmts:\\\\.\\root\\cimv2\")" + nl
                + "For i = 1 To 120" + nl
                + "  If wmi.ExecQuery(\"SELECT ProcessId FROM Win32_Process WHERE ProcessId="
                + pid + "\").Count = 0 Then Exit For" + nl
                + "  WScript.Sleep 1000" + nl
                + "Next" + nl
                + "lg.WriteLine Now & \" copy\"" + nl
                + "fso.CopyFile \"" + built.toString().replace("\"", "") + "\", \""
                + appJar.toString().replace("\"", "") + "\", True" + nl
                + "For i = 1 To 10" + nl
                + "  If Err.Number = 0 Then Exit For" + nl
                + "  Err.Clear" + nl
                + "  WScript.Sleep 1000" + nl
                + "  fso.CopyFile \"" + built.toString().replace("\"", "") + "\", \""
                + appJar.toString().replace("\"", "") + "\", True" + nl
                + "Next" + nl
                + "If Err.Number = 0 Then" + nl
                + "  lg.WriteLine Now & \" start\"" + nl
                + "  Set sh = CreateObject(\"WScript.Shell\")" + nl
                + "  sh.Environment(\"PROCESS\")(\"HARMONIA_NO_BROWSER\") = \"1\"" + nl
                + "  sh.Run \"\"\""
                + exe.toString().replace("\"", "") + "\"\"\", 1, False" + nl
                + "Else" + nl
                + "  lg.WriteLine Now & \" copy failed\"" + nl
                + "End If" + nl
                + "lg.Close" + nl
                + "fso.DeleteFile WScript.ScriptFullName" + nl;
        Path script = Files.createTempFile("harmonia-update-", ".vbs");
        // wscript без BOM читает .vbs как ANSI и портит не-ASCII пути
        Files.write(script, ((char) 0xFEFF + body).getBytes(StandardCharsets.UTF_16LE));
        return script;
    }

    private static void wdetach(Path script) throws IOException {
        new ProcessBuilder("wscript", "//Nologo", "//B", script.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .start();
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

    private static String probeGit() {
        Path installed = installToolchainGit();
        if (installed != null) {
            return installed.toString();
        }
        Path cached = toolchainDir().resolve("git").resolve("cmd").resolve(gitExe());
        if (Files.isRegularFile(cached)) {
            return cached.toString();
        }
        try {
            out(Paths.get(System.getProperty("user.dir")), "git", "--version");
            return "git";
        } catch (Exception e) {
            return null;
        }
    }

    private static Path installToolchainGit() {
        Path base = installToolchain();
        if (base == null) {
            return null;
        }
        Path exe = base.resolve("git").resolve("cmd").resolve(gitExe());
        return Files.isRegularFile(exe) ? exe : null;
    }

    private static Path installToolchain() {
        if ("dev".equals(launchMode(System.getProperty("java.class.path", "")))) {
            return null;
        }
        Path app = InstallLayout.appDir();
        if (app == null) {
            return null;
        }
        Path toolchain = app.resolve("toolchain");
        return Files.isDirectory(toolchain) ? toolchain : null;
    }

    private static String ensureGit(Consumer<String> log, ObjectMapper objectMapper) throws IOException {
        String gitBin = probeGit();
        if (gitBin != null) {
            return gitBin;
        }
        Path root = repoRootFs();
        if (root == null) {
            throw new IOException("нет исходников");
        }
        if (!isWindows()) {
            throw new IOException("установи git");
        }
        log.accept("Качаю MinGit");
        Path dir = toolchainDir().resolve("git");
        Path zip = Files.createTempFile("harmonia-git-", ".zip");
        try {
            Map<String, Object> json = getJson(objectMapper, MINGIT_RELEASES_URL);
            String url = pickMinGitUrl(assets(json));
            if (url == null) {
                throw new IOException("MinGit не найден");
            }
            downloadFile(url, zip, log);
            unzip(zip, dir, log);
        } finally {
            Files.deleteIfExists(zip);
        }
        Path exe = dir.resolve("cmd").resolve(gitExe());
        if (!Files.isRegularFile(exe)) {
            throw new IOException("MinGit битый");
        }
        return exe.toString();
    }

    private static String ensureJava(Consumer<String> log) throws IOException {
        Path base = installToolchain();
        if (base != null) {
            Path found = findJavacHome(base.resolve("jdk"));
            if (found != null) {
                log.accept("Toolchain: JDK из установки");
                return found.toString();
            }
        }
        String home = systemJavaHome();
        if (home != null) {
            log.accept("Toolchain: системный JDK");
            return home;
        }
        Path bundled = findJavacHome(toolchainDir().resolve("jdk"));
        if (bundled != null) {
            log.accept("Toolchain: bundled JDK");
            return bundled.toString();
        }
        if (!isWindows()) {
            throw new IOException("установи JDK 21");
        }
        log.accept("Качаю Temurin 21");
        Path dir = toolchainDir().resolve("jdk");
        Path zip = Files.createTempFile("harmonia-jdk-", ".zip");
        try {
            downloadFile(TEMURIN_URL, zip, log);
            unzip(zip, dir, log);
        } finally {
            Files.deleteIfExists(zip);
        }
        Path found = findJavacHome(dir);
        if (found == null) {
            throw new IOException("JDK битый");
        }
        return found.toString();
    }

    private static String systemJavaHome() {
        String home = System.getenv("JAVA_HOME");
        if (javacMajor(home) >= 21) {
            return home;
        }
        try {
            String where = out(Paths.get(System.getProperty("user.dir")),
                    isWindows() ? "where" : "which", "java");
            String first = where.lines().findFirst().orElse("");
            if (!first.isBlank()) {
                Path candidate = Paths.get(first.strip()).getParent();
                if (candidate != null && candidate.getFileName().toString().equalsIgnoreCase("bin")) {
                    candidate = candidate.getParent();
                }
                if (candidate != null && javacMajor(candidate.toString()) >= 21) {
                    return candidate.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int javacMajor(String home) {
        if (home == null || home.isBlank()) {
            return -1;
        }
        try {
            Path javac = Paths.get(home).resolve("bin").resolve(isWindows() ? "javac.exe" : "javac");
            if (!Files.isRegularFile(javac)) {
                return -1;
            }
            String version = out(Paths.get(System.getProperty("user.dir")), javac.toString(), "-version");
            return parseJavaMajor(version);
        } catch (Exception e) {
            return -1;
        }
    }

    private static Path findJavacHome(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            String exe = isWindows() ? "javac.exe" : "javac";
            return walk.filter(p -> p.getFileName().toString().equals(exe))
                    .map(p -> p.getParent().getParent())
                    .filter(home -> home != null && javacMajor(home.toString()) >= 21)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String gitExe() {
        return isWindows() ? "git.exe" : "git";
    }

    private static Path toolchainDir() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank()
                ? Paths.get(local)
                : Paths.get(System.getProperty("user.home"), ".cache");
        return base.resolve("HarmoniaSuite").resolve("toolchain");
    }

    private static void unzip(Path zip, Path dest, Consumer<String> log) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) {
                    throw new IOException("zip-slip");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (var in = zipFile.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        log.accept("Распаковано в " + dest);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> assets(Map<String, Object> json) {
        List<Map<String, String>> assets = new ArrayList<>();
        Object rawAssets = json.get("assets");
        if (rawAssets instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> asset) {
                    Object name = asset.get("name");
                    Object url = asset.get("browser_download_url");
                    if (name != null && url != null) {
                        assets.add(Map.of("name", name.toString(), "browser_download_url", url.toString()));
                    }
                }
            }
        }
        return assets;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getJson(ObjectMapper objectMapper, String url) throws IOException {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "harmonia-suite")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException(httpFailure("HTTP", response.statusCode(), response.body()));
            }
            return objectMapper.readValue(response.body(), Map.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }
    }

    private static void downloadFile(String url, Path target, Consumer<String> log) throws IOException {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "harmonia-suite")
                    .timeout(Duration.ofSeconds(600))
                    .GET()
                    .build();
            HttpResponse<Path> response =
                    http.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new IOException(httpFailure("скачивание: HTTP", response.statusCode(), bodyExcerpt(target)));
            }
            log.accept("Скачано " + Files.size(target) / 1024 / 1024 + " МБ");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }
    }

    private static String httpFailure(String prefix, int status, String body) {
        String excerpt = bodyExcerpt(body);
        return prefix + " " + status + (excerpt.isBlank() ? "" : ": " + excerpt);
    }

    private static String bodyExcerpt(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder value = new StringBuilder(HTTP_BODY_CHARS + 1);
            char[] buffer = new char[Math.min(1024, HTTP_BODY_CHARS + 1)];
            while (value.length() <= HTTP_BODY_CHARS) {
                int count = reader.read(buffer, 0, Math.min(buffer.length, HTTP_BODY_CHARS + 1 - value.length()));
                if (count < 0) {
                    break;
                }
                value.append(buffer, 0, count);
            }
            return bodyExcerpt(value.toString());
        } catch (Exception e) {
            return "";
        }
    }

    private static String bodyExcerpt(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String value = scrub(body.strip());
        return value.length() > HTTP_BODY_CHARS ? value.substring(0, HTTP_BODY_CHARS) + "…" : value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Path repoRoot(String gitBin) {
        for (Path base : candidateRoots()) {
            try {
                String top = out(base, gitBin, "rev-parse", "--show-toplevel");
                Path path = Paths.get(top);
                if (Files.isDirectory(path) && isOurRepo(path)) {
                    return path;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Path repoRootFs() {
        for (Path base : candidateRoots()) {
            Path dir = base.toAbsolutePath().normalize();
            for (int i = 0; i < 8 && dir != null; i++) {
                if (isOurRepo(dir)) {
                    return dir;
                }
                dir = dir.getParent();
            }
        }
        return null;
    }

    private static List<Path> candidateRoots() {
        List<Path> bases = new ArrayList<>();
        Path app = InstallLayout.appDir();
        if (app != null) {
            bases.add(app.resolve("src"));
            bases.add(app);
        }
        bases.add(Paths.get(System.getProperty("user.dir")));
        return bases;
    }

    private static boolean isOurRepo(Path top) {
        try {
            Path pom = top.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                return false;
            }
            return Files.readString(pom, StandardCharsets.UTF_8)
                    .contains("<artifactId>harmonia-suite</artifactId>");
        } catch (Exception e) {
            return false;
        }
    }

    private static String[] upstream(Path root, String gitBin) {
        return upstream(root, gitBin, line -> {
        });
    }

    private static String[] upstream(Path root, String gitBin, Consumer<String> log) {
        try {
            String ref = out(root, log, gitBin, "rev-parse", "--abbrev-ref", "@{upstream}");
            int slash = ref.indexOf('/');
            if (slash > 0) {
                return new String[]{ref.substring(0, slash), ref.substring(slash + 1)};
            }
        } catch (Exception e) {
            logger.warn("Не удалось определить upstream, использую {}/{}: {}",
                    REMOTE, BRANCH, scrub(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return new String[]{REMOTE, BRANCH};
    }

    private static List<String> subjects(Path root, String gitBin) throws IOException {
        List<String> lines = new ArrayList<>();
        List<String> command = List.of(gitBin, "log", "--format=%s", "-n", String.valueOf(MAX_SUBJECTS),
                "HEAD..FETCH_HEAD");
        int code = run(root, command, lines::add);
        if (code != 0) {
            throw new IOException(formatFailure(command, code, lines));
        }
        return lines.stream().filter(l -> !l.isBlank()).toList();
    }

    private static String out(Path dir, String... cmd) throws IOException {
        return out(dir, line -> {
        }, cmd);
    }

    private static String out(Path dir, Consumer<String> log, String... cmd) throws IOException {
        List<String> lines = new ArrayList<>();
        int code = run(dir, List.of(cmd), line -> {
            lines.add(line);
            log.accept(line);
        }, Map.of());
        if (code != 0) {
            throw new IOException(formatFailure(List.of(cmd), code, lines));
        }
        return String.join("\n", lines).strip();
    }

    private static void exec(Path dir, List<String> cmd) throws IOException {
        exec(dir, cmd, line -> {
        });
    }

    private static void exec(Path dir, List<String> cmd, Consumer<String> log) throws IOException {
        List<String> lines = new ArrayList<>();
        int code = run(dir, cmd, line -> {
            lines.add(line);
            log.accept(line);
        }, Map.of());
        if (code != 0) {
            throw new IOException(formatFailure(cmd, code, lines));
        }
    }

    private static int run(Path dir, List<String> cmd, Consumer<String> log) throws IOException {
        return run(dir, cmd, log, Map.of());
    }

    private static int run(Path dir, List<String> cmd, Consumer<String> log, Map<String, String> env)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(env);
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
        } catch (IOException ignored) {
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().forEach(line -> log.accept(scrub(line)));
        }
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("прервано");
        }
    }

    private static Path writeRelaunch(List<String> command) throws IOException {
        long pid = ProcessHandle.current().pid();
        boolean windows = isWindows();
        Path script = Files.createTempFile("harmonia-update-", windows ? ".cmd" : ".sh");
        StringBuilder body = new StringBuilder();
        if (windows) {
            body.append("@echo off\n");
            body.append(":wait\n");
            body.append("tasklist /FI \"PID eq ").append(pid).append("\" 2>nul | find \"")
                    .append(pid).append("\" >nul\n");
            body.append("if not errorlevel 1 (timeout /t 1 /nobreak >nul & goto wait)\n");
            body.append("cd /d \"").append(System.getProperty("user.dir")).append("\"\n");
            body.append("start \"\"");
            for (String arg : command) {
                body.append(" \"").append(arg.replace("\"", "")).append("\"");
            }
            body.append("\ndel \"%~f0\"\n");
        } else {
            body.append("#!/bin/sh\n");
            body.append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 1; done\n");
            body.append("cd \"").append(System.getProperty("user.dir")).append("\"\n");
            body.append("rm -- \"$0\"\n");
            body.append("exec");
            for (String arg : command) {
                body.append(" \"").append(arg.replace("\"", "")).append("\"");
            }
            body.append("\n");
            try {
                Files.setPosixFilePermissions(script,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
            }
        }
        Files.writeString(script, body.toString(), StandardCharsets.UTF_8);
        return script;
    }

    private static void detach(Path script) throws IOException {
        if (isWindows()) {
            new ProcessBuilder("cmd", "/c", "start", "", script.toString()).start();
        } else {
            new ProcessBuilder("sh", "-c", "nohup \"" + script + "\" >/dev/null 2>&1 &").start();
        }
    }
}
