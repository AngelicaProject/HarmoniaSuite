package com.harmoniasuite.source.atlas;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration for the server-owned Atlas executable and its process limits. */
@Validated
@ConfigurationProperties(prefix = "harmonia.atlas")
public class AtlasProperties {

    private String executable = "";

    @Min(1)
    private long verificationTimeoutMs = 300_000;

    @Min(1)
    private int maxStdoutBytes = 1_048_576;

    @Min(1)
    private int maxStderrBytes = 65_536;

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public long getVerificationTimeoutMs() {
        return verificationTimeoutMs;
    }

    public void setVerificationTimeoutMs(long verificationTimeoutMs) {
        this.verificationTimeoutMs = verificationTimeoutMs;
    }

    public int getMaxStdoutBytes() {
        return maxStdoutBytes;
    }

    public void setMaxStdoutBytes(int maxStdoutBytes) {
        this.maxStdoutBytes = maxStdoutBytes;
    }

    public int getMaxStderrBytes() {
        return maxStderrBytes;
    }

    public void setMaxStderrBytes(int maxStderrBytes) {
        this.maxStderrBytes = maxStderrBytes;
    }
}
