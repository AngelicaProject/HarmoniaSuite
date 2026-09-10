package com.harmoniasuite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.config.AppVersion;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.InstallLayout;
import com.harmoniasuite.dto.UpdateState;
import com.harmoniasuite.dto.UpdateStatusDto;
import com.harmoniasuite.exception.UpdateErrorCode;
import com.harmoniasuite.exception.UpdateException;
import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UpdateService {

    private static final String UPDATE_REMOTE = "origin";
    private static final String UPDATE_BRANCH = "main";
    private static final int MAX_SUBJECTS = 10;
    private static final String MINGIT_RELEASES_URL =
            "https://api.github.com/repos/git-for-windows/git/releases/latest";
    private static final String TEMURIN_URL =
            "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse";
    private static final Set<String> DOWNLOAD_HOSTS = Set.of(
            "api.github.com", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com", "api.adoptium.net");
    private static final Pattern JAVAC_VERSION = Pattern.compile("javac (\\d+)");
    private static final int HTTP_BODY_CHARS = 4096;
    private static final Logger logger = LoggerFactory.getLogger(UpdateService.class);

    private enum UpdatePhase {
        PULL("получение исходников", UpdateErrorCode.PULL_FAILED),
        BUILD("сборка", UpdateErrorCode.BUILD_FAILED);

        private final String label;
        private final UpdateErrorCode errorCode;

        UpdatePhase(String label, UpdateErrorCode errorCode) {
            this.label = label;
            this.errorCode = errorCode;
        }
    }

    private record UpdatePlan(Path root, String gitBin, String javaHome,
            String current, String latest, int behind, int ahead) {
    }

    private final UpdateCoordinator coordinator = new UpdateCoordinator();
    private final HarmoniaProperties properties;
    private final ObjectMapper objectMapper;

    public UpdateService(HarmoniaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public UpdateStatusDto status() {
        return coordinator.read(this::statusLocked);
    }

    private UpdateStatusDto statusLocked() {
        String version = AppVersion.resolve(properties.getApp().getVersion(), "dev");
        String gitBin = probeGit();
        Path root = resolveRoot(gitBin);
        if (root == null) {
            return unavailableStatus(version, null, UpdateState.UNAVAILABLE,
                    new UpdateException(UpdateErrorCode.SOURCES_NOT_FOUND));
        }
        if (gitBin == null || probeJavaHome() == null) {
            String mode = launchMode(System.getProperty("java.class.path", ""));
            return new UpdateStatusDto(version, true, mode, true,
                    null, null, null, null, false, UpdateState.TOOLCHAIN_REQUIRED,
                    UpdateState.TOOLCHAIN_REQUIRED.message());
        }
        return gitStatus(version, root, gitBin);
    }

    public void runUpdate(Consumer<String> log) {
        coordinator.run(() -> runUpdateLocked(log));
    }

    private void runUpdateLocked(Consumer<String> log) {
        String gitBin = ensureGit(log, objectMapper);
        Path root = resolveRoot(gitBin);
        if (root == null) {
            throw new UpdateException(UpdateErrorCode.SOURCES_NOT_FOUND);
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

    static UpdateState historyState(int behind, int ahead) {
        if (behind > 0 && ahead == 0) {
            return UpdateState.UPDATE_AVAILABLE;
        }
        if (behind == 0 && ahead > 0) {
            return UpdateState.LOCAL_AHEAD;
        }
        if (behind > 0) {
            return UpdateState.DIVERGED;
        }
        return UpdateState.UP_TO_DATE;
    }

    static String shortSha(String sha) {
        if (sha == null) {
            return "";
        }
        return sha.length() >= 7 ? sha.substring(0, 7) : sha;
    }

    static String launchMode(String classPath) {
        return UpdateRelaunch.launchMode(classPath);
    }

    public static String resolveCommit(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("@")) {
            return "";
        }
        return raw.strip();
    }

    static String scrub(String value) {
        return UpdateProcess.scrub(value);
    }

    @SafeVarargs
    static <T> T firstAvailable(Supplier<T>... candidates) {
        for (Supplier<T> candidate : candidates) {
            T value = candidate.get();
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String formatFailure(List<String> command, int code, List<String> lines) {
        return UpdateProcess.formatFailure(command, code, lines);
    }

    static String formatFailure(String command, int code, List<String> lines) {
        return UpdateProcess.formatFailure(command, code, lines);
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
        return UpdateRelaunch.relaunchCommand(root);
    }

    static List<String> relaunchCommand(Path root, String javaBin, List<String> args, Path runtimeJava) {
        return UpdateRelaunch.relaunchCommand(root, javaBin, args, runtimeJava);
    }

    private UpdateStatusDto gitStatus(String version, Path root, String gitBin) {
        String mode = launchMode(System.getProperty("java.class.path", ""));
        if (gitBin == null) {
            return new UpdateStatusDto(version, true, mode, true,
                    null, null, null, null, false, UpdateState.TOOLCHAIN_REQUIRED,
                    UpdateState.TOOLCHAIN_REQUIRED.message());
        }
        try {
            String current = captureOutput(root, gitBin, "rev-parse", "HEAD");
            runCommand(root, List.of(gitBin, "fetch", "--quiet", UPDATE_REMOTE, UPDATE_BRANCH));
            String latest = captureOutput(root, gitBin, "rev-parse", "FETCH_HEAD");
            if (latest.isBlank()) {
                throw new UpdateException(UpdateErrorCode.TARGET_NOT_FOUND);
            }
            int behind = latest.equals(current)
                    ? 0 : gitDistance(root, gitBin, "HEAD..FETCH_HEAD");
            int ahead = latest.equals(current)
                    ? 0 : gitDistance(root, gitBin, "FETCH_HEAD..HEAD");
            UpdateState state = historyState(behind, ahead);
            List<String> subjects = behind > 0 ? subjects(root, gitBin) : List.of();
            return new UpdateStatusDto(version, true, mode, null,
                    current, latest, behind, subjects,
                    state == UpdateState.UPDATE_AVAILABLE, state, state.message());
        } catch (UpdateException | IOException | NumberFormatException e) {
            UpdateException failure = e instanceof UpdateException updateException
                    ? updateException
                    : new UpdateException(UpdateErrorCode.STATUS_CHECK_FAILED, diagnosticDetail(e), e);
            return unavailableStatus(version, mode, UpdateState.CHECK_FAILED, failure);
        }
    }

    private static int gitDistance(Path root, String gitBin, String range) throws IOException {
        return Integer.parseInt(captureOutput(root, gitBin, "rev-list", "--count", range));
    }

    private static UpdateStatusDto unavailableStatus(String version, String mode,
            UpdateState state, UpdateException failure) {
        if (state == UpdateState.CHECK_FAILED) {
            logger.warn("Обновления недоступны [{}]: {}", failure.code(),
                    scrub(failure.diagnosticMessage()));
        } else {
            logger.debug("Обновления недоступны [{}]: {}", failure.code(),
                    scrub(failure.diagnosticMessage()));
        }
        return new UpdateStatusDto(version, false, mode, null,
                null, null, null, null, null, state, failure.getMessage());
    }

    private static String diagnosticDetail(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.toString() : scrub(message);
    }

    static List<String> buildCommand(Path root) {
        Path mvn = root.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        return List.of(mvn.toString(), "-B", "-DskipTests", "clean", "package");
    }

    private void runGitUpdate(Path root, Consumer<String> log, String gitBin, String javaHome) {
        try {
            if (!captureOutput(root, log, gitBin, "status", "--porcelain").isBlank()) {
                throw new UpdateException(UpdateErrorCode.WORKTREE_DIRTY);
            }
            UpdatePlan plan = prepareUpdate(root, log, gitBin, javaHome);
            if (plan.current().isBlank()) {
                throw new UpdateException(UpdateErrorCode.GIT_COMMAND_FAILED);
            }
            if (plan.latest().isBlank()) {
                throw new UpdateException(UpdateErrorCode.TARGET_NOT_FOUND);
            }
            if (plan.ahead() > 0) {
                throw new UpdateException(plan.behind() > 0
                        ? UpdateErrorCode.HISTORY_DIVERGED : UpdateErrorCode.LOCAL_VERSION_AHEAD);
            }
            if (plan.latest().equals(plan.current())) {
                log.accept("Уже актуально");
                return;
            }
            log.accept("Получение версии " + shortSha(plan.latest()));
            updateSourceAndBuild(plan, log);
            relaunch(plan, log);
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.GIT_COMMAND_FAILED, diagnosticDetail(e), e);
        }
    }

    private UpdatePlan prepareUpdate(Path root, Consumer<String> log, String gitBin, String javaHome) {
        try {
            String current = captureOutput(root, log, gitBin, "rev-parse", "HEAD");
            runCommand(root, List.of(gitBin, "fetch", "--quiet", UPDATE_REMOTE, UPDATE_BRANCH), log);
            String latest = captureOutput(root, log, gitBin, "rev-parse", "FETCH_HEAD");
            if (latest.isBlank()) {
                return new UpdatePlan(root, gitBin, javaHome, current, latest, 0, 0);
            }
            int behind = latest.equals(current) ? 0 : gitDistance(root, gitBin, "HEAD..FETCH_HEAD");
            int ahead = latest.equals(current) ? 0 : gitDistance(root, gitBin, "FETCH_HEAD..HEAD");
            return new UpdatePlan(root, gitBin, javaHome, current, latest, behind, ahead);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.GIT_COMMAND_FAILED, diagnosticDetail(e), e);
        }
    }

    private void updateSourceAndBuild(UpdatePlan plan, Consumer<String> log) {
        UpdatePhase phase = UpdatePhase.PULL;
        List<String> phaseOutput = new ArrayList<>();
        try {
            List<String> pullCommand = List.of(plan.gitBin(), "pull", "--ff-only",
                    UPDATE_REMOTE, UPDATE_BRANCH);
            int code = runProcess(plan.root(), pullCommand, line -> {
                phaseOutput.add(line);
                log.accept(line);
            });
            if (code != 0) {
                throw new UpdateException(phase.errorCode, formatFailure(pullCommand, code, phaseOutput));
            }
            log.accept("Сборка");
            phase = UpdatePhase.BUILD;
            phaseOutput.clear();
            List<String> buildCommand = buildCommand(plan.root());
            code = runProcess(plan.root(), buildCommand, line -> {
                phaseOutput.add(line);
                log.accept(line);
            }, Map.of("JAVA_HOME", plan.javaHome()));
            if (code != 0) {
                throw new UpdateException(phase.errorCode, formatFailure(buildCommand, code, phaseOutput));
            }
        } catch (UpdateException e) {
            reportPhaseFailure(phase, phaseOutput, e);
            rollback(plan.root(), log, plan.gitBin(), plan.current(), e);
            throw e;
        } catch (IOException e) {
            UpdateException failure = new UpdateException(phase.errorCode, diagnosticDetail(e), e);
            reportPhaseFailure(phase, phaseOutput, failure);
            rollback(plan.root(), log, plan.gitBin(), plan.current(), failure);
            throw failure;
        }
    }

    private static void reportPhaseFailure(UpdatePhase phase, List<String> output,
            UpdateException failure) {
        String fullOutput = String.join("\n", output);
        if (fullOutput.isBlank()) {
            logger.error("Обновление: фаза {} завершилась ошибкой [{}]: {}",
                    phase.label, failure.code(), failure.diagnosticMessage());
        } else {
            logger.error("Обновление: фаза {} завершилась ошибкой [{}]; полный вывод:\n{}",
                    phase.label, failure.code(), fullOutput);
        }
    }

    private void relaunch(UpdatePlan plan, Consumer<String> log) {
        if (!"jar".equals(launchMode(System.getProperty("java.class.path", "")))) {
            log.accept("Готово. Перезапусти приложение из среды разработки");
            return;
        }
        Path relaunchScript = null;
        try {
            Path exe = exeInstallPath();
            if (exe != null && InstallLayout.appDir() != null) {
                relaunchScript = writeRelaunchVbs(ProcessHandle.current().pid(),
                        UpdateRelaunch.builtJar(plan.root()),
                        InstallLayout.appDir().resolve(UpdateRelaunch.BUILT_JAR), exe);
                log.accept("Перезапуск");
                wdetach(relaunchScript);
            } else {
                relaunchScript = writeRelaunch(relaunchCommand(plan.root()));
                log.accept("Перезапуск");
                detach(relaunchScript);
            }
        } catch (Exception e) {
            UpdateException failure = new UpdateException(UpdateErrorCode.RELAUNCH_FAILED,
                    diagnosticDetail(e), e);
            if (relaunchScript != null) {
                try {
                    Files.deleteIfExists(relaunchScript);
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    logger.warn("Не удалось удалить скрипт перезапуска: {}",
                            scrub(cleanupFailure.getMessage() == null
                                    ? cleanupFailure.toString() : cleanupFailure.getMessage()));
                }
            }
            log.accept(failure.getMessage());
            logger.error("Перезапуск обновления не удался [{}]: {}",
                    failure.code(), failure.diagnosticMessage());
            rollback(plan.root(), log, plan.gitBin(), plan.current(), failure);
            throw failure;
        }
        Runtime.getRuntime().halt(0);
    }

    private void rollback(Path root, Consumer<String> log, String gitBin, String current,
            UpdateException original) {
        List<String> command = List.of(gitBin, "reset", "--hard", current);
        try {
            log.accept("Откат на " + shortSha(current));
            List<String> output = new ArrayList<>();
            int code = runProcess(root, command, line -> {
                output.add(line);
                log.accept(line);
            });
            if (code != 0) {
                throw new IOException(formatFailure(command, code, output));
            }
        } catch (Exception e) {
            UpdateException failure = new UpdateException(UpdateErrorCode.ROLLBACK_FAILED,
                    diagnosticDetail(e), e);
            log.accept(failure.getMessage());
            logger.error("Откат обновления не удался [{}]: {}",
                    failure.code(), failure.diagnosticMessage());
            original.addSuppressed(failure);
        }
    }

    static Path exeInstallPath() {
        return UpdateRelaunch.exeInstallPath();
    }

    static Path writeRelaunchVbs(long pid, Path built, Path appJar, Path exe) throws IOException {
        return UpdateRelaunch.writeRelaunchVbs(pid, built, appJar, exe);
    }

    private static void wdetach(Path script) throws IOException {
        UpdateRelaunch.wdetach(script);
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
            captureOutput(Paths.get(System.getProperty("user.dir")), "git", "--version");
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

    private static String ensureGit(Consumer<String> log, ObjectMapper objectMapper) {
        String gitBin = probeGit();
        if (gitBin != null) {
            return gitBin;
        }
        if (repoRootFs() == null) {
            throw new UpdateException(UpdateErrorCode.SOURCES_NOT_FOUND);
        }
        if (!isWindows()) {
            throw new UpdateException(UpdateErrorCode.GIT_NOT_FOUND);
        }
        log.accept("Загрузка Git");
        Path dir = toolchainDir().resolve("git");
        Map<String, Object> json;
        try {
            json = getJson(objectMapper, MINGIT_RELEASES_URL);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.GIT_DOWNLOAD_FAILED, diagnosticDetail(e), e);
        }
        String url = pickMinGitUrl(assets(json));
        if (url == null) {
            throw new UpdateException(UpdateErrorCode.GIT_RELEASE_NOT_FOUND);
        }
        downloadAndExtract(url, dir, "harmonia-git-", log, UpdateErrorCode.GIT_DOWNLOAD_FAILED);
        Path exe = dir.resolve("cmd").resolve(gitExe());
        if (!Files.isRegularFile(exe)) {
            throw new UpdateException(UpdateErrorCode.TOOLCHAIN_CORRUPT);
        }
        return exe.toString();
    }

    private static String probeJavaHome() {
        Path base = installToolchain();
        if (base != null) {
            Path installed = findJavacHome(base.resolve("jdk"));
            if (installed != null) {
                return installed.toString();
            }
        }
        String system = systemJavaHome();
        if (system != null) {
            return system;
        }
        Path cached = findJavacHome(toolchainDir().resolve("jdk"));
        return cached == null ? null : cached.toString();
    }

    private static String ensureJava(Consumer<String> log) {
        Path base = installToolchain();
        if (base != null) {
            Path found = findJavacHome(base.resolve("jdk"));
            if (found != null) {
                log.accept("Используется JDK из установки");
                return found.toString();
            }
        }
        String home = systemJavaHome();
        if (home != null) {
            log.accept("Используется системный JDK");
            return home;
        }
        Path bundled = findJavacHome(toolchainDir().resolve("jdk"));
        if (bundled != null) {
            log.accept("Используется JDK из кэша");
            return bundled.toString();
        }
        if (!isWindows()) {
            throw new UpdateException(UpdateErrorCode.JDK_NOT_FOUND);
        }
        log.accept("Загрузка JDK 21");
        Path dir = toolchainDir().resolve("jdk");
        downloadAndExtract(TEMURIN_URL, dir, "harmonia-jdk-", log, UpdateErrorCode.JDK_DOWNLOAD_FAILED);
        Path found = findJavacHome(dir);
        if (found == null) {
            throw new UpdateException(UpdateErrorCode.TOOLCHAIN_CORRUPT);
        }
        return found.toString();
    }

    private static void downloadAndExtract(String url, Path directory, String tempPrefix,
            Consumer<String> log, UpdateErrorCode downloadError) {
        Path archive;
        try {
            archive = Files.createTempFile(tempPrefix, ".zip");
        } catch (IOException e) {
            throw new UpdateException(downloadError, diagnosticDetail(e), e);
        }
        try {
            downloadFile(url, archive, log);
            try {
                unzip(archive, directory, log);
            } catch (IOException e) {
                throw new UpdateException(UpdateErrorCode.TOOLCHAIN_CORRUPT,
                        diagnosticDetail(e), e);
            }
        } catch (UpdateException e) {
            throw e;
        } catch (IOException e) {
            throw new UpdateException(downloadError, diagnosticDetail(e), e);
        } finally {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException e) {
                logger.debug("Не удалось удалить временный архив обновления", e);
            }
        }
    }

    private static String systemJavaHome() {
        String home = System.getenv("JAVA_HOME");
        if (javacMajor(home) >= 21) {
            return home;
        }
        try {
            String where = captureOutput(Paths.get(System.getProperty("user.dir")),
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
            String version = captureOutput(Paths.get(System.getProperty("user.dir")), javac.toString(), "-version");
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
        if (json == null) {
            return assets;
        }
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
            URI source = trustedDownloadUri(url);
            HttpRequest request = HttpRequest.newBuilder(source)
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
            throw new UpdateException(UpdateErrorCode.INTERRUPTED, e);
        }
    }

    private static void downloadFile(String url, Path target, Consumer<String> log) throws IOException {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            URI source = trustedDownloadUri(url);
            HttpRequest request = HttpRequest.newBuilder(source)
                    .header("User-Agent", "harmonia-suite")
                    .timeout(Duration.ofSeconds(600))
                    .GET()
                    .build();
            HttpResponse<Path> response =
                    http.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new IOException(httpFailure("скачивание: HTTP", response.statusCode(), bodyExcerpt(target)));
            }
            if (!isAllowedDownloadUri(response.uri())) {
                throw new IOException("download redirect host is not allowed");
            }
            log.accept("Скачано " + Files.size(target) / 1024 / 1024 + " МБ");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException(UpdateErrorCode.INTERRUPTED, e);
        }
    }

    private static URI trustedDownloadUri(String raw) throws IOException {
        try {
            URI uri = URI.create(raw);
            if (!isAllowedDownloadUri(uri)) {
                throw new IOException("download host is not allowed");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("download URL is invalid", e);
        }
    }

    private static boolean isAllowedDownloadUri(URI uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && DOWNLOAD_HOSTS.contains(uri.getHost());
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
        return UpdateRelaunch.isWindows();
    }

    private static Path resolveRoot(String gitBin) {
        if (gitBin != null) {
            return firstAvailable(() -> repoRoot(gitBin), UpdateService::repoRootFs);
        }
        return firstAvailable(UpdateService::repoRootFs);
    }

    private static Path repoRoot(String gitBin) {
        for (Path base : candidateRoots()) {
            try {
                String top = captureOutput(base, gitBin, "rev-parse", "--show-toplevel");
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

    private static List<String> subjects(Path root, String gitBin) throws IOException {
        List<String> lines = new ArrayList<>();
        List<String> command = List.of(gitBin, "log", "--format=%s", "-n", String.valueOf(MAX_SUBJECTS),
                "HEAD..FETCH_HEAD");
        int code = runProcess(root, command, lines::add);
        if (code != 0) {
            throw new IOException(formatFailure(command, code, lines));
        }
        return lines.stream().filter(l -> !l.isBlank()).toList();
    }

    private static String captureOutput(Path dir, String... cmd) throws IOException {
        return captureOutput(dir, line -> {
        }, cmd);
    }

    private static String captureOutput(Path dir, Consumer<String> log, String... cmd) throws IOException {
        return UpdateProcess.captureOutput(dir, log, cmd);
    }

    private static void runCommand(Path dir, List<String> cmd) throws IOException {
        runCommand(dir, cmd, line -> {
        });
    }

    private static void runCommand(Path dir, List<String> cmd, Consumer<String> log) throws IOException {
        UpdateProcess.requireSuccess(dir, cmd, log);
    }

    private static int runProcess(Path dir, List<String> cmd, Consumer<String> log) throws IOException {
        return runProcess(dir, cmd, log, Map.of());
    }

    private static int runProcess(Path dir, List<String> cmd, Consumer<String> log, Map<String, String> env)
            throws IOException {
        return UpdateProcess.runProcess(dir, cmd, log, env).code();
    }

    private static Path writeRelaunch(List<String> command) throws IOException {
        return UpdateRelaunch.writeRelaunch(command);
    }

    private static void detach(Path script) throws IOException {
        UpdateRelaunch.detach(script);
    }
}
