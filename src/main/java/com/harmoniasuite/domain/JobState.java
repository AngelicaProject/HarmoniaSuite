package com.harmoniasuite.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JobState {

    private final String id;
    private final String action;
    private volatile String status = "queued";
    private final StringBuilder output = new StringBuilder();
    private Integer code;

    public JobState(String id, String action) {
        this.id = id;
        this.action = action;
    }

    public synchronized void append(String line) {
        output.append(line);
        if (!line.endsWith("\n")) {
            output.append('\n');
        }
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("action", action);
        map.put("status", status);
        map.put("output", output.toString());
        if (code != null) {
            map.put("code", code);
        }
        return map;
    }

    public String getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public synchronized String getOutput() {
        return output.toString();
    }

    public Integer getCode() {
        return code;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    private final Instant createdAt = Instant.now();

    public Instant getCreatedAt() {
        return createdAt;
    }
}
