package com.harmoniasuite.service.job;

import java.nio.file.Path;
import java.util.UUID;

public record JobPaths(UUID projectId, Path projectDir, Path root, Path output) {
}
