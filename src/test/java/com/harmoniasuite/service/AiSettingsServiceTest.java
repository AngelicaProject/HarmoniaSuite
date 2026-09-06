package com.harmoniasuite.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.dto.UpdateAiSettingsRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.repository.SettingsRepository;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSettingsServiceTest {

    private record Fixture(AiSettingsService ai, HarmoniaProperties properties) {
    }

    private Fixture seed(Path dbDir) {
        HarmoniaProperties properties = new HarmoniaProperties();
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        OpenRouterApiClient openRouter =
                new OpenRouterApiClient(new ObjectMapper(), RestClient.builder());
        return new Fixture(new AiSettingsService(new SettingsRepository(jdbc), properties, openRouter, null),
                properties);
    }

    @Test
    @DisplayName("по умолчанию провайдер gemini, ключ не задан")
    void defaultsAreEmpty(@TempDir Path dbDir) {
        Fixture fixture = seed(dbDir);
        assertEquals("gemini", fixture.ai().provider());
        assertEquals("", fixture.ai().effectiveGeminiKey());
        assertEquals(false, fixture.ai().status().get("geminiConfigured"));
    }

    @Test
    @DisplayName("ключ из окружения подхватывается")
    void envKeyIsEffective(@TempDir Path dbDir) {
        Fixture fixture = seed(dbDir);
        fixture.properties().getGemini().setApiKey("env-one");
        assertEquals("env-one", fixture.ai().effectiveGeminiKey());
        Map<String, Object> status = fixture.ai().status();
        assertEquals(true, status.get("geminiConfigured"));
        assertEquals("env", status.get("geminiKeySource"));
    }

    @Test
    @DisplayName("сохранённый ключ важнее окружения и маскируется")
    void storedKeyWinsAndMasks(@TempDir Path dbDir) {
        Fixture fixture = seed(dbDir);
        fixture.properties().getGemini().setApiKey("env-one");
        Map<String, Object> status = fixture.ai()
                .update(new UpdateAiSettingsRequest(null, "stored-secret-one", null, null, null));
        assertEquals("stored-secret-one", fixture.ai().effectiveGeminiKey());
        assertEquals(true, status.get("geminiKeySet"));
        assertEquals("settings", status.get("geminiKeySource"));
        assertTrue(((String) status.get("geminiKeyHint")).endsWith("one"));
    }

    @Test
    @DisplayName("пустая строка стирает сохранённый ключ")
    void blankClearsStoredKey(@TempDir Path dbDir) {
        Fixture fixture = seed(dbDir);
        fixture.properties().getGemini().setApiKey("env-one");
        fixture.ai().update(new UpdateAiSettingsRequest(null, "stored-secret-one", null, null, null));
        fixture.ai().update(new UpdateAiSettingsRequest(null, "  ", null, null, null));
        assertEquals("env-one", fixture.ai().effectiveGeminiKey());
        assertEquals("env", fixture.ai().status().get("geminiKeySource"));
    }

    @Test
    @DisplayName("неизвестный провайдер отклоняется")
    void unknownProviderIsRejected(@TempDir Path dbDir) {
        Fixture fixture = seed(dbDir);
        assertThrows(HarmoniaSuiteBadRequestException.class, () -> fixture.ai()
                .update(new UpdateAiSettingsRequest("pack-one", null, null, null, null)));
    }

    @Test
    @DisplayName("openrouter принимается и хранит свой ключ")
    void openrouterProviderStoresKey(@TempDir Path dbDir) {
        Fixture fixture = seed(dbDir);
        Map<String, Object> status = fixture.ai()
                .update(new UpdateAiSettingsRequest("openrouter", null, "or-one", null, null));
        assertEquals("openrouter", fixture.ai().provider());
        assertEquals("or-one", fixture.ai().effectiveOpenRouterKey());
        assertEquals(true, status.get("openrouterConfigured"));
        assertEquals("settings", status.get("openrouterKeySource"));
    }

    @Test
    @DisplayName("reasoning сохраняется и отклоняет мусор")
    void reasoningStoredAndValidated(@TempDir Path dbDir) {
        Fixture fixture = seed(dbDir);
        assertEquals("", fixture.ai().openRouterReasoning());
        Map<String, Object> status = fixture.ai()
                .update(new UpdateAiSettingsRequest(null, null, null, null, "Medium"));
        assertEquals("medium", fixture.ai().openRouterReasoning());
        assertEquals("medium", status.get("openrouterReasoning"));
        assertThrows(HarmoniaSuiteBadRequestException.class, () -> fixture.ai()
                .update(new UpdateAiSettingsRequest(null, null, null, null, "ultra")));
    }

    @Test
    @DisplayName("проверка без ключа не ходит в сеть")
    void checkWithoutKeyStaysOffline(@TempDir Path dbDir) {
        Fixture fixture = seed(dbDir);
        Map<String, Object> res = fixture.ai().checkAccess("gemini", null);
        assertEquals(false, res.get("ok"));
        assertThrows(HarmoniaSuiteBadRequestException.class, () ->
                fixture.ai().checkAccess("pack-one", "x"));
    }
}
