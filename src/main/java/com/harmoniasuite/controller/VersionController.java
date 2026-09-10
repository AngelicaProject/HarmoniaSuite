package com.harmoniasuite.controller;

import com.harmoniasuite.config.AppVersion;
import com.harmoniasuite.config.HarmoniaProperties;
import com.harmoniasuite.service.update.UpdateService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {

    private final HarmoniaProperties properties;

    public VersionController(HarmoniaProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/version")
    public Map<String, Object> version() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", AppVersion.resolve(properties.getApp().getVersion(), "dev"));
        String built = AppVersion.resolve(properties.getApp().getBuildTime(), "");
        if (!built.isEmpty()) {
            map.put("buildTime", built);
        }
        String commit = UpdateService.resolveCommitSha(properties.getApp().getCommit());
        if (!commit.isEmpty()) {
            map.put("commit", commit);
        }
        return map;
    }
}
