package com.harmoniasuite.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public interface LlmProvider {

    String id();

    String resolveModel(String override);

    void requireApiKey();

    long delayMs();

    int maxInputChars();

    int maxOutputTokens();

    int retries();

    String limitHint();

    default void prepareModel(String model, Consumer<String> log) {
    }

    default int maxBatchItems() {
        return Integer.MAX_VALUE;
    }

    default void noteBatchOk() {
    }

    default void noteTruncated(int sent, int produced) {
    }

    default void noteRouteChanged() {
    }

    default String resolveReasoning(String override) {
        return "";
    }

    default void reasoningOverride(String reasoning) {
    }

    default String breakerHint() {
        return "Проверьте ключ, модель и сеть.";
    }

    LlmResult chat(String model, String systemPrompt, List<String> sources, int maxOut,
                   Consumer<String> log, AtomicInteger failedAttempts) throws Exception;
}
