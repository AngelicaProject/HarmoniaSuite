package com.harmoniasuite.dto;

public record UpdateAiSettingsRequest(
        String provider,
        String geminiKey,
        String openrouterKey,
        String openrouterModel,
        String openrouterReasoning) {
}
