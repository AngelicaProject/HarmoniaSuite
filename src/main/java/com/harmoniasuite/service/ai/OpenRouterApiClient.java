package com.harmoniasuite.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class OpenRouterApiClient {

    private static final String URL = "https://openrouter.ai/api/v1/chat/completions";

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile String lastProvider = "";
    private volatile List<ModelBrief> cachedCatalog;
    private volatile long catalogAt;

    public record ModelBrief(String id, String name, int maxCompletion, long context, boolean reasoning) {
    }

    private static final long LIMITS_TTL_MS = 3600_000;
    private static final String MODELS_URL = "https://openrouter.ai/api/v1/models";

    public OpenRouterApiClient(ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    public List<ModelBrief> models() {
        return catalog();
    }

    public boolean supportsReasoning(String model) {
        for (ModelBrief m : catalog()) {
            if (m.id().equals(model)) {
                return m.reasoning();
            }
        }
        return false;
    }

    private List<ModelBrief> catalog() {
        List<ModelBrief> cached = cachedCatalog;
        if (cached != null && System.currentTimeMillis() - catalogAt < LIMITS_TTL_MS) {
            return cached;
        }
        try {
            String responseText = LlmHttp.getForText(restClient, MODELS_URL, Map.of());
            List<ModelBrief> parsed = parseModels(objectMapper.readTree(responseText));
            cachedCatalog = parsed;
            catalogAt = System.currentTimeMillis();
            return parsed;
        } catch (Exception ignored) {
            return cached != null ? cached : List.of();
        }
    }

    static List<ModelBrief> parseModels(JsonNode root) {
        List<ModelBrief> out = new ArrayList<>();
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return out;
        }
        for (JsonNode node : data) {
            String id = node.path("id").asText("");
            if (id.isBlank()) {
                continue;
            }
            boolean reasoning = false;
            JsonNode params = node.path("supported_parameters");
            if (params.isArray()) {
                for (JsonNode p : params) {
                    if ("reasoning".equals(p.asText())) {
                        reasoning = true;
                        break;
                    }
                }
            }
            out.add(new ModelBrief(id, node.path("name").asText(""),
                    node.path("top_provider").path("max_completion_tokens").asInt(0),
                    node.path("context_length").asLong(0), reasoning));
        }
        out.sort(Comparator.comparing(ModelBrief::id));
        return out;
    }

    public String checkKey(String apiKey) throws Exception {
        String responseText = LlmHttp.getForText(restClient,
                "https://openrouter.ai/api/v1/auth/key", Map.of("Authorization", "Bearer " + apiKey));
        return objectMapper.readTree(responseText).path("data").path("label").asText("");
    }

    static ObjectNode requestBody(ObjectMapper objectMapper, String model, String systemPrompt,
                                   String userJson, int maxOutputTokens, String reasoningEffort) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", maxOutputTokens);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.putObject("reasoning").put("effort", reasoningEffort);
        } else {
            body.putObject("reasoning").put("enabled", false);
        }
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userJson);
        return body;
    }

    public LlmResult translateIndexed(String model, String apiKey, String systemPrompt,
                                      List<String> sources, int maxOutputTokens, String reasoningEffort,
                                      int retries, double baseWaitSeconds, Consumer<String> log,
                                      AtomicInteger failedAttempts) {
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                ArrayNode items = objectMapper.createArrayNode();
                for (int i = 0; i < sources.size(); i++) {
                    ObjectNode item = items.addObject();
                    item.put("i", i);
                    item.put("s", sources.get(i));
                }
                ObjectNode body = requestBody(objectMapper, model, systemPrompt,
                        objectMapper.writeValueAsString(items), maxOutputTokens, reasoningEffort);

                String responseText = LlmHttp.postForText(restClient, URL,
                        Map.of("Authorization", "Bearer " + apiKey,
                                "X-Title", "HarmoniaSuite"),
                        body);

                JsonNode root = objectMapper.readTree(responseText);
                String actualModel = root.path("model").asText("");
                String actualProvider = root.path("provider").asText("");
                if (actualProvider.isBlank() && actualModel.contains("/")) {
                    actualProvider = actualModel.substring(0, actualModel.indexOf('/'));
                }
                if ((!actualProvider.isBlank() && !actualProvider.equals(lastProvider))
                        || (!actualModel.isBlank() && !actualModel.equals(model))) {
                    lastProvider = actualProvider;
                    log.accept("[OpenRouter] провайдер: " + (actualProvider.isBlank() ? "—" : actualProvider)
                            + " · модель: " + (actualModel.isBlank() ? model : actualModel));
                }
                JsonNode choice = root.path("choices").path(0);
                boolean truncated = "length".equals(choice.path("finish_reason").asText());
                JsonNode usage = root.path("usage");
                int inTokens = usage.path("prompt_tokens").asInt(0);
                int outTokens = usage.path("completion_tokens").asInt(0);
                String text = choice.path("message").path("content").asText();
                String thinking = choice.path("message").path("reasoning_content").asText("");
                if (thinking.isBlank()) {
                    thinking = choice.path("message").path("reasoning").asText("");
                }
                String thought = thinking.isBlank()
                        ? ""
                        : " (маршрут думает вместо ответа, reasoning ~" + thinking.length() + " символов)";
                if (!thinking.isBlank()) {
                    log.accept("[REASONING] " + (thinking.length() > 1500
                            ? thinking.substring(0, 1500) + "… (всего " + thinking.length() + ")"
                            : thinking));
                }
                if (text == null || text.isBlank()) {
                    if (truncated) {
                        return new LlmResult(Map.of(), true, inTokens, outTokens, actualProvider == null ? "" : actualProvider);
                    }
                    log.accept("[ОШИБКА] Пустой ответ OpenRouter" + thought);
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
                            ? "[ОШИБКА] Ответ обрезан лимитом max_tokens (выход " + outTokens + "/"
                                    + maxOutputTokens + " токенов, текст " + text.length()
                                    + " символов)" + thought + ", делю пачку"
                            : "[ОШИБКА] Некорректный JSON от OpenRouter: " + LlmJson.excerpt(text));
                    return null;
                }
                return new LlmResult(byIndex, truncated, inTokens, outTokens,
                        actualProvider == null ? "" : actualProvider);
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
}
