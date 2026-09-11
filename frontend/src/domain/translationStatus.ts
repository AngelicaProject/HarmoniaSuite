export const ENTRY_STATUS = Object.freeze({
  UNTRANSLATED: "untranslated",
  MACHINE_TRANSLATED: "machine_translated",
  NO_TRANSLATION_REQUIRED: "no_translation_required",
  STALE: "stale",
  HUMAN_REVIEWED: "human_reviewed",
  APPROVED: "approved",
});

export const ENTRY_STATUS_OPTIONS = Object.freeze([
  Object.freeze({ value: ENTRY_STATUS.UNTRANSLATED, label: "Не переведено" }),
  Object.freeze({
    value: ENTRY_STATUS.NO_TRANSLATION_REQUIRED,
    label: "Не требует перевода",
  }),
  Object.freeze({
    value: ENTRY_STATUS.MACHINE_TRANSLATED,
    label: "Машинный перевод",
  }),
  Object.freeze({ value: ENTRY_STATUS.STALE, label: "Устарело" }),
  Object.freeze({
    value: ENTRY_STATUS.HUMAN_REVIEWED,
    label: "Проверено человеком",
  }),
  Object.freeze({ value: ENTRY_STATUS.APPROVED, label: "Одобрено" }),
]);

export const ENTRY_STATUS_SUMMARY = Object.freeze([
  Object.freeze({ value: ENTRY_STATUS.APPROVED, label: "Одобрено", dot: "ok" }),
  Object.freeze({
    value: ENTRY_STATUS.HUMAN_REVIEWED,
    label: "Проверено человеком",
    dot: "info",
  }),
  Object.freeze({
    value: ENTRY_STATUS.MACHINE_TRANSLATED,
    label: "Машинный перевод",
    dot: "warn",
  }),
  Object.freeze({
    value: ENTRY_STATUS.NO_TRANSLATION_REQUIRED,
    label: "Не требует перевода",
    dot: "mut",
  }),
  Object.freeze({
    value: ENTRY_STATUS.UNTRANSLATED,
    label: "Не переведено",
    dot: "mut",
  }),
  Object.freeze({ value: ENTRY_STATUS.STALE, label: "Устарело", dot: "bad" }),
]);

export const ENTRY_STATUS_DOTS = Object.freeze({
  [ENTRY_STATUS.APPROVED]: "ok",
  [ENTRY_STATUS.HUMAN_REVIEWED]: "info",
  [ENTRY_STATUS.MACHINE_TRANSLATED]: "warn",
  [ENTRY_STATUS.STALE]: "bad",
});

export const ENTRY_STATUS_TONES = Object.freeze({
  [ENTRY_STATUS.UNTRANSLATED]: "muted",
  [ENTRY_STATUS.NO_TRANSLATION_REQUIRED]: "no-translation",
  [ENTRY_STATUS.MACHINE_TRANSLATED]: "warning",
  [ENTRY_STATUS.STALE]: "outdated",
  [ENTRY_STATUS.HUMAN_REVIEWED]: "reviewed",
  [ENTRY_STATUS.APPROVED]: "success",
});

export function isNoTranslationRequired(status) {
  return status === ENTRY_STATUS.NO_TRANSLATION_REQUIRED;
}

export function isStale(status) {
  return status === ENTRY_STATUS.STALE;
}

export function statusTone(status) {
  return ENTRY_STATUS_TONES[status] || "muted";
}

export function isTranslated(entry) {
  return (
    !!entry &&
    (isNoTranslationRequired(entry.status) ||
      (String(entry.translation || "").trim() !== "" && !isStale(entry.status)))
  );
}

export function needsWork(entry) {
  return (
    !!entry &&
    !isNoTranslationRequired(entry.status) &&
    (!(entry.translation && entry.translation.trim()) || isStale(entry.status))
  );
}
