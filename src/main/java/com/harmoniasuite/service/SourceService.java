package com.harmoniasuite.service;

import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.dto.UpdateSourceSettingsRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.repository.SettingsRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class SourceService {

    public static final String MODE_GAME = "game";
    public static final String MODE_CSVDIR = "csvdir";

    private static final String KEY_MODE = "source.mode";
    private static final String KEY_GAME_PATH = "source.game-path";
    private static final String KEY_UNPACKER_EXE = "source.unpacker-exe";
    private static final String KEY_CSV_DIR = "source.csv-dir";

    private static final String LEGACY_ROOT = "rawexd/en";

    private static final List<String> GAME_CANDIDATES = List.of(
            "C:/Program Files (x86)/SquareEnix/FINAL FANTASY XIV - A Realm Reborn",
            "C:/Program Files (x86)/Steam/steamapps/common/FINAL FANTASY XIV Online");

    private static final String UNPACKER_HOME = Paths.get(System.getProperty("user.home"),
            "source", "repos", "XivExdUnpacker").toString();

    private static final List<String> UNPACKER_CANDIDATES = List.of(
            UNPACKER_HOME + "/bin/Release/net10.0/XivExdUnpacker.exe",
            UNPACKER_HOME + "/bin/Debug/net10.0/XivExdUnpacker.exe");

    private final WorkspacePaths workspace;
    private final SettingsRepository settings;

    public SourceService(WorkspacePaths workspace, SettingsRepository settings) {
        this.workspace = workspace;
        this.settings = settings;
    }

    public String mode() {
        String mode = settings.get(KEY_MODE);
        return MODE_CSVDIR.equals(mode) ? MODE_CSVDIR : MODE_GAME;
    }

    public String gamePath() {
        String value = settings.get(KEY_GAME_PATH);
        return value == null ? "" : value;
    }

    public String unpackerExe() {
        String value = settings.get(KEY_UNPACKER_EXE);
        return value == null ? "" : value;
    }

    public String csvDir() {
        String value = settings.get(KEY_CSV_DIR);
        return value == null ? "" : value;
    }

    public Map<String, Object> status() {
        String mode = mode();
        String gamePath = gamePath();
        boolean gameValid = isValidGamePath(gamePath);
        String exe = unpackerExe();
        boolean exeOk = isRegularFile(exe);
        Path root = activeRootIfReady();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", mode);
        map.put("csvDir", csvDir());
        map.put("gamePath", gamePath);
        map.put("gameValid", gameValid);
        map.put("gameVersion", gameVersion() == null ? "" : gameVersion());
        map.put("unpackerExe", exe);
        map.put("unpackerReady", exeOk);
        map.put("activeRoot", root == null ? "" : root.toString());
        map.put("ready", root != null && Files.isDirectory(root));
        map.put("configured", MODE_CSVDIR.equals(mode)
                ? Files.isDirectory(workspace.resolve(csvDir()))
                : gameValid && exeOk);
        return map;
    }

    public Map<String, Object> update(UpdateSourceSettingsRequest patch) {
        if (patch.mode() != null) {
            if (!MODE_GAME.equals(patch.mode()) && !MODE_CSVDIR.equals(patch.mode())) {
                throw new HarmoniaSuiteBadRequestException("Некорректный режим источника: " + patch.mode());
            }
            settings.set(KEY_MODE, patch.mode());
        }
        if (patch.gamePath() != null) {
            String value = patch.gamePath().strip();
            if (!value.isEmpty() && !isValidGamePath(value)) {
                throw new HarmoniaSuiteBadRequestException("Каталог игры не распознан: " + value);
            }
            settings.set(KEY_GAME_PATH, value);
        }
        if (patch.unpackerExe() != null) {
            String value = patch.unpackerExe().strip();
            if (!value.isEmpty() && !isRegularFile(value)) {
                throw new HarmoniaSuiteBadRequestException("Файл не найден: " + value);
            }
            settings.set(KEY_UNPACKER_EXE, value);
        }
        if (patch.csvDir() != null) {
            settings.set(KEY_CSV_DIR, patch.csvDir().strip());
        }
        return status();
    }

    public Map<String, String> detect() {
        Map<String, String> map = new LinkedHashMap<>();
        String game = detectGamePath();
        if (game != null) {
            map.put("gamePath", game);
        }
        String exe = detectUnpackerExe();
        if (exe != null) {
            map.put("unpackerExe", exe);
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
        if (MODE_CSVDIR.equals(mode())) {
            String dir = csvDir();
            if (dir.isBlank()) {
                return null;
            }
            Path csv = workspace.resolve(dir);
            return Files.isDirectory(csv) ? csv : null;
        }
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
        if (!MODE_GAME.equals(mode())) {
            throw new HarmoniaSuiteBadRequestException("Синхронизация доступна в режиме game");
        }
        String gamePath = gamePath();
        if (!isValidGamePath(gamePath)) {
            throw new HarmoniaSuiteBadRequestException("Некорректный путь к игре: " + gamePath);
        }
        String exe = unpackerExe();
        if (!isRegularFile(exe)) {
            throw new HarmoniaSuiteBadRequestException("Не задан XivExdUnpacker: " + exe);
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

    private String detectUnpackerExe() {
        String configured = unpackerExe();
        if (isRegularFile(configured)) {
            return configured;
        }
        for (String candidate : UNPACKER_CANDIDATES) {
            if (isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
