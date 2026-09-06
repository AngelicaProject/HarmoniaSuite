package com.harmoniasuite.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "harmonia")
public class HarmoniaProperties {

    private String workspace = ".";
    private String dbPath = "data/harmonia.db";
    private final Gemini gemini = new Gemini();
    private final Openrouter openrouter = new Openrouter();
    private final Http http = new Http();
    private final App app = new App();

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getDbPath() {
        return dbPath;
    }

    public void setDbPath(String dbPath) {
        this.dbPath = dbPath;
    }

    public Gemini getGemini() {
        return gemini;
    }

    public Openrouter getOpenrouter() {
        return openrouter;
    }

    public Http getHttp() {
        return http;
    }

    public App getApp() {
        return app;
    }

    public static class App {
        private String version = "dev";
        private String buildTime = "";

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getBuildTime() {
            return buildTime;
        }

        public void setBuildTime(String buildTime) {
            this.buildTime = buildTime;
        }
    }

    public static class Http {
        @Min(1000) private long connectTimeoutMs = 15000;
        @Min(30000) private long readTimeoutMs = 300000;

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public static class Gemini {
        private String apiKey = "";
        @NotBlank
        private String model = "gemini-3.5-flash-lite";
        private List<String> models =
                new ArrayList<>(List.of("gemini-3.5-flash-lite", "gemini-3.1-flash-lite"));
        @Min(500) private int maxInputChars = 500000;
        @Min(512) private int maxOutputTokens = 65536;
        @Min(1) private int retries = 5;
        @Min(0) private long requestDelayMs = 5000;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models == null ? new ArrayList<>() : new ArrayList<>(models);
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public long getRequestDelayMs() {
            return requestDelayMs;
        }

        public void setRequestDelayMs(long requestDelayMs) {
            this.requestDelayMs = requestDelayMs;
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            this.maxInputChars = maxInputChars;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    public static class Openrouter {
        private String apiKey = "";
        private String model = "google/gemini-flash-1.5";
        @Min(500) private int maxInputChars = 500000;
        @Min(512) private int maxOutputTokens = 65536;
        @Min(1) private int retries = 5;
        @Min(0) private long requestDelayMs = 1000;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            this.maxInputChars = maxInputChars;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public long getRequestDelayMs() {
            return requestDelayMs;
        }

        public void setRequestDelayMs(long requestDelayMs) {
            this.requestDelayMs = requestDelayMs;
        }
    }
}
