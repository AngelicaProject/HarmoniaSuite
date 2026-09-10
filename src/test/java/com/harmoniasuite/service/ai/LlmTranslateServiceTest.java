package com.harmoniasuite.service.ai;

import com.harmoniasuite.config.PromptResources;
import com.harmoniasuite.domain.TranslationEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmTranslateServiceTest {

    private LlmTranslateService service(int maxInputChars, int maxOutputTokens) {
        return new LlmTranslateService(null, null, new PromptResources("p")) {
            @Override
            protected int maxInputChars() {
                return maxInputChars;
            }

            @Override
            protected int maxOutputTokens() {
                return maxOutputTokens;
            }
        };
    }

    private static TranslationEntry entry(String id, String source) {
        TranslationEntry e = new TranslationEntry();
        e.setId(id);
        e.setSource(source);
        return e;
    }

    @Test
    @DisplayName("partitioning respects the char budget")
    void partitionRespectsCharBudget() {
        LlmTranslateService svc = service(100, 40000);
        List<TranslationEntry> pending = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pending.add(entry("e" + i, "x".repeat(40)));
        }
        List<List<TranslationEntry>> parts = svc.partition(pending);
        assertEquals(3, parts.size());
        assertEquals(2, parts.get(0).size());
        assertEquals(2, parts.get(1).size());
        assertEquals(1, parts.get(2).size());
    }

    @Test
    @DisplayName("partitioning respects the model output token budget")
    void partitionRespectsOutputBudget() {
        LlmTranslateService svc = service(1000000, 100);
        List<TranslationEntry> pending = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pending.add(entry("e" + i, "x".repeat(40)));
        }
        List<List<TranslationEntry>> parts = svc.partition(pending);
        assertEquals(3, parts.size());
        assertEquals(2, parts.get(0).size());
        assertEquals(2, parts.get(1).size());
        assertEquals(1, parts.get(2).size());
    }

    @Test
    @DisplayName("batches are cut by the provider item limit")
    void partitionRespectsItemCap() {
        LlmTranslateService svc = service(1000000, 40000);
        LlmTranslateService capped = new LlmTranslateService(null, null, new PromptResources("p")) {
            @Override
            protected int maxInputChars() {
                return 1000000;
            }

            @Override
            protected int maxOutputTokens() {
                return 40000;
            }

            @Override
            protected int maxBatchItems() {
                return 2;
            }
        };
        List<TranslationEntry> pending = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pending.add(entry("e" + i, "x".repeat(40)));
        }
        List<List<TranslationEntry>> parts = capped.partition(pending);
        assertEquals(3, parts.size());
        assertEquals(2, parts.get(0).size());
        assertEquals(2, parts.get(1).size());
        assertEquals(1, parts.get(2).size());
    }

    @Test
    @DisplayName("breaker trips after consecutive errors and resets on success")
    void breakerTripsAfterConsecutiveFails() {
        LlmTranslateService svc = service(1000000, 40000);
        for (int i = 0; i < LlmTranslateService.MAX_CONSECUTIVE_FAILS - 1; i++) {
            assertEquals(false, svc.noteAttempt(false));
        }
        assertEquals(true, svc.noteAttempt(false));
        assertEquals(false, svc.noteAttempt(true));
        assertEquals(false, svc.noteAttempt(false));
    }
}
