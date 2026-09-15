package com.harmoniasuite.source.infrastructure.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Runtime limits and managed roots for source upload ingestion. */
@Validated
@ConfigurationProperties(prefix = "harmonia.source-ingestion")
public class SourceIngestionProperties {

    private String stagingPath = "data/source-staging";
    private String artifactPath = "data/source-artifacts";
    @Min(1) private long maxUploadBytes = 4_294_967_296L;
    @Min(1) private long maxHxsBytes = 4_294_967_296L;
    @Min(1) private long maxChunkBytes = 16_777_216L;
    @Min(1) private long staleUploadHours = 24L;
    private int zstdLevel = 3;

    public String getStagingPath() { return stagingPath; }
    public void setStagingPath(String stagingPath) { this.stagingPath = stagingPath; }
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public long getMaxHxsBytes() { return maxHxsBytes; }
    public void setMaxHxsBytes(long maxHxsBytes) { this.maxHxsBytes = maxHxsBytes; }
    public long getMaxChunkBytes() { return maxChunkBytes; }
    public void setMaxChunkBytes(long maxChunkBytes) { this.maxChunkBytes = maxChunkBytes; }
    public long getStaleUploadHours() { return staleUploadHours; }
    public void setStaleUploadHours(long staleUploadHours) { this.staleUploadHours = staleUploadHours; }
    public int getZstdLevel() { return zstdLevel; }
    public void setZstdLevel(int zstdLevel) { this.zstdLevel = zstdLevel; }
}
