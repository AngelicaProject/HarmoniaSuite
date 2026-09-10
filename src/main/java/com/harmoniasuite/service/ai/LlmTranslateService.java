package com.harmoniasuite.service.ai;

import com.harmoniasuite.config.PromptResources;
import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.ProjectRepository;
import com.harmoniasuite.util.TagSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class LlmTranslateService extends AbstractBatchTranslator {

    static final int MAX_CONSECUTIVE_FAILS = 8;

    private final String systemPrompt;
    private int requests;
    private long inTokens;
    private long outTokens;
    private int consecutiveFails;
    private String lastRoute = "";
    private final AtomicInteger failedAttempts = new AtomicInteger();
    private double tokPerChar = 0.6;

    public LlmTranslateService(
            ProjectRepository projectRepository,
            EntryRepository entryRepository,
            PromptResources prompts) {
        super(projectRepository, entryRepository);
        this.systemPrompt = prompts.geminiTranslate();
    }

    private List<String> tables = List.of();
    private String modelOverride = "";
    private LlmProvider provider;

    public void translate(UUID projectId, List<String> tables, String model, String reasoning,
            LlmProvider provider, Consumer<String> log) throws Exception {
        this.tables = tables == null ? List.of() : tables;
        this.modelOverride = provider.resolveModel(model);
        this.provider = provider;
        try {
            provider.reasoningOverride(provider.resolveReasoning(reasoning));
            provider.prepareModel(modelOverride, log);
            run(projectId, log);
        } finally {
            this.tables = List.of();
            this.modelOverride = "";
            this.provider = null;
            provider.reasoningOverride(null);
        }
    }

    private String activeModel() {
        return modelOverride;
    }

    @Override
    protected void requireApiKey() {
        provider.requireApiKey();
    }

    @Override
    protected List<TranslationEntry> selectPending(UUID projectId) {
        return entryRepository.pending(projectId, tables);
    }

    @Override
    protected int maxInputChars() {
        return provider.maxInputChars();
    }

    @Override
    protected int maxOutputTokens() {
        return provider.maxOutputTokens();
    }

    @Override
    protected int maxBatchItems() {
        return provider == null ? Integer.MAX_VALUE : provider.maxBatchItems();
    }

    /** Records the batch outcome; consecutive failures past the limit abort the run. */
    boolean recordAttempt(boolean ok) {
        if (ok) {
            consecutiveFails = 0;
            return false;
        }
        return ++consecutiveFails >= MAX_CONSECUTIVE_FAILS;
    }

    private void abortRun() {
        String hint = provider == null ? "" : provider.breakerHint();
        throw new IllegalStateException("Останавливаю прогон: " + consecutiveFails + " ошибок подряд"
                + (lastRoute.isBlank() ? "" : " (маршрут: " + lastRoute + ")") + ". " + hint);
    }

    @Override
    protected int estimateOutTokens(TranslationEntry entry) {
        int len = entry.getSource() == null ? 0 : entry.getSource().length();
        // Стартовая оценка сверху (кириллица + JSON-обрамление фразы),
        // дальше калибруется по фактическим токенам из usage.
        return (int) (len * tokPerChar) + 16;
    }

    private void calibrate(int inputChars, long batchOutTokens) {
        if (inputChars <= 0 || batchOutTokens <= 0) {
            return;
        }
        double observed = (double) batchOutTokens / inputChars;
        tokPerChar = Math.min(3.0, Math.max(0.6, tokPerChar * 0.75 + observed * 0.25));
    }

    @Override
    protected long delayMs() {
        return provider.delayMs();
    }

    @Override
    protected void onStart(List<TranslationEntry> pending, UUID projectId, Consumer<String> log) {
        requests = 0;
        inTokens = 0;
        outTokens = 0;
        consecutiveFails = 0;
        lastRoute = "";
        failedAttempts.set(0);
        log.accept("Entries to translate: " + pending.size() + (tables.isEmpty() ? ""
                : " (таблицы: " + tables.size() + ")")
                + " (" + provider.id() + "/" + activeModel() + provider.limitHint() + ")");
    }

    @Override
    protected void onEmpty(Consumer<String> log) {
        log.accept("Все фразы уже переведены!");
    }

    @Override
    protected void onComplete(UUID projectId, Consumer<String> log) {
        long remainingCount = entryRepository.remainingCount(projectId);
        log.accept("ГОТОВО. Не переведено: " + remainingCount + ". Запросов: " + requests
                + " (неуспешных попыток: " + failedAttempts.get() + ")"
                + ". Токены: вход " + format(inTokens) + " / выход " + format(outTokens));
    }

    private static String format(long value) {
        return String.format("%,d", value).replace(',', ' ');
    }

    @Override
    protected boolean executeBatch(List<TranslationEntry> batch, UUID projectId,
            Consumer<String> log) throws Exception {
        runWithSplitFallback(batch, log, part -> translateBatch(part, projectId, log));
        return true;
    }

    private int batchOutEstimate(List<TranslationEntry> batch) {
        int est = 0;
        for (TranslationEntry entry : batch) {
            est += estimateOutTokens(entry);
        }
        return est;
    }

    private boolean translateBatch(List<TranslationEntry> batch, UUID projectId,
            Consumer<String> log) throws Exception {
        List<String> sources = new ArrayList<>();
        int inputChars = 0;
        for (TranslationEntry entry : batch) {
            String source = entry.getSource() == null ? "" : entry.getSource();
            sources.add(source);
            inputChars += source.length();
        }
        int est = batchOutEstimate(batch);
        int maxOut = Math.min(provider.maxOutputTokens(),
                est + Math.max(512, est / 8));
        requests++;
        LlmResult result = provider.chat(activeModel(), systemPrompt,
                sources, maxOut, log, failedAttempts);
        if (result == null) {
            if (recordAttempt(false)) {
                abortRun();
            }
            return false;
        }
        if (result.route() != null && !result.route().isBlank()) {
            if (!lastRoute.isBlank() && !lastRoute.equals(result.route())) {
                provider.noteRouteChanged();
            }
            lastRoute = result.route();
        }
        inTokens += result.inTokens();
        outTokens += result.outTokens();
        if (requests % 10 == 0) {
            log.accept("Токены: вход " + format(inTokens) + " / выход " + format(outTokens)
                    + " (ток/симв " + String.format("%.2f", tokPerChar) + ")");
        }
        if (result.truncated() || result.byIndex().size() != batch.size()) {
            log.accept("[ОШИБКА] ответ обрезан/неполный (" + result.byIndex().size()
                    + "/" + batch.size() + "), делю пачку");
            if (result.truncated()) {
                int before = provider.maxOutputTokens();
                provider.noteTruncated(maxOut, result.outTokens());
                int after = provider.maxOutputTokens();
                if (after < before) {
                    log.accept("Замер: маршрут режет выход на ~" + after + " токенах (просили "
                            + maxOut + ") — дальше пачки под него");
                }
            }
            if (recordAttempt(false)) {
                abortRun();
            }
            return false;
        }
        List<String> translations = new ArrayList<>(batch.size());
        List<Integer> transientBad = new ArrayList<>();
        List<Integer> hardBad = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            TranslationCheck check = checkTranslation(sources.get(i), result.byIndex().get(i));
            if (check.problem() == null) {
                translations.add(check.translation());
            } else {
                log.accept("[ОШИБКА] " + check.problem() + " (индекс " + i + ")");
                translations.add(null);
                if (check.retryable()) {
                    transientBad.add(i);
                } else {
                    hardBad.add(i);
                }
            }
        }
        String now = Instant.now().toString();
        List<EntryRepository.TranslationWrite> writes = new ArrayList<>(batch.size());
        int goodChars = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (translations.get(i) == null) {
                continue;
            }
            TranslationEntry entry = batch.get(i);
            writes.add(new EntryRepository.TranslationWrite(
                    entry.getUuid(),
                    entry.getTranslation() == null ? "" : entry.getTranslation(),
                    entry.getStatus() == null ? "" : entry.getStatus(),
                    translations.get(i), "machine_translated"));
            entry.setTranslation(translations.get(i));
            entry.setStatus("machine_translated");
            goodChars += sources.get(i).length();
        }
        if (!writes.isEmpty()) {
            entryRepository.batchWriteTranslations(projectId, writes, provider.id(), now);
        }
        log.accept("[OK] " + writes.size() + " из " + batch.size()
                + " фраз (~" + inputChars + " символов, запрос " + requests + ")");
        calibrate(goodChars, result.outTokens());
        if (!transientBad.isEmpty()) {
            log.accept("[РЕТРАЙ] нестабильных: " + transientBad.size() + ", делю пачку");
            if (recordAttempt(false)) {
                abortRun();
            }
            return false;
        }
        for (int i : hardBad) {
            acceptSource(batch.get(i), sources.get(i), projectId, now, log);
        }
        recordAttempt(true);
        provider.noteBatchOk();
        return true;
    }

    private record TranslationCheck(String translation, String problem, boolean retryable) {
    }

    private TranslationCheck checkTranslation(String source, String raw) {
        if (raw == null || raw.isBlank()) {
            return new TranslationCheck(null, "пусто: нет перевода", true);
        }
        if (raw.equals(source)) {
            return new TranslationCheck(null, "эхо: перевод совпадает с исходником", false);
        }
        String translation = TagSupport.repairCasing(source, raw);
        return new TranslationCheck(translation, null, false);
    }

    private void acceptSource(TranslationEntry entry, String source, UUID projectId,
            String now, Consumer<String> log) {
        String oldStatus = entry.getStatus() == null ? "" : entry.getStatus();
        entryRepository.batchWriteTranslations(projectId,
                List.of(new EntryRepository.TranslationWrite(
                        entry.getUuid(), "", oldStatus, "", "no_translation_required")),
                provider.id(), now);
        entry.setTranslation("");
        entry.setStatus("no_translation_required");
        log.accept("[ЭХО] перевод не нужен: " + excerpt(source));
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 60 ? flat.substring(0, 60) + "…" : flat;
    }
}
