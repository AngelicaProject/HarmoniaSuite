package com.harmoniasuite.source.api.dto;

import java.util.List;
import java.util.Map;

public record ApiErrorResponse(String code, String message,
                               List<Violation> violations,
                               Map<String, Object> details) {
    public record Violation(String field, String message) {
    }
}
