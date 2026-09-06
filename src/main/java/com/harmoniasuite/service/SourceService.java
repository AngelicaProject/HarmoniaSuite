package com.harmoniasuite.service;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.dto.UpdateSourceSettingsRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.repository.SettingsRepository;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class SourceService {

    private static final String KEY_GAME_PATH = "source.game-path";

    private static final String LEGACY_ROOT = "rawexd/en";

    private static final List<String> GAME_CANDIDATES = List.of(
            "C:/Program Files (x86)/SquareEnix/FINAL FANTASY XIV - A Realm Reborn",
            "C:/Program Files (x86)/Steam/steamapps/common/FINAL FANTASY XIV Online");

    private final WorkspacePaths workspace;
    private final SettingsRepository settings;

    public SourceService(WorkspacePaths workspace, SettingsRepository settings) {
        this.workspace = workspace;
        this.settings = settings;
    }

    public String gamePath() {
        String value = settings.get(KEY_GAME_PATH);
        return value == null ? "" : value;
    }

    public Map<String, Object> status() {
        String gamePath = gamePath();
        boolean gameValid = isValidGamePath(gamePath);
        boolean exeOk = isRegularFile(bundledExe());
        Path root = activeRootIfReady();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("gamePath", gamePath);
        map.put("gameValid", gameValid);
        map.put("gameVersion", gameVersion() == null ? "" : gameVersion());
        map.put("activeRoot", root == null ? "" : root.toString());
        map.put("ready", root != null && Files.isDirectory(root));
        map.put("configured", gameValid && exeOk);
        return map;
    }

    public Map<String, Object> update(UpdateSourceSettingsRequest patch) {
        if (patch.gamePath() != null) {
            String value = patch.gamePath().strip();
            if (!value.isEmpty() && !isValidGamePath(value)) {
                throw new HarmoniaSuiteBadRequestException("Каталог игры не распознан: " + value);
            }
            settings.set(KEY_GAME_PATH, value);
        }
        return status();
    }

    public Map<String, String> detect() {
        Map<String, String> map = new LinkedHashMap<>();
        String game = detectGamePath();
        if (game != null) {
            map.put("gamePath", game);
        }
        return map;
    }

    public String gameVersion() {
        return gameVersionOf(gamePath());
    }

    public String gameVersionOf(String gamePath) {
        if (gamePath == null || gamePath.isBlank()) {
            return null;
        }
        try {
            Path ver = Paths.get(gamePath).resolve("game").resolve("ffxivgame.ver");
            if (!Files.isRegularFile(ver)) {
                return null;
            }
            String version = Files.readString(ver, StandardCharsets.UTF_8).strip();
            return version.isEmpty() ? null : version;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidGamePath(String gamePath) {
        if (gamePath == null || gamePath.isBlank()) {
            return false;
        }
        try {
            Path game = Paths.get(gamePath);
            return Files.isDirectory(game)
                    && Files.isRegularFile(game.resolve("game").resolve("ffxivgame.ver"))
                    && Files.isDirectory(game.resolve("game").resolve("sqpack"));
        } catch (Exception e) {
            return false;
        }
    }

    public Path activeRoot() {
        Path ready = activeRootIfReady();
        if (ready != null) {
            return ready;
        }
        throw new HarmoniaSuiteBadRequestException(
                "Источники не готовы: задайте путь к игре и запустите синхронизацию");
    }

    public Path activeRootIfReady() {
        String version = gameVersion();
        if (version != null) {
            Path cached = cacheDir(version).resolve("en");
            if (Files.isDirectory(cached)) {
                return cached;
            }
        }
        Path legacy = workspace.resolve(LEGACY_ROOT);
        return Files.isDirectory(legacy) ? legacy : null;
    }

    public Path cacheDir(String version) {
        if (version == null || !version.matches("[0-9A-Za-z._-]+")) {
            throw new HarmoniaSuiteBadRequestException("Некорректная версия игры: " + version);
        }
        return workspace.resolve("data").resolve("sources").resolve(version);
    }

    public void sync(Consumer<String> log) throws IOException {
        String gamePath = gamePath();
        if (!isValidGamePath(gamePath)) {
            throw new HarmoniaSuiteBadRequestException("Некорректный путь к игре: " + gamePath);
        }
        String exe = bundledExe();
        if (!isRegularFile(exe)) {
            throw new HarmoniaSuiteBadRequestException(
                    "Нет XivExdUnpacker (bundled нет; для своей сборки — через env HARMONIA_UNPACKER_EXE)");
        }
        String version = gameVersion();
        if (version == null) {
            throw new HarmoniaSuiteBadRequestException("Не читается ffxivgame.ver");
        }
        Path dir = cacheDir(version);
        Files.createDirectories(dir);
        writeUnpackerConfig(dir, gamePath);
        log.accept("Версия игры: " + version);
        log.accept("Экспорт в " + dir.resolve("en"));
        Process process = new ProcessBuilder(exe, "--language", "en", "--clear")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        Thread pump = new Thread(() -> {
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                out.lines().forEach(log);
            } catch (Exception ignored) {
            }
        });
        pump.setDaemon(true);
        pump.start();
        try {
            int code = process.waitFor();
            pump.join();
            if (code != 0) {
                throw new IOException("XivExdUnpacker завершился с кодом " + code);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Синхронизация прервана");
        }
        long csv = countCsv(dir.resolve("en"));
        if (csv == 0) {
            throw new IOException("Экспорт пуст: CSV не созданы, проверьте лог выше");
        }
        log.accept("Готово: " + csv + " CSV, версия " + version);
    }

    static void writeUnpackerConfig(Path dir, String gamePath) throws IOException {
        String safe = gamePath.replace('\\', '/').replace("\"", "");
        String content = "globalGamePath: \"" + safe + "\"\n\nen:\n  outputDir: \"./en\"\n";
        Files.writeString(dir.resolve("config.yml"), content, StandardCharsets.UTF_8);
    }

    private static String bundledExe() {
        String env = System.getenv("HARMONIA_UNPACKER_EXE");
        if (isRegularFile(env)) {
            return env.strip();
        }
        for (Path base : bundledBases()) {
            Path exe = base.resolve("unpacker").resolve("XivExdUnpacker.exe");
            if (Files.isRegularFile(exe)) {
                return exe.toString();
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            for (String candidate : homeCandidates(home)) {
                if (isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return "";
    }

    static List<String> homeCandidates(String home) {
        List<String> out = new ArrayList<>();
        for (String config : List.of("Release", "Debug")) {
            out.add(Paths.get(home, "source", "repos", "XivExdUnpacker",
                    "bin", config, "net10.0", "XivExdUnpacker.exe").toString());
        }
        return out;
    }

    static List<Path> bundledBases() {
        List<Path> bases = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Paths.get(entry.strip());
                if (!path.isAbsolute()) {
                    path = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
                }
                Path fileName = path.getFileName();
                Path parent = path.getParent();
                bases.add(fileName != null && fileName.toString().toLowerCase().endsWith(".jar") && parent != null
                        ? parent : path);
            } catch (Exception ignored) {
            }
        }
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            try {
                Path exe = Paths.get(appPath);
                Path dir = Files.isDirectory(exe) ? exe : exe.getParent();
                if (dir != null) {
                    bases.add(dir.resolve("app"));
                    bases.add(dir);
                }
            } catch (Exception ignored) {
            }
        }
        try {
            URI location = SourceService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            if ("file".equalsIgnoreCase(location.getScheme())) {
                Path parent = Paths.get(location).getParent();
                if (parent != null) {
                    bases.add(parent);
                }
            }
        } catch (Exception ignored) {
        }
        return bases;
    }

    private static long countCsv(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .count();
        }
    }

    private static boolean isRegularFile(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(Paths.get(value));
        } catch (Exception e) {
            return false;
        }
    }

    private String detectGamePath() {
        String configured = gamePath();
        if (isValidGamePath(configured)) {
            return configured;
        }
        for (String candidate : GAME_CANDIDATES) {
            if (isValidGamePath(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
