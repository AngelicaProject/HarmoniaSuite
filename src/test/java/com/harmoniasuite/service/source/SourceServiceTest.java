package com.harmoniasuite.service.source;

import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.config.WorkspacePaths;
import com.harmoniasuite.dto.UpdateSourceSettingsRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.repository.SettingsRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceServiceTest {

    private record Fixture(SourceService sources) {
    }

    private Fixture seed(Path workspace, Path dbDir) {
        HarmoniaProperties properties = new HarmoniaProperties();
        properties.setWorkspace(workspace.toString());
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        return new Fixture(new SourceService(new WorkspacePaths(properties),
                new SettingsRepository(jdbc)));
    }

    private Path fakeGame(Path dir) throws Exception {
        Path game = dir.resolve("game-install");
        Files.createDirectories(game.resolve("game").resolve("sqpack"));
        Files.writeString(game.resolve("game").resolve("ffxivgame.ver"),
                "2099.01.01.0000.0000\n", StandardCharsets.UTF_8);
        return game;
    }

    @Test
    @DisplayName("game version reads from ffxivgame.ver")
    void gameVersionReadsVerFile(@TempDir Path workspace, @TempDir Path dbDir) throws Exception {
        Fixture fixture = seed(workspace, dbDir);
        Path game = fakeGame(workspace);
        fixture.sources().update(new UpdateSourceSettingsRequest(game.toString()));
        assertEquals("2099.01.01.0000.0000", fixture.sources().gameVersion());
        assertTrue(fixture.sources().isValidGamePath(game.toString()));
    }

    @Test
    @DisplayName("broken game path is invalid")
    void brokenGamePathIsInvalid(@TempDir Path workspace, @TempDir Path dbDir) {
        Fixture fixture = seed(workspace, dbDir);
        assertFalse(fixture.sources().isValidGamePath(""));
        assertFalse(fixture.sources().isValidGamePath(workspace.resolve("missing").toString()));
    }

    @Test
    @DisplayName("update rejects a missing game path")
    void updateRejectsMissingGamePath(@TempDir Path workspace, @TempDir Path dbDir) {
        Fixture fixture = seed(workspace, dbDir);
        assertThrows(HarmoniaSuiteBadRequestException.class, () -> fixture.sources()
                .update(new UpdateSourceSettingsRequest(workspace.resolve("missing").toString())));
    }

    @Test
    @DisplayName("active root without sources throws 400")
    void activeRootWithoutSourcesThrows(@TempDir Path workspace, @TempDir Path dbDir) {
        Fixture fixture = seed(workspace, dbDir);
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> fixture.sources().activeRoot());
    }

    @Test
    @DisplayName("legacy rawexd covers the transition until first sync")
    void legacyRootCoversTransition(@TempDir Path workspace, @TempDir Path dbDir) throws Exception {
        Fixture fixture = seed(workspace, dbDir);
        Path legacy = workspace.resolve("rawexd").resolve("en");
        Files.createDirectories(legacy);
        assertEquals(legacy.toAbsolutePath().normalize(), fixture.sources().activeRoot());
    }

    @Test
    @DisplayName("version cache beats legacy")
    void versionCacheBeatsLegacy(@TempDir Path workspace, @TempDir Path dbDir) throws Exception {
        Fixture fixture = seed(workspace, dbDir);
        Files.createDirectories(workspace.resolve("rawexd").resolve("en"));
        Path game = fakeGame(workspace);
        fixture.sources().update(new UpdateSourceSettingsRequest(game.toString()));
        Path cached = workspace.resolve("data").resolve("sources")
                .resolve("2099.01.01.0000.0000").resolve("en");
        Files.createDirectories(cached);
        assertEquals(cached.toAbsolutePath().normalize(), fixture.sources().activeRoot());
    }

    @Test
    @DisplayName("unpacker config contains the game path and ./en")
    void unpackerConfigContent(@TempDir Path dir) throws Exception {
        SourceService.writeUnpackerConfig(dir, "C:\\game\\FFXIV");
        String content = Files.readString(dir.resolve("config.yml"), StandardCharsets.UTF_8);
        assertTrue(content.contains("globalGamePath: \"C:/game/FFXIV\""));
        assertTrue(content.contains("outputDir: \"./en\""));
    }

    @Test
    @DisplayName("cacheDir rejects versions with separators")
    void cacheDirRejectsSeparators(@TempDir Path workspace, @TempDir Path dbDir) {
        Fixture fixture = seed(workspace, dbDir);
        assertThrows(HarmoniaSuiteBadRequestException.class,
                () -> fixture.sources().cacheDir("../../pack-one"));
    }

    @Test
    @DisplayName("home fallback prefers Release over Debug")
    void homeFallbackOrder(@TempDir Path workspace, @TempDir Path dbDir) {
        seed(workspace, dbDir);
        List<String> candidates = SourceService.homeCandidates("C:/Users/pack-one");
        assertEquals(2, candidates.size());
        assertTrue(candidates.get(0).contains("Release"));
        assertTrue(candidates.get(1).contains("Debug"));
    }

    @Test
    @DisplayName("status reflects readiness")
    void statusReflectsReadiness(@TempDir Path workspace, @TempDir Path dbDir) {
        Fixture fixture = seed(workspace, dbDir);
        Map<String, Object> before = fixture.sources().status();
        assertEquals(false, before.get("configured"));
        assertEquals(false, before.get("ready"));
    }
}
