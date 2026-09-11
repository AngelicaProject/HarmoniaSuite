import { describe, expect, it } from "vitest";
import type { Entry } from "../api/types";
import {
  decorateRowGroups,
  groupPreviewEntries,
  mergeEntries,
  pageCount,
} from "./fileRows";

function entry(id: string, rowIndex: number, columnIndex: number): Entry {
  return {
    uuid: id,
    id,
    source: id,
    translation: "",
    status: "untranslated",
    file: "dialog.csv",
    rowIndex,
    columnIndex,
  };
}

describe("file row transforms", () => {
  it("decorates groups with stable page positions and sections", () => {
    const groups = decorateRowGroups(
      [{ row: 1, cells: [entry("a", 1, 0)], un: 1 }],
      2,
    );

    expect(groups[0]).toMatchObject({ pos: 120, section: 1 });
  });

  it("groups preview cells by row and sorts cells by column", () => {
    const groups = groupPreviewEntries([
      entry("b", 4, 1),
      entry("a", 4, 0),
      entry("c", 2, 0),
    ]);

    expect(groups.map((group) => group.row)).toEqual([2, 4]);
    expect(groups[1].cells.map((cell) => cell.id)).toEqual(["a", "b"]);
    expect(groups[1].un).toBe(2);
  });

  it("merges entries by id without losing document metadata", () => {
    const document = {
      files: [],
      entries: [entry("a", 0, 0)],
      _loadMs: 12,
    };
    const replacement = { ...entry("a", 0, 0), translation: "ok" };
    const merged = mergeEntries(document, [replacement, entry("b", 1, 0)]);

    expect(merged._loadMs).toBe(12);
    expect(merged.entries).toEqual([replacement, entry("b", 1, 0)]);
  });

  it("returns at least one page for an empty result", () => {
    expect(pageCount(0)).toBe(1);
    expect(pageCount(121)).toBe(3);
  });
});
