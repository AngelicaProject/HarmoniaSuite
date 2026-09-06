package com.harmoniasuite.service;

import com.harmoniasuite.config.HarmoniaProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class OpenRouterProvider implements LlmProvider {

    private final OpenRouterApiClient client;
    private final HarmoniaProperties properties;
    private final AiSettingsService ai;
    private volatile String reasoningEffort = "";
    private volatile int itemCap = PROBE_ITEMS;
    private volatile int routeOutCap = Integer.MAX_VALUE;
    private volatile String sessionReasoning;
    private static final Set<String> REASONING =
            Set.of("", "off", "low", "medium", "high");
    private static final int PROBE_ITEMS = 40;
    private static final int MAX_ITEMS = 2000;

    public OpenRouterProvider(OpenRouterApiClient client, HarmoniaProperties properties, AiSettingsService ai) {
        this.client = client;
        this.properties = properties;
        this.ai = ai;
    }

    @Override
    public String id() {
        return "openrouter";
    }

    @Override
    public String resolveModel(String override) {
        if (override != null && !override.isBlank()) {
            return override.strip();
        }
        return ai.openRouterModel();
    }

    @Override
    public void requireApiKey() {
        if (ai.effectiveOpenRouterKey().isBlank()) {
            throw new IllegalStateException("Нет ключа OpenRouter: задайте его в настройках или переменной OPENROUTER_API_KEY");
        }
    }

    @Override
    public long delayMs() {
        return properties.getOpenrouter().getRequestDelayMs();
    }

    @Override
    public String resolveReasoning(String override) {
        if (override == null || override.isBlank()) {
            return null;
        }
        String norm = override.strip().toLowerCase(Locale.ROOT);
        if (!REASONING.contains(norm)) {
            throw new IllegalArgumentException("Некорректный reasoning: " + override);
        }
        return "off".equals(norm) ? "" : norm;
    }

    @Override
    public void reasoningOverride(String reasoning) {
        sessionReasoning = reasoning;
    }

    @Override
    public void prepareModel(String model, Consumer<String> log) {
        reasoningEffort = "";
        itemCap = PROBE_ITEMS;
        routeOutCap = Integer.MAX_VALUE;
        String want = sessionReasoning != null ? sessionReasoning : ai.openRouterReasoning();
        if (!want.isBlank()) {
            if (client.supportsReasoning(model)) {
                reasoningEffort = want;
            } else {
                log.accept("[OpenRouter] модель " + model + " не поддерживает reasoning, параметр пропущен");
            }
        }
        log.accept("[OpenRouter] модель " + model + ": старт " + PROBE_ITEMS + " фраз/пачка, окно ×2 до "
                + MAX_ITEMS + ", max_tokens из замера"
                + (reasoningEffort.isBlank() ? "" : ", reasoning=" + reasoningEffort)
                + (sessionReasoning != null ? " (сессия)" : ""));
    }

    @Override
    public int maxInputChars() {
        // Потолка нет: размер пачки задаёт окно slow-start, переполнение лечится сплитом.
        return Integer.MAX_VALUE;
    }

    @Override
    public int maxOutputTokens() {
        // Не догадка: сужается только замером реального обрезания маршрута (noteTruncated).
        return routeOutCap;
    }

    @Override
    public void noteTruncated(int sent, int produced) {
        // Обрезание нашим потолком даёт produced == sent; чужой рез раньше — строго меньше.
        if (produced > 0 && produced < sent && produced < routeOutCap) {
            routeOutCap = produced;
        }
    }

    @Override
    public void noteRouteChanged() {
        if (routeOutCap != Integer.MAX_VALUE) {
            routeOutCap = Integer.MAX_VALUE;
        }
    }

    @Override
    public int retries() {
        return properties.getOpenrouter().getRetries();
    }

    @Override
    public String limitHint() {
        return ", размер пачек из замера";
    }

    @Override
    public int maxBatchItems() {
        return itemCap;
    }

    @Override
    public void noteBatchOk() {
        itemCap = Math.min(MAX_ITEMS, itemCap * 2);
    }

    @Override
    public String breakerHint() {
        return "Маршрут не возвращает корректный JSON — смените модель.";
    }

    @Override
    public LlmResult chat(String model, String systemPrompt, List<String> sources, int maxOut,
                          Consumer<String> log, AtomicInteger failedAttempts) throws Exception {
        return client.translateIndexed(model, ai.effectiveOpenRouterKey(), systemPrompt,
                sources, maxOut, reasoningEffort, retries(), 5.0, log, failedAttempts);
    }
}
