package com.harmoniasuite.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "harmonia")
public class HarmoniaProperties {

    private String workspace = ".";
    private final Core core = new Core();
    private final App app = new App();

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

}
