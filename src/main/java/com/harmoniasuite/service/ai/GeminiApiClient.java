package com.harmoniasuite.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class GeminiApiClient {

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public GeminiApiClient(ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    public LlmResult translateIndexed(String model, String apiKey, String systemPrompt,
                                      List<String> sources, int maxOutputTokens,
                                      int retries, double baseWaitSeconds, Consumer<String> log,
                                      AtomicInteger failedAttempts) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                ArrayNode items = objectMapper.createArrayNode();
                for (int i = 0; i < sources.size(); i++) {
                    ObjectNode item = items.addObject();
                    item.put("i", i);
                    item.put("s", sources.get(i));
                }
                ObjectNode body = objectMapper.createObjectNode();
                ArrayNode contents = body.putArray("contents");
                ObjectNode content = contents.addObject();
                content.put("role", "user");
                content.putArray("parts").addObject()
                        .put("text", objectMapper.writeValueAsString(items));
                ObjectNode system = body.putObject("systemInstruction");
                system.putArray("parts").addObject().put("text", systemPrompt);
                ObjectNode generation = body.putObject("generationConfig");
                generation.put("temperature", 0);
                generation.put("responseMimeType", "application/json");
                generation.put("maxOutputTokens", maxOutputTokens);

                // Сырой поток без конвертеров: сервер иногда отдаёт octet-stream.
                String responseText = LlmHttp.postForText(restClient, url, Map.of(), body);

                JsonNode root = objectMapper.readTree(responseText);
                JsonNode candidate = root.path("candidates").path(0);
                boolean truncated = "MAX_TOKENS".equals(candidate.path("finishReason").asText());
                int[] usage = readUsage(root);
                String text = candidate.path("content").path("parts").path(0).path("text").asText();
                if (text == null || text.isBlank()) {
                    if (truncated) {
                        return new LlmResult(Map.of(), true, usage[0], usage[1], "");
                    }
                    log.accept("[ОШИБКА] Пустой ответ Gemini");
                    return null;
                }
                Map<Integer, String> byIndex = LlmJson.extractIndexed(objectMapper, text);
                if (byIndex == null) {
                    byIndex = LlmJson.extractPositional(objectMapper, text);
                    if (byIndex != null) {
                        log.accept("[ПРЕДУПРЕЖДЕНИЕ] ответ без индексов, стыковка по порядку");
                    }
                }
                if (byIndex == null) {
                    if (failedAttempts != null) {
                        failedAttempts.incrementAndGet();
                    }
                    log.accept(truncated
                            ? "[ОШИБКА] Ответ обрезан лимитом maxOutputTokens, делю пачку"
                            : "[ОШИБКА] Некорректный JSON от Gemini: " + LlmJson.excerpt(text));
                    return null;
                }
                return new LlmResult(byIndex, truncated, usage[0], usage[1], "");
            } catch (Exception e) {
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                log.accept("[ОШИБКА API] попытка " + (attempt + 1) + "/" + retries + ": " + e.getMessage());
                if (failedAttempts != null) {
                    failedAttempts.incrementAndGet();
                }
                if (attempt == retries - 1) {
                    return null;
                }
                AbstractBatchTranslator.sleep((long) (LlmHttp.waitSeconds(e.getMessage(), attempt, baseWaitSeconds) * 1000));
            }
        }
        return null;
    }

    private static int[] readUsage(JsonNode root) {
        JsonNode meta = root.path("usageMetadata");
        return new int[]{
                meta.path("promptTokenCount").asInt(0),
                meta.path("candidatesTokenCount").asInt(0)};
    }

    public int checkKey(String apiKey) throws Exception {
        String responseText = LlmHttp.getForText(restClient,
                "https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey, Map.of());
        JsonNode models = objectMapper.readTree(responseText).path("models");
        return models.isArray() ? models.size() : 0;
    }
}
