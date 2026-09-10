package com.harmoniasuite.domain;

import java.util.List;

/**
 * Persistence-neutral criteria for searching translation entries.
 *
 * <p>The HTTP representation is deliberately kept outside the repository. This
 * object is the boundary between an entry use case and its persistence adapter.</p>
 */
public record EntryQuery(
        String rowKey,
        List<String> statuses,
        String file,
        String query,
        boolean onlyUntranslated) {

    public EntryQuery {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }

    public static EntryQuery empty() {
        return new EntryQuery(null, List.of(), null, null, false);
    }
}
