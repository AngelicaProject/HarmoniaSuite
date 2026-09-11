import { describe, expect, it } from "vitest";
import type { Entry, FileStats } from "../api/types";
import {
  replaceEntryInGroups,
  replaceEntryInRows,
  updateFileStats,
} from "./entryReconciliation";
import type { DisplayRowGroup } from "./workspace";

function entry(id: string, translation = ""): Entry {
  return {
    uuid: id,
    id,
    source: id,
    translation,
    status: translation ? "human_reviewed" : "untranslated",
    file: "a.csv",
    rowIndex: 0,
    columnIndex: 0,
  };
}

describe("entry reconciliation", () => {
  it("replaces a cell and updates the row pending count", () => {
    const groups: DisplayRowGroup[] = [{ row: 0, cells: [entry("a")], un: 1 }];
    const result = replaceEntryInGroups(groups, entry("a", "ok"));

    expect(result.changed).toBe(true);
    expect(result.groups[0].cells[0].translation).toBe("ok");
    expect(result.groups[0].un).toBe(0);
  });

  it("keeps an unrelated preview immutable", () => {
    const rows = [entry("a")];
    expect(replaceEntryInRows(rows, entry("missing"))).toBe(rows);
  });

  it("updates only the matching file statistics", () => {
    const files: FileStats[] = [
      { path: "a.csv", total: 2, translated: 0, untranslated: 2 },
      { path: "b.csv", total: 2, translated: 1, untranslated: 1 },
    ];
    expect(updateFileStats(files, entry("a"), entry("a", "ok"))).toEqual([
      { path: "a.csv", total: 2, translated: 1, untranslated: 2 },
      { path: "b.csv", total: 2, translated: 1, untranslated: 1 },
    ]);
  });
});
