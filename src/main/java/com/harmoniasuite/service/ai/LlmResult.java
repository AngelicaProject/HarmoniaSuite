package com.harmoniasuite.service.ai;

import java.util.Map;

public record LlmResult(Map<Integer, String> byIndex, boolean truncated, int inTokens, int outTokens,
                        String route) {
}
