package com.harmoniasuite.source.atlas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/** Server-side trust-boundary client for Atlas HXS inspection. */
@Service
public class AtlasClient {

    private static final Pattern PUBLIC_ID = Pattern.compile("sha256:[0-9a-f]{64}");

    private final AtlasProperties properties;
    private final AtlasProcessRunner processRunner;
    private final ObjectMapper objectMapper;

    public AtlasClient(AtlasProperties properties, AtlasProcessRunner processRunner,
                       ObjectMapper objectMapper) {
        this.properties = properties;
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
    }

    public AtlasInspection inspect(Path hxsPath) {
        Path verifiedPath = requireReadableRegularFile(hxsPath);
        AtlasProcessResult result = run(List.of(
                executablePath().toString(),
                "inspect",
                verifiedPath.toString(),
                "--json"));
        if (result.exitCode() != 0) {
            throw new AtlasException(AtlasException.Reason.NON_ZERO_EXIT,
                    "Atlas inspect failed with exit code " + result.exitCode());
        }
        return parseInspection(result.stdout());
    }

    public String version() {
        AtlasProcessResult result = run(List.of(executablePath().toString(), "--version"));
        if (result.exitCode() != 0) {
            throw new AtlasException(AtlasException.Reason.NON_ZERO_EXIT,
                    "Atlas version command failed with exit code " + result.exitCode());
        }
        if (result.stdout().isBlank()) {
            throw new AtlasException(AtlasException.Reason.INVALID_OUTPUT,
                    "Atlas version command returned empty output");
        }
        return result.stdout().trim();
    }

    private AtlasProcessResult run(List<String> arguments) {
        AtlasProperties configured = properties;
        if (configured == null) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Atlas configuration is unavailable");
        }
        if (configured.getMaxStdoutBytes() < 1 || configured.getMaxStderrBytes() < 1) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Atlas output limits must be positive");
        }
        return processRunner.run(arguments,
                timeout(configured.getVerificationTimeoutMs()),
                configured.getMaxStdoutBytes(),
                configured.getMaxStderrBytes());
    }

    private Path executablePath() {
        String configuredExecutable = properties == null ? null : properties.getExecutable();
        if (configuredExecutable == null || configuredExecutable.isBlank()) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Atlas executable is not configured");
        }

        Path executable;
        try {
            executable = Path.of(configuredExecutable);
        } catch (InvalidPathException exception) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Atlas executable path is invalid", exception);
        }
        try {
            if (!Files.exists(executable)) {
                throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                        "Configured Atlas executable does not exist");
            }
            if (!Files.isRegularFile(executable)) {
                throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                        "Configured Atlas executable is not a regular file");
            }
            if (!Files.isExecutable(executable)) {
                throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                        "Configured Atlas executable cannot be executed");
            }
        } catch (SecurityException exception) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Configured Atlas executable cannot be inspected", exception);
        }
        return executable.toAbsolutePath().normalize();
    }

    private static Path requireReadableRegularFile(Path path) {
        if (path == null) {
            throw new AtlasException(AtlasException.Reason.INPUT_FILE,
                    "HXS path must not be null");
        }
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new AtlasException(AtlasException.Reason.INPUT_FILE,
                        "HXS file does not exist");
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new AtlasException(AtlasException.Reason.INPUT_FILE,
                        "HXS path is not a regular file");
            }
            if (!Files.isReadable(path)) {
                throw new AtlasException(AtlasException.Reason.INPUT_FILE,
                        "HXS file is not readable");
            }
            return path.toAbsolutePath().normalize();
        } catch (AtlasException exception) {
            throw exception;
        } catch (SecurityException exception) {
            throw new AtlasException(AtlasException.Reason.INPUT_FILE,
                    "HXS file cannot be inspected", exception);
        }
    }

    private AtlasInspection parseInspection(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            throw invalidOutput("Atlas inspect returned empty stdout");
        }

        try {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(stdout);
            if (root == null || !root.isObject()) {
                throw invalidOutput("Atlas inspect output is not a JSON object");
            }

            int hxsVersion = requiredInt(root, "hxsVersion");
            if (hxsVersion != 1) {
                throw invalidOutput("Atlas returned unsupported HXS version");
            }
            String gameVersion = requiredText(root, "gameVersion");
            String language = requiredText(root, "language");
            String scope = requiredText(root, "scope");
            if (!"full".equals(scope)) {
                throw invalidOutput("Atlas returned unsupported HXS scope");
            }
            String snapshotId = requiredPublicId(root, "snapshotId");
            String contentId = requiredPublicId(root, "contentId");
            String extractorVersion = requiredText(root, "extractorVersion");
            String luminaVersion = requiredText(root, "luminaVersion");
            long sheetCount = requiredNonNegativeLong(root, "sheetCount");
            long rowCount = requiredNonNegativeLong(root, "rowCount");
            long stringCellCount = requiredNonNegativeLong(root, "stringCellCount");

            return new AtlasInspection(hxsVersion, gameVersion, language, scope,
                    snapshotId, contentId, extractorVersion, luminaVersion,
                    sheetCount, rowCount, stringCellCount);
        } catch (JsonProcessingException exception) {
            throw new AtlasException(AtlasException.Reason.INVALID_OUTPUT,
                    "Atlas inspect returned invalid JSON", exception);
        }
    }

    private static int requiredInt(JsonNode root, String field) {
        JsonNode value = requiredNode(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidOutput("Atlas inspection field has invalid type: " + field);
        }
        return value.intValue();
    }

    private static long requiredNonNegativeLong(JsonNode root, String field) {
        JsonNode value = requiredNode(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidOutput("Atlas inspection field has invalid count: " + field);
        }
        return value.longValue();
    }

    private static String requiredPublicId(JsonNode root, String field) {
        String value = requiredText(root, field);
        if (!PUBLIC_ID.matcher(value).matches()) {
            throw invalidOutput("Atlas inspection field has invalid public ID: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = requiredNode(root, field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalidOutput("Atlas inspection field is blank or not text: " + field);
        }
        return value.textValue();
    }

    private static JsonNode requiredNode(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw invalidOutput("Atlas inspection field is missing: " + field);
        }
        return value;
    }

    private static AtlasException invalidOutput(String message) {
        return new AtlasException(AtlasException.Reason.INVALID_OUTPUT, message);
    }

    private static Duration timeout(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new AtlasException(AtlasException.Reason.CONFIGURATION,
                    "Atlas verification timeout is invalid");
        }
        return Duration.ofMillis(timeoutMs);
    }
}
