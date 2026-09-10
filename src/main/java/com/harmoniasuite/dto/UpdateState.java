package com.harmoniasuite.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum UpdateState {
    UNAVAILABLE("unavailable", "Исходники приложения недоступны"),
    TOOLCHAIN_REQUIRED("toolchain_required", "Для обновления потребуются компоненты Git и JDK"),
    UP_TO_DATE("up_to_date", null),
    UPDATE_AVAILABLE("update_available", null),
    LOCAL_AHEAD("local_ahead", "Локальная версия новее origin/main"),
    DIVERGED("diverged", "Локальная история отличается от origin/main"),
    CHECK_FAILED("check_failed", "Не удалось проверить наличие обновления");

    private final String wireValue;
    private final String message;

    UpdateState(String wireValue, String message) {
        this.wireValue = wireValue;
        this.message = message;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public String message() {
        return message;
    }
}
