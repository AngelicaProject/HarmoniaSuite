package com.harmoniasuite.service.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.config.InstallLayout;
import com.harmoniasuite.exception.UpdateErrorCode;
import com.harmoniasuite.exception.UpdateException;
import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Supplies the Git and JDK builds the self-update runs with: either the bundled, system or cached
 * ones, or a freshly downloaded copy in the toolchain directory.
 */
final class ToolchainProvider {

    private static final String MINGIT_RELEASE_API_URL =
            "https://api.github.com/repos/git-for-windows/git/releases/latest";
    private static final String TEMURIN_JDK_URL =
            "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse";
    private static final String NODE_DOWNLOAD_URL =
            "https://nodejs.org/dist/v%s/node-v%s-win-x64.zip";
    private static final Pattern JAVAC_VERSION = Pattern.compile("javac (\\d+)");
    private static final Pattern NODE_VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");

    private ToolchainProvider() {
    }

    /** Path to a usable Git, or {@code null} when none is present yet. */
    static String gitBinary() {
        Path bundled = bundledGit();
        if (bundled != null) {
            return bundled.toString();
        }
        Path cached = cacheToolchainDir().resolve("git").resolve("cmd").resolve(gitExecutableName());
        if (Files.isRegularFile(cached)) {
            return cached.toString();
        }
        try {
            UpdateProcess.captureOutput(Paths.get(System.getProperty("user.dir")), "git", "--version");
            return "git";
        } catch (Exception e) {
            return null;
        }
    }

