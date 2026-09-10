package com.harmoniasuite.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmoniasuite.TestDatabases;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.dto.UpdateAiSettingsRequest;
import com.harmoniasuite.repository.SettingsRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmProviderTest {

    @Test
    @DisplayName("gemini rejects a model outside the allowlist")
    void geminiRejectsUnknownModel() {
        GeminiProvider provider = new GeminiProvider(null, new HarmoniaProperties(), null);
        assertEquals("gemini-3.5-flash-lite", provider.resolveModel(null));
        assertEquals("gemini-3.1-flash-lite", provider.resolveModel("gemini-3.1-flash-lite"));
        assertThrows(IllegalArgumentException.class, () -> provider.resolveModel("pack-one"));
    }

    @Test
    @DisplayName("openrouter accepts any model, default from settings")
    void openrouterAcceptsAnyModel(@TempDir Path dbDir) {
        HarmoniaProperties properties = new HarmoniaProperties();
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        AiSettingsService ai = new AiSettingsService(new SettingsRepository(jdbc), properties, null, null);
        OpenRouterProvider provider = new OpenRouterProvider(null, properties, ai);
        assertEquals("google/gemini-flash-1.5", provider.resolveModel(null));
        assertEquals("pack-one/custom", provider.resolveModel("pack-one/custom"));
        ai.update(new UpdateAiSettingsRequest(null, null, null, "pack-one/stored", null));
        assertEquals("pack-one/stored", provider.resolveModel(null));
        assertEquals("pack-one/custom", provider.resolveModel("  pack-one/custom  "));
    }

    @Test
    @DisplayName("catalog reads names, reasoning and ordering")
    void parseModelsReadsCatalog() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree("{\"data\":["
                + "{\"id\":\"pack-one/b\",\"name\":\"Beta\",\"context_length\":32000,"
                + "\"supported_parameters\":[\"temperature\"]},"
                + "{\"id\":\"pack-one/a\",\"name\":\"Alpha\",\"context_length\":128000,"
                + "\"top_provider\":{\"max_completion_tokens\":8192},"
                + "\"supported_parameters\":[\"temperature\",\"reasoning\"]},"
                + "{\"name\":\"NoId\"}]}");
        var models = OpenRouterApiClient.parseModels(root);
        assertEquals(2, models.size());
        assertEquals("pack-one/a", models.get(0).id());
        assertEquals("Alpha", models.get(0).name());
        assertEquals(true, models.get(0).reasoning());
        assertEquals("pack-one/b", models.get(1).id());
        assertEquals(false, models.get(1).reasoning());
    }

    @Test
    @DisplayName("openrouter batch window grows from probe to ceiling")
    void openrouterWindowGrowsFromProbe(@TempDir Path dbDir) {
        HarmoniaProperties properties = new HarmoniaProperties();
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        AiSettingsService ai = new AiSettingsService(new SettingsRepository(jdbc), properties, null, null);
        OpenRouterProvider provider = new OpenRouterProvider(null, properties, ai);
        assertEquals(40, provider.maxBatchItems());
        provider.noteBatchOk();
        assertEquals(80, provider.maxBatchItems());
        provider.noteBatchOk();
        assertEquals(160, provider.maxBatchItems());
        for (int i = 0; i < 10; i++) {
            provider.noteBatchOk();
        }
        assertEquals(2000, provider.maxBatchItems());
    }

    @Test
    @DisplayName("openrouter request body uses chat completions format")
    void openrouterRequestBodyUsesContent() throws Exception {
        ObjectMapper om = new ObjectMapper();
        var body = OpenRouterApiClient.requestBody(om, "pack-one/a", "sys", "[{\"i\":0}]", 1024, "");
        assertEquals("pack-one/a", body.path("model").asText());
        assertEquals(0, body.path("temperature").asInt(-1));
        assertEquals(1024, body.path("max_tokens").asInt(0));
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals(false, body.path("reasoning").path("enabled").asBoolean(true));
        var messages = body.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.path(0).path("role").asText());
        assertEquals("sys", messages.path(0).path("content").asText());
        assertEquals(true, messages.path(0).path("text").isMissingNode());
        assertEquals("user", messages.path(1).path("role").asText());
        assertEquals("[{\"i\":0}]", messages.path(1).path("content").asText());
        assertEquals(true, messages.path(1).path("text").isMissingNode());
        var withReasoning = OpenRouterApiClient.requestBody(om, "pack-one/a", "sys", "[]", 8, "low");
        assertEquals("low", withReasoning.path("reasoning").path("effort").asText());
    }

    @Test
    @DisplayName("truncation measurement narrows output and resets on route change")
    void openrouterTruncationNarrowsOutput(@TempDir Path dbDir) {
        HarmoniaProperties properties = new HarmoniaProperties();
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        AiSettingsService ai = new AiSettingsService(new SettingsRepository(jdbc), properties, null, null);
        OpenRouterProvider provider = new OpenRouterProvider(null, properties, ai);
        assertEquals(Integer.MAX_VALUE, provider.maxOutputTokens());
        provider.noteTruncated(20000, 20000);
        assertEquals(Integer.MAX_VALUE, provider.maxOutputTokens());
        provider.noteTruncated(20000, 0);
        assertEquals(Integer.MAX_VALUE, provider.maxOutputTokens());
        provider.noteTruncated(20000, 4096);
        assertEquals(4096, provider.maxOutputTokens());
        provider.noteTruncated(4000, 3000);
        assertEquals(3000, provider.maxOutputTokens());
        provider.noteTruncated(2000, 3000);
        assertEquals(3000, provider.maxOutputTokens());
        provider.noteRouteChanged();
        assertEquals(Integer.MAX_VALUE, provider.maxOutputTokens());
    }

    @Test
    @DisplayName("session reasoning is validated and normalized")
    void openrouterReasoningOverride(@TempDir Path dbDir) {
        HarmoniaProperties properties = new HarmoniaProperties();
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        AiSettingsService ai = new AiSettingsService(new SettingsRepository(jdbc), properties, null, null);
        OpenRouterProvider provider = new OpenRouterProvider(null, properties, ai);
        assertNull(provider.resolveReasoning(null));
        assertNull(provider.resolveReasoning(""));
        assertNull(provider.resolveReasoning("   "));
        assertEquals("", provider.resolveReasoning("off"));
        assertEquals("", provider.resolveReasoning(" OFF "));
        assertEquals("low", provider.resolveReasoning(" Low "));
        assertEquals("medium", provider.resolveReasoning("medium"));
        assertEquals("high", provider.resolveReasoning("HIGH"));
        assertThrows(IllegalArgumentException.class, () -> provider.resolveReasoning("ultra"));
        assertEquals("", new GeminiProvider(null, new HarmoniaProperties(), null).resolveReasoning("low"));
    }

    @Test
    @DisplayName("openrouter prepare resets the window and sets no caps")
    void openrouterPrepareResetsWindowWithoutCaps(@TempDir Path dbDir) {
        HarmoniaProperties properties = new HarmoniaProperties();
        JdbcTemplate jdbc = TestDatabases.sqlite(dbDir);
        AiSettingsService ai = new AiSettingsService(new SettingsRepository(jdbc), properties, null, null);
        OpenRouterProvider provider = new OpenRouterProvider(null, properties, ai);
        provider.noteBatchOk();
        provider.noteBatchOk();
        assertEquals(160, provider.maxBatchItems());
        List<String> logged = new ArrayList<>();
        provider.prepareModel("pack-one/a", logged::add);
        assertEquals(40, provider.maxBatchItems());
        assertEquals(Integer.MAX_VALUE, provider.maxInputChars());
        assertEquals(Integer.MAX_VALUE, provider.maxOutputTokens());
        assertEquals(1, logged.size());
        assertEquals(true, logged.get(0).contains("pack-one/a"));
        assertEquals(false, logged.get(0).contains("max_tokens="));
    }
}
