package com.harmoniasuite.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class LlmJson {

    private LlmJson() {
    }

    static Map<Integer, String> extractIndexed(ObjectMapper objectMapper, String text) {
        try {
            JsonNode list = unwrap(objectMapper, text);
            if (list == null || !list.isArray()) {
                return null;
            }
            Map<Integer, String> out = new HashMap<>();
            for (JsonNode node : list) {
                if (!node.isObject()) {
                    return null;
                }
                JsonNode idx = node.has("i") ? node.get("i")
                        : node.has("index") ? node.get("index")
                        : node.has("id") ? node.get("id") : null;
                if (idx == null || !idx.canConvertToInt()) {
                    return null;
                }
                String t = node.path("t").isTextual() ? node.path("t").asText()
                        : node.path("translation").isTextual() ? node.path("translation").asText()
                        : node.path("s").isTextual() ? node.path("s").asText() : null;
                if (t == null) {
                    return null;
                }
                out.put(idx.asInt(), t);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static Map<Integer, String> extractPositional(ObjectMapper objectMapper, String text) {
        try {
            String json = unfence(text).strip();
            JsonNode parsed = objectMapper.readTree(json);
            if (!parsed.isArray()) {
                return null;
            }
            Map<Integer, String> out = new HashMap<>();
            int i = 0;
            for (JsonNode node : parsed) {
                if (!node.isTextual()) {
                    return null;
                }
                out.put(i++, node.asText());
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    static String snippet(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() > 400 ? flat.substring(0, 400) + "…" : flat;
    }

    private static JsonNode unwrap(ObjectMapper objectMapper, String text) throws Exception {
        JsonNode parsed = objectMapper.readTree(unfence(text).strip());
        if (parsed.isObject()) {
            for (String key : List.of("translations", "result", "items")) {
                if (parsed.has(key) && parsed.get(key).isArray()) {
                    return parsed.get(key);
                }
            }
            return null;
        }
        return parsed.isArray() ? parsed : null;
    }

    private static String unfence(String text) {
        String json = text.strip();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start > 0 && end > start) {
                json = json.substring(start + 1, end).strip();
            }
        }
        return json;
    }
}
