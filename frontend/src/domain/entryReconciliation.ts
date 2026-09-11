import type { Entry, FileStats } from "../api/types";
import { isCountedAsTranslated } from "./selections";
import { needsWork } from "./translationStatus";
import type { DisplayRowGroup } from "./workspace";

export function replaceEntryInGroups(
  groups: DisplayRowGroup[],
  entry: Entry,
): { groups: DisplayRowGroup[]; changed: boolean } {
  let changed = false;
  const nextGroups = (groups || []).map((group) => {
    const previous = group.cells.find((cell) => cell.id === entry.id);
    if (!previous) return group;
    changed = true;
    const cells = group.cells.map((cell) =>
      cell.id === entry.id ? entry : cell,
    );
    const un =
      (group.un || 0) -
      (needsWork(previous) ? 1 : 0) +
      (needsWork(entry) ? 1 : 0);
    return { ...group, cells, un };
  });
  return { groups: nextGroups, changed };
}

export function replaceEntryInRows(rows: Entry[], entry: Entry): Entry[] {
  const index = rows.findIndex((cell) => cell.id === entry.id);
  if (index < 0) return rows;
  return [...rows.slice(0, index), entry, ...rows.slice(index + 1)];
}

export function updateFileStats(
  files: FileStats[],
  previous: Entry | null,
  entry: Entry,
): FileStats[] {
  if (!previous || previous.file !== entry.file) return files;
  const delta =
    Number(isCountedAsTranslated(entry)) -
    Number(isCountedAsTranslated(previous));
  if (!delta) return files;
  return files.map((file) =>
    file.path === entry.file
      ? { ...file, translated: Math.max(0, (file.translated || 0) + delta) }
      : file,
  );
}
