package com.harmoniasuite.controller;

import com.harmoniasuite.dto.UpdateSourceSettingsRequest;
import com.harmoniasuite.service.SourceService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SourceService sources;

    public SettingsController(SourceService sources) {
        this.sources = sources;
    }

    @GetMapping
    public Map<String, Object> status() {
        return sources.status();
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody UpdateSourceSettingsRequest body) {
        return sources.update(body);
    }

    @GetMapping("/detect")
    public Map<String, String> detect() {
        return sources.detect();
    }
}