    /** Path to a usable Git, downloading MinGit first when the machine has none. */
    static String requireGit(Consumer<String> log, ObjectMapper objectMapper) {
        String existing = gitBinary();
        if (existing != null) {
            return existing;
        }
        if (!isWindows()) {
            throw new UpdateException(UpdateErrorCode.GIT_NOT_FOUND);
        }
        log.accept("Загрузка Git");
        Path directory = cacheToolchainDir().resolve("git");
        Map<String, Object> release;
        try {
            release = Downloader.fetchJson(objectMapper, MINGIT_RELEASE_API_URL);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.GIT_DOWNLOAD_FAILED,
                    UpdateProcess.diagnosticDetail(e), e);
        }
        String url = minGitDownloadUrl(releaseAssets(release));
        if (url == null) {
            throw new UpdateException(UpdateErrorCode.GIT_RELEASE_NOT_FOUND);
        }
        Downloader.downloadAndExtract(url, directory, "harmonia-git-", log,
                UpdateErrorCode.GIT_DOWNLOAD_FAILED);
        Path exe = directory.resolve("cmd").resolve(gitExecutableName());
        if (!Files.isRegularFile(exe)) {
            throw new UpdateException(UpdateErrorCode.TOOLCHAIN_CORRUPT);
        }
        return exe.toString();
    }

    /** Java home of a usable JDK, or {@code null} when none is present yet. */
    static String javaHome() {
        Path installed = installedToolchainDir();
        if (installed != null) {
            Path bundled = findJavaHome(installed.resolve("jdk"));
            if (bundled != null) {
                return bundled.toString();
            }
        }
        String system = systemJavaHome();
        if (system != null) {
            return system;
        }
        Path cached = findJavaHome(cacheToolchainDir().resolve("jdk"));
        return cached == null ? null : cached.toString();
    }

    /** Java home of a usable JDK, downloading Temurin 21 first when the machine has none. */
    static String requireJavaHome(Consumer<String> log) {
        Path installed = installedToolchainDir();
        if (installed != null) {
            Path bundled = findJavaHome(installed.resolve("jdk"));
            if (bundled != null) {
                log.accept("Используется JDK из установки");
                return bundled.toString();
            }
        }
        String system = systemJavaHome();
        if (system != null) {
            log.accept("Используется системный JDK");
            return system;
        }
        Path cached = findJavaHome(cacheToolchainDir().resolve("jdk"));
        if (cached != null) {
            log.accept("Используется JDK из кэша");
            return cached.toString();
        }
        if (!isWindows()) {
            throw new UpdateException(UpdateErrorCode.JDK_NOT_FOUND);
        }
        log.accept("Загрузка JDK 21");
        Path directory = cacheToolchainDir().resolve("jdk");
        Downloader.downloadAndExtract(TEMURIN_JDK_URL, directory, "harmonia-jdk-", log,
                UpdateErrorCode.JDK_DOWNLOAD_FAILED);
        Path found = findJavaHome(directory);
        if (found == null) {
            throw new UpdateException(UpdateErrorCode.TOOLCHAIN_CORRUPT);
        }
        return found.toString();
    }

    /** Reads the exact Node.js version required by the checkout being built. */
    static String requiredNodeVersion(Path checkout) {
        Path versionFile = checkout.resolve("frontend").resolve(".node-version");
        final String raw;
        try {
            raw = Files.readString(versionFile, java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.NODE_VERSION_NOT_FOUND,
                    UpdateProcess.diagnosticDetail(e), e);
        }
        Matcher matcher = NODE_VERSION.matcher(raw);
        if (!matcher.matches()) {
            throw new UpdateException(UpdateErrorCode.NODE_VERSION_INVALID);
        }
        return matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
    }

    /** Existing compatible Node.js home, without downloading anything. */
    static String nodeHome(Path checkout) {
        String required = requiredNodeVersion(checkout);
        Path installed = installedToolchainDir();
        if (installed != null) {
            Path bundled = findNodeHome(installed.resolve("node"), required);
            if (bundled != null) {
                return bundled.toString();
            }
        }
        Path cached = findNodeHome(cacheToolchainDir().resolve("node").resolve(required), required);
        if (cached != null) {
            return cached.toString();
        }
        Path system = systemNodeHome(required);
        return system == null ? null : system.toString();
    }

    /** Existing compatible Node.js home or a cached portable copy downloaded on Windows. */
    static String requireNodeHome(Path checkout, Consumer<String> log) {
        String required = requiredNodeVersion(checkout);
        String existing = nodeHome(checkout);
        if (existing != null) {
            log.accept("Используется Node.js " + required);
            return existing;
        }
        if (!isWindows()) {
            throw new UpdateException(UpdateErrorCode.NODE_NOT_FOUND);
        }
        log.accept("Загрузка Node.js " + required);
        Path directory = cacheToolchainDir().resolve("node").resolve(required);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UpdateException(UpdateErrorCode.NODE_DOWNLOAD_FAILED,
                    UpdateProcess.diagnosticDetail(e), e);
        }
        String url = NODE_DOWNLOAD_URL.formatted(required, required);
        Downloader.downloadAndExtract(url, directory, "harmonia-node-", log,
                UpdateErrorCode.NODE_DOWNLOAD_FAILED);
        Path found = findNodeHome(directory, required);
        if (found == null) {
            throw new UpdateException(UpdateErrorCode.TOOLCHAIN_CORRUPT);
        }
        npmBinary(found.toString());
        return found.toString();
    }

    /** npm executable belonging to a resolved Node.js home. */
    static String npmBinary(String nodeHome) {
        if (nodeHome == null || nodeHome.isBlank()) {
            throw new UpdateException(UpdateErrorCode.NODE_NOT_FOUND);
        }
        Path npm = Paths.get(nodeHome).resolve(npmExecutableName());
        if (!Files.isRegularFile(npm)) {
            throw new UpdateException(UpdateErrorCode.TOOLCHAIN_CORRUPT);
        }
        return npm.toString();
    }

    static boolean isCompatibleNodeVersion(String actualOutput, String requiredVersion) {
        String actual = normalizeNodeVersion(actualOutput);
        String required = normalizeNodeVersion(requiredVersion);
        return actual != null && actual.equals(required);
    }

    static String normalizeNodeVersion(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = NODE_VERSION.matcher(value.strip());
        return matcher.matches()
                ? matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3)
                : null;
    }

    static String prependToPath(String home, String path) {
        if (home == null || home.isBlank()) {
            return path;
        }
        if (path == null || path.isBlank()) {
            return home;
        }
        return home + File.pathSeparator + path;
    }

    static String minGitDownloadUrl(List<Map<String, String>> assets) {
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

    static int parseJavacMajorVersion(String javacVersionOutput) {
        if (javacVersionOutput == null) {
            return -1;
        }
        Matcher matcher = JAVAC_VERSION.matcher(javacVersionOutput);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Git shipped inside the installation, or {@code null} when the app is not installed. */
    private static Path bundledGit() {
        Path installed = installedToolchainDir();
        if (installed == null) {
            return null;
        }
        Path exe = installed.resolve("git").resolve("cmd").resolve(gitExecutableName());
        return Files.isRegularFile(exe) ? exe : null;
    }

    private static Path systemNodeHome(String required) {
        String explicit = System.getenv("NODE_HOME");
        Path fromEnvironment = null;
        if (explicit != null && !explicit.isBlank()) {
            try {
                fromEnvironment = nodeHomeFromExecutable(
                        Paths.get(explicit).resolve(nodeExecutableName()).toString(), required);
            } catch (RuntimeException ignored) {
                // Ignore an invalid optional environment override and continue with PATH.
            }
        }
        if (fromEnvironment != null) {
            return fromEnvironment;
        }
        try {
            String where = UpdateProcess.captureOutput(Paths.get(System.getProperty("user.dir")),
                    isWindows() ? "where" : "which", "node");
            for (String line : where.lines().toList()) {
                Path found = nodeHomeFromExecutable(line.strip(), required);
                if (found != null) {
                    return found;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path nodeHomeFromExecutable(String executable, String required) {
        if (executable == null || executable.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(executable).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !path.getFileName().toString().equalsIgnoreCase(nodeExecutableName())) {
                return null;
            }
            String version = UpdateProcess.captureOutput(Paths.get(System.getProperty("user.dir")),
                    path.toString(), "--version");
            if (!isCompatibleNodeVersion(version, required) || path.getParent() == null) {
                return null;
            }
            Path npm = path.getParent().resolve(npmExecutableName());
            return Files.isRegularFile(npm) ? path.getParent() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path findNodeHome(Path directory, String required) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(directory, 5)) {
            String exe = nodeExecutableName();
            return walk.filter(p -> p.getFileName().toString().equalsIgnoreCase(exe))
                    .map(p -> nodeHomeFromExecutable(p.toString(), required))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Toolchain directory of the installation, or {@code null} when the app is not installed. */
    private static Path installedToolchainDir() {
        if ("dev".equals(UpdateRelaunch.detectLaunchMode(System.getProperty("java.class.path", "")))) {
            return null;
        }
        Path app = InstallLayout.appDir();
        if (app == null) {
            return null;
        }
        Path toolchain = app.resolve("toolchain");
        return Files.isDirectory(toolchain) ? toolchain : null;
    }

    /** Toolchain directory downloads are cached in. */
    private static Path cacheToolchainDir() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank()
                ? Paths.get(local)
                : Paths.get(System.getProperty("user.home"), ".cache");
        return base.resolve("HarmoniaSuite").resolve("toolchain");
    }

    private static String systemJavaHome() {
        String home = System.getenv("JAVA_HOME");
        if (javacMajorVersion(home) >= 21) {
            return home;
        }
        try {
            String where = UpdateProcess.captureOutput(Paths.get(System.getProperty("user.dir")),
                    isWindows() ? "where" : "which", "java");
            String first = where.lines().findFirst().orElse("");
            if (!first.isBlank()) {
                Path candidate = Paths.get(first.strip()).getParent();
                if (candidate != null && candidate.getFileName().toString().equalsIgnoreCase("bin")) {
                    candidate = candidate.getParent();
                }
                if (candidate != null && javacMajorVersion(candidate.toString()) >= 21) {
                    return candidate.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int javacMajorVersion(String javaHome) {
        if (javaHome == null || javaHome.isBlank()) {
            return -1;
        }
        try {
            Path javac = Paths.get(javaHome).resolve("bin").resolve(javacExecutableName());
            if (!Files.isRegularFile(javac)) {
                return -1;
            }
            String version = UpdateProcess.captureOutput(Paths.get(System.getProperty("user.dir")),
                    javac.toString(), "-version");
            return parseJavacMajorVersion(version);
        } catch (Exception e) {
            return -1;
        }
    }

    /** First Java home at or below {@code directory} whose javac is 21 or newer. */
    private static Path findJavaHome(Path directory) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(directory, 4)) {
            String exe = javacExecutableName();
            return walk.filter(p -> p.getFileName().toString().equals(exe))
                    .map(p -> p.getParent().getParent())
                    .filter(home -> home != null && javacMajorVersion(home.toString()) >= 21)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> releaseAssets(Map<String, Object> release) {
        List<Map<String, String>> assets = new ArrayList<>();
        if (release == null) {
            return assets;
        }
        Object rawAssets = release.get("assets");
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

    private static String gitExecutableName() {
        return isWindows() ? "git.exe" : "git";
    }

    private static String javacExecutableName() {
        return isWindows() ? "javac.exe" : "javac";
    }

    private static String nodeExecutableName() {
        return isWindows() ? "node.exe" : "node";
    }

    private static String npmExecutableName() {
        return isWindows() ? "npm.cmd" : "npm";
    }

    private static boolean isWindows() {
        return UpdateRelaunch.isWindows();
    }
}
