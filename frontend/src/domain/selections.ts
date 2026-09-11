import type { Entry } from "../api/types";
import { isTranslated, isNoTranslationRequired } from "./translationStatus";

export interface PersistedSelections {
  translate: string[];
  export: string[];
}

export function selectionStorageKey(projectId: string): string {
  return `hs-sel-${projectId}`;
}

export function parsePersistedSelections(
  value: string | null,
): PersistedSelections {
  try {
    const parsed: unknown = JSON.parse(value || "null");
    if (!parsed || typeof parsed !== "object") {
      return { translate: [], export: [] };
    }
    const record = parsed as Record<string, unknown>;
    return {
      translate: Array.isArray(record.translate)
        ? record.translate.filter(
            (item): item is string => typeof item === "string",
          )
        : [],
      export: Array.isArray(record.export)
        ? record.export.filter(
            (item): item is string => typeof item === "string",
          )
        : [],
    };
  } catch {
    return { translate: [], export: [] };
  }
}

export function serializeSelections(value: PersistedSelections): string {
  return JSON.stringify({
    translate: [...new Set(value.translate)],
    export: [...new Set(value.export)],
  });
}

export function toggleSelection(
  current: string[],
  file: string,
  selected: boolean,
): string[] {
  const result = new Set(current);
  if (selected) result.add(file);
  else result.delete(file);
  return [...result];
}

export function toggleVisibleSelection(
  current: string[],
  files: string[],
  selected: boolean,
): string[] {
  const result = new Set(current);
  for (const file of files) {
    if (selected) result.add(file);
    else result.delete(file);
  }
  return [...result];
}

export function translationEstimate(
  selectedFiles: string[],
  pendingByFile: Record<string, number>,
): { entries: number } {
  return {
    entries: selectedFiles.reduce(
      (total, file) => total + (pendingByFile[file] || 0),
      0,
    ),
  };
}

export function updatePendingCount(
  pendingByFile: Record<string, number>,
  previous: Entry | null,
  next: Entry,
  ready: boolean,
): Record<string, number> {
  if (!ready || !previous || previous.file !== next.file) return pendingByFile;
  const before = isPending(previous);
  const after = isPending(next);
  const delta = Number(after) - Number(before);
  if (!delta) return pendingByFile;
  const count = Math.max(0, (pendingByFile[next.file] || 0) + delta);
  const result = { ...pendingByFile };
  if (count) result[next.file] = count;
  else delete result[next.file];
  return result;
}

function isPending(entry: Entry): boolean {
  return (
    String(entry.translation || "").trim() === "" &&
    !isNoTranslationRequired(entry.status)
  );
}

export function isCountedAsTranslated(entry: Entry): boolean {
  return isTranslated(entry);
}
