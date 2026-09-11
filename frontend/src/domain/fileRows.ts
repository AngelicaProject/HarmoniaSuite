import type { Entry, RowGroup } from "../api/types";
import { needsWork } from "./translationStatus";
import type { DisplayRowGroup, ProjectDocument } from "./workspace";

export const ROW_GROUP_PAGE_SIZE = 60;

export function decorateRowGroups(
  groups: RowGroup[],
  page: number,
): DisplayRowGroup[] {
  return (groups || []).map((group, index) => {
    const pos = page * ROW_GROUP_PAGE_SIZE + index;
    return { ...group, pos, section: Math.floor(pos / 100) };
  });
}

export function mergeEntries(
  document: ProjectDocument | null,
  items: Entry[],
): ProjectDocument {
  const entries = new Map(
    (document?.entries || []).map((entry) => [entry.id, entry]),
  );
  for (const entry of items) {
    if (entry?.id) entries.set(entry.id, entry);
  }
  return {
    files: document?.files || [],
    entries: [...entries.values()],
    _loadMs: document?._loadMs,
  };
}

export function replacePageEntries(
  document: ProjectDocument | null,
  previousPageIds: ReadonlySet<string>,
  activeEntryId: string | null | undefined,
  nextPageEntries: Entry[],
): ProjectDocument {
  const entries = new Map<string, Entry>();
  for (const entry of document?.entries || []) {
    if (
      entry?.id &&
      (!previousPageIds.has(entry.id) || entry.id === activeEntryId)
    ) {
      entries.set(entry.id, entry);
    }
  }
  for (const entry of nextPageEntries) {
    if (entry?.id) entries.set(entry.id, entry);
  }
  return {
    files: document?.files || [],
    entries: [...entries.values()],
    _loadMs: document?._loadMs,
  };
}

export function groupPreviewEntries(entries: Entry[]): DisplayRowGroup[] {
  const groups = new Map<number, DisplayRowGroup>();
  for (const entry of entries || []) {
    let group = groups.get(entry.rowIndex);
    if (!group) {
      group = { row: entry.rowIndex, key: entry.rowKey, cells: [], un: 0 };
      groups.set(entry.rowIndex, group);
    }
    group.cells.push(entry);
    if (needsWork(entry)) group.un += 1;
  }
  const result = [...groups.values()].sort(
    (left, right) => left.row - right.row,
  );
  result.forEach((group) =>
    group.cells.sort((left, right) => left.columnIndex - right.columnIndex),
  );
  return result;
}

export function pageCount(totalGroups: number): number {
  return Math.ceil(totalGroups / ROW_GROUP_PAGE_SIZE) || 1;
}
