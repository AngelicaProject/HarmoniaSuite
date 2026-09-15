package com.harmoniasuite.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "harmonia")
public class HarmoniaProperties {

    private String workspace = ".";
    private final Core core = new Core();
    private final App app = new App();
    private final SourceIngestion sourceIngestion = new SourceIngestion();

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public Core getCore() {
        return core;
    }

    public App getApp() {
        return app;
    }

    public SourceIngestion getSourceIngestion() {
        return sourceIngestion;
    }

    public static class Core {
        @NotBlank
        private String dbPath = "data/core/harmonia.db";

        @NotBlank
        private String postgresSchema = "harmonia_core";

        public String getDbPath() {
            return dbPath;
        }

        public void setDbPath(String dbPath) {
            this.dbPath = dbPath;
        }

        public String getPostgresSchema() {
            return postgresSchema;
        }

        public void setPostgresSchema(String postgresSchema) {
            this.postgresSchema = postgresSchema;
        }
    }

    public static class App {
        private String version = "dev";
        private String buildTime = "";
        private String commit = "";

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

        public String getCommit() {
            return commit;
        }

        public void setCommit(String commit) {
            this.commit = commit;
        }
    }

    public static class SourceIngestion {
        private String stagingPath = "data/source-staging";
        private String artifactPath = "data/source-artifacts";
        @Min(1) private long maxUploadBytes = 4_294_967_296L;
        @Min(1) private long maxHxsBytes = 4_294_967_296L;
        @Min(1) private long maxChunkBytes = 16_777_216L;
        @Min(1) private long staleUploadHours = 24L;
        private int zstdLevel = 3;

        public String getStagingPath() {
            return stagingPath;
        }

        public void setStagingPath(String stagingPath) {
            this.stagingPath = stagingPath;
        }

        public String getArtifactPath() {
            return artifactPath;
        }

        public void setArtifactPath(String artifactPath) {
            this.artifactPath = artifactPath;
        }

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        public void setMaxUploadBytes(long maxUploadBytes) {
            this.maxUploadBytes = maxUploadBytes;
        }

        public long getMaxHxsBytes() {
            return maxHxsBytes;
        }

        public void setMaxHxsBytes(long maxHxsBytes) {
            this.maxHxsBytes = maxHxsBytes;
        }

        public long getMaxChunkBytes() {
            return maxChunkBytes;
        }

        public void setMaxChunkBytes(long maxChunkBytes) {
            this.maxChunkBytes = maxChunkBytes;
        }

        public long getStaleUploadHours() {
            return staleUploadHours;
        }

        public void setStaleUploadHours(long staleUploadHours) {
            this.staleUploadHours = staleUploadHours;
        }

        public int getZstdLevel() {
            return zstdLevel;
        }

        public void setZstdLevel(int zstdLevel) {
            this.zstdLevel = zstdLevel;
        }
    }

}
