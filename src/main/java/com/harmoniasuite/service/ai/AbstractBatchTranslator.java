package com.harmoniasuite.service.ai;

import com.harmoniasuite.domain.TranslationEntry;
import com.harmoniasuite.repository.EntryRepository;
import com.harmoniasuite.repository.ProjectRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public abstract class AbstractBatchTranslator {

    protected final ProjectRepository projectRepository;
    protected final EntryRepository entryRepository;

    protected AbstractBatchTranslator(ProjectRepository projectRepository, EntryRepository entryRepository) {
        this.projectRepository = projectRepository;
        this.entryRepository = entryRepository;
    }

    public final void run(UUID projectId, Consumer<String> log) throws Exception {
        requireApiKey();
        projectRepository.findById(projectId);
        prepare(projectId);
        List<TranslationEntry> pending = selectPending(projectId);
        onStart(pending, projectId, log);
        if (pending.isEmpty()) {
            onEmpty(log);
            return;
        }
        int done = 0;
        int idx = 0;
        while (idx < pending.size()) {
            List<TranslationEntry> batch = takeBatch(pending, idx);
            if (batch.isEmpty()) {
                break;
            }
            idx += batch.size();
            if (isCancelled()) {
                log.accept("Отменено пользователем");
                break;
            }
            onBatch(done + 1, done + batch.size(), pending.size(), log);
            if (executeBatch(batch, projectId, log)) {
                done += batch.size();
                onBatchComplete(done, pending.size(), log);
                sleep(delayMs());
            }
        }
        onComplete(projectId, log);
    }

    protected void prepare(UUID projectId) {
    }

    protected abstract void requireApiKey();

    protected abstract List<TranslationEntry> selectPending(UUID projectId);

    protected abstract int maxInputChars();

    protected abstract int maxOutputTokens();

    protected abstract int estimateOutTokens(TranslationEntry entry);

    protected abstract long delayMs();

    protected double outputFillRatio() {
        return 0.8;
    }

    protected int maxBatchItems() {
        return Integer.MAX_VALUE;
    }

    protected List<List<TranslationEntry>> partition(List<TranslationEntry> pending) {
        List<List<TranslationEntry>> parts = new ArrayList<>();
        int from = 0;
        while (from < pending.size()) {
            List<TranslationEntry> batch = takeBatch(pending, from);
            parts.add(batch);
            from += batch.size();
        }
        return parts;
    }

    protected List<TranslationEntry> takeBatch(List<TranslationEntry> pending, int from) {
        List<TranslationEntry> batch = new ArrayList<>();
        long inChars = 0;
        long outEstimate = 0;
        long outBudget = (long) (maxOutputTokens() * outputFillRatio());
        for (int i = from; i < pending.size(); i++) {
            TranslationEntry entry = pending.get(i);
            int srcLen = entry.getSource() == null ? 0 : entry.getSource().length();
            int est = estimateOutTokens(entry);
            if (!batch.isEmpty() && (inChars + srcLen > maxInputChars()
                    || outEstimate + est > outBudget
                    || batch.size() >= maxBatchItems())) {
                break;
            }
            batch.add(entry);
            inChars += srcLen;
            outEstimate += est;
        }
        return batch;
    }

    protected void onStart(List<TranslationEntry> pending, UUID projectId, Consumer<String> log) {
    }

    protected void onEmpty(Consumer<String> log) {
    }

    protected void onBatch(int from, int to, int total, Consumer<String> log) {
        log.accept("Пачка " + from + "-" + to + " из " + total);
    }

    protected abstract boolean executeBatch(List<TranslationEntry> batch, UUID projectId,
            Consumer<String> log) throws Exception;

    protected void onBatchComplete(int done, int total, Consumer<String> log) {
    }

    protected void onComplete(UUID projectId, Consumer<String> log) {
    }

    protected boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }

    protected final void runWithSplitFallback(List<TranslationEntry> batch, Consumer<String> log,
            BatchAttempt attempt) throws Exception {
        if (isCancelled()) {
            return;
        }
        if (attempt.run(batch)) {
            return;
        }
        if (batch.size() == 1) {
            log.accept("[SKIP] " + batch.getFirst().getId());
            return;
        }
        int mid = batch.size() / 2;
        log.accept("[FALLBACK] split " + mid + " + " + (batch.size() - mid));
        sleep(delayMs());
        runWithSplitFallback(batch.subList(0, mid), log, attempt);
        sleep(delayMs());
        runWithSplitFallback(batch.subList(mid, batch.size()), log, attempt);
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    protected interface BatchAttempt {
        boolean run(List<TranslationEntry> batch) throws Exception;
    }
}
