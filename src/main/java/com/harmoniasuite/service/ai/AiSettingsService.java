package com.harmoniasuite.service.ai;

import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.dto.UpdateAiSettingsRequest;
import com.harmoniasuite.exception.HarmoniaSuiteBadRequestException;
import com.harmoniasuite.repository.SettingsRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

// AI provider settings. Keys are namespaced per provider (ai.<provider>.api-key)
// so future providers (openrouter, …) slot into the same shape.
@Service
public class AiSettingsService {

    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OPENROUTER = "openrouter";

    private static final Set<String> PROVIDERS = Set.of(PROVIDER_GEMINI, PROVIDER_OPENROUTER);

    private static final String KEY_PROVIDER = "ai.provider";
    private static final String KEY_GEMINI = "ai.gemini.api-key";
    private static final String KEY_OPENROUTER = "ai.openrouter.api-key";
    private static final String KEY_OR_MODEL = "ai.openrouter.model";
    private static final String KEY_OR_REASONING = "ai.openrouter.reasoning";

    private static final Set<String> REASONING = Set.of("", "low", "medium", "high");

    private final SettingsRepository settings;
    private final HarmoniaProperties properties;
    private final OpenRouterApiClient openRouter;
    private final GeminiApiClient gemini;

    public AiSettingsService(SettingsRepository settings, HarmoniaProperties properties,
            OpenRouterApiClient openRouter, GeminiApiClient gemini) {
        this.settings = settings;
        this.properties = properties;
        this.openRouter = openRouter;
        this.gemini = gemini;
    }

    public String provider() {
        String value = settings.get(KEY_PROVIDER);
        return value != null && PROVIDERS.contains(value) ? value : PROVIDER_GEMINI;
    }

    public String effectiveGeminiKey() {
        String stored = settings.get(KEY_GEMINI);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        String env = properties.getGemini().getApiKey();
        return env == null ? "" : env;
    }

    public String effectiveOpenRouterKey() {
        String stored = settings.get(KEY_OPENROUTER);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        String env = properties.getOpenrouter().getApiKey();
        return env == null ? "" : env;
    }

    public String openRouterModel() {
        String stored = settings.get(KEY_OR_MODEL);
        if (stored != null && !stored.isBlank()) {
            return stored.strip();
        }
        return properties.getOpenrouter().getModel();
    }

    public String openRouterReasoning() {
        String stored = settings.get(KEY_OR_REASONING);
        return stored == null ? "" : stored.strip();
    }

    public Map<String, Object> checkAccess(String provider, String key) {
        if (!PROVIDERS.contains(provider)) {
            throw new HarmoniaSuiteBadRequestException("Провайдер недоступен: " + provider);
        }
        try {
            if (PROVIDER_OPENROUTER.equals(provider)) {
                String use = key != null && !key.isBlank() ? key.strip() : effectiveOpenRouterKey();
                if (use.isBlank()) {
                    return Map.of("ok", false, "message", "ключ не задан");
                }
                String label = openRouter.checkKey(use);
                return Map.of("ok", true,
                        "message", label.isBlank() ? "Доступ OK" : "Доступ OK · " + label);
            }
            String use = key != null && !key.isBlank() ? key.strip() : effectiveGeminiKey();
            if (use.isBlank()) {
                return Map.of("ok", false, "message", "ключ не задан");
            }
            int models = gemini.checkKey(use);
            return Map.of("ok", true, "message", "Доступ OK · моделей: " + models);
        } catch (Exception e) {
            return Map.of("ok", false, "message", failureText(e.getMessage()));
        }
    }

    public List<Map<String, String>> openRouterModels() {
        List<Map<String, String>> out = new ArrayList<>();
        for (OpenRouterApiClient.ModelBrief m : openRouter.models()) {
            out.add(Map.of("id", m.id(), "name", m.name()));
        }
        return out;
    }

    private static String failureText(String message) {
        if (message == null || message.isBlank()) {
            return "ошибка доступа";
        }
        String flat = message.strip().replaceAll("\\s+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    public Map<String, Object> status() {
        String stored = settings.get(KEY_GEMINI);
        boolean hasStored = stored != null && !stored.isBlank();
        String env = properties.getGemini().getApiKey();
        boolean hasEnv = env != null && !env.isBlank();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", provider());
        map.put("providers", PROVIDERS.stream().sorted().toList());
        map.put("geminiKeySet", hasStored);
        map.put("geminiKeyHint", hasStored ? mask(stored) : "");
        map.put("geminiKeySource", hasStored ? "settings" : (hasEnv ? "env" : ""));
        map.put("geminiConfigured", hasStored || hasEnv);
        map.put("geminiModel", properties.getGemini().getModel());
        map.put("geminiModels", properties.getGemini().getModels());
        String orStored = settings.get(KEY_OPENROUTER);
        boolean orHasStored = orStored != null && !orStored.isBlank();
        String orEnv = properties.getOpenrouter().getApiKey();
        boolean orHasEnv = orEnv != null && !orEnv.isBlank();
        map.put("openrouterKeySet", orHasStored);
        map.put("openrouterKeyHint", orHasStored ? mask(orStored) : "");
        map.put("openrouterKeySource", orHasStored ? "settings" : (orHasEnv ? "env" : ""));
        map.put("openrouterConfigured", orHasStored || orHasEnv);
        map.put("openrouterModel", openRouterModel());
        map.put("openrouterReasoning", openRouterReasoning());
        return map;
    }

    public Map<String, Object> update(UpdateAiSettingsRequest patch) {
        if (patch.provider() != null) {
            if (!PROVIDERS.contains(patch.provider())) {
                throw new HarmoniaSuiteBadRequestException(
                        "Провайдер недоступен: " + patch.provider());
            }
            settings.set(KEY_PROVIDER, patch.provider());
        }
        if (patch.geminiKey() != null) {
            settings.set(KEY_GEMINI, patch.geminiKey().strip());
        }
        if (patch.openrouterKey() != null) {
            settings.set(KEY_OPENROUTER, patch.openrouterKey().strip());
        }
        if (patch.openrouterModel() != null) {
            settings.set(KEY_OR_MODEL, patch.openrouterModel().strip());
        }
        if (patch.openrouterReasoning() != null) {
            String reasoning = patch.openrouterReasoning().strip().toLowerCase(Locale.ROOT);
            if (!REASONING.contains(reasoning)) {
                throw new HarmoniaSuiteBadRequestException(
                        "Некорректный reasoning: " + patch.openrouterReasoning());
            }
            settings.set(KEY_OR_REASONING, reasoning);
        }
        return status();
    }

    private static String mask(String key) {
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return "••••" + tail;
    }
}
