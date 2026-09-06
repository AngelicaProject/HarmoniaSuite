package com.harmoniasuite.dto;

public record UpdateSourceSettingsRequest(
        String mode,
        String gamePath,
        String unpackerExe,
        String csvDir) {
}
