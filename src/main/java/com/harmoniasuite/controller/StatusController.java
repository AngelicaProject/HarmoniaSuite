package com.harmoniasuite.controller;

import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.service.AiSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StatusController {

    private final HarmoniaProperties properties;
    private final AiSettingsService ai;

    public StatusController(HarmoniaProperties properties, AiSettingsService ai) {
        this.properties = properties;
        this.ai = ai;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of(
                "geminiConfigured", !ai.effectiveGeminiKey().isBlank(),
                "geminiModel", properties.getGemini().getModel(),
                "geminiModels", properties.getGemini().getModels(),
                "openrouterConfigured", !ai.effectiveOpenRouterKey().isBlank(),
                "openrouterModel", ai.openRouterModel(),
                "openrouterReasoning", ai.openRouterReasoning());
    }
}
