package com.harmoniasuite.service.ai;

import com.harmoniasuite.config.HarmoniaProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class GeminiProvider implements LlmProvider {

    private final GeminiApiClient client;
    private final HarmoniaProperties properties;
    private final AiSettingsService ai;

    public GeminiProvider(GeminiApiClient client, HarmoniaProperties properties, AiSettingsService ai) {
        this.client = client;
        this.properties = properties;
        this.ai = ai;
    }

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public String resolveModel(String override) {
        String useModel = override == null || override.isBlank()
                ? properties.getGemini().getModel() : override;
        if (!properties.getGemini().getModels().contains(useModel)) {
            throw new IllegalArgumentException("неизвестная модель: " + useModel);
        }
        return useModel;
    }

    @Override
    public void requireApiKey() {
        if (ai.effectiveGeminiKey().isBlank()) {
            throw new IllegalStateException("Нет ключа Gemini: задайте его в настройках или переменной GEMINI_API_KEY");
        }
    }

    @Override
    public long delayMs() {
        return properties.getGemini().getRequestDelayMs();
    }

    @Override
    public int maxInputChars() {
        return properties.getGemini().getMaxInputChars();
    }

    @Override
    public int maxOutputTokens() {
        return properties.getGemini().getMaxOutputTokens();
    }

    @Override
    public int retries() {
        return properties.getGemini().getRetries();
    }

    @Override
    public String limitHint() {
        return ", дневной лимит см. в AI Studio";
    }

    @Override
    public LlmResult chat(String model, String systemPrompt, List<String> sources, int maxOut,
                          Consumer<String> log, AtomicInteger failedAttempts) throws Exception {
        return client.translateIndexed(model, ai.effectiveGeminiKey(), systemPrompt,
                sources, maxOut, retries(), 10.0, log, failedAttempts);
    }
}
