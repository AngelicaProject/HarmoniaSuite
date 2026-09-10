package com.harmoniasuite.controller;

import com.harmoniasuite.dto.AiKeyCheckRequest;
import com.harmoniasuite.dto.UpdateAiSettingsRequest;
import com.harmoniasuite.service.ai.AiSettingsService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/ai")
public class AiSettingsController {

    private final AiSettingsService ai;

    public AiSettingsController(AiSettingsService ai) {
        this.ai = ai;
    }

    @GetMapping
    public Map<String, Object> status() {
        return ai.status();
    }

    @GetMapping("/models")
    public List<Map<String, String>> models() {
        return ai.openRouterModels();
    }

    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody AiKeyCheckRequest body) {
        return ai.checkAccess(body == null ? null : body.provider(), body == null ? null : body.key());
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody UpdateAiSettingsRequest body) {
        return ai.update(body);
    }
}
