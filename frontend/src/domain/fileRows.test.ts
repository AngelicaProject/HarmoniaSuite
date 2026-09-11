import { describe, expect, it } from "vitest";
import type { Entry } from "../api/types";
import {
  decorateRowGroups,
  groupPreviewEntries,
  mergeEntries,
  pageCount,
  replacePageEntries,
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

  it("replaces the previous page while retaining the active entry and cache", () => {
    const oldA = entry("oldA", 0, 0);
    const oldB = entry("oldB", 1, 0);
    const unrelated = entry("unrelated", 9, 0);
    const newC = entry("newC", 2, 0);
    const newD = entry("newD", 3, 0);
    const document = {
      files: [
        { path: "dialog.csv", total: 10, translated: 0, untranslated: 10 },
      ],
      entries: [oldA, oldB, unrelated],
      _loadMs: 12,
    };

    const replaced = replacePageEntries(
      document,
      new Set(["oldA", "oldB"]),
      "oldB",
      [newC, newD],
    );

    expect(replaced.entries).toEqual([oldB, unrelated, newC, newD]);
    expect(replaced.files).toBe(document.files);
    expect(replaced._loadMs).toBe(12);
  });

  it("removes all previous page entries when there is no active entry", () => {
    const oldA = entry("oldA", 0, 0);
    const oldB = entry("oldB", 1, 0);
    const newC = entry("newC", 2, 0);

    const replaced = replacePageEntries(
      { files: [], entries: [oldA, oldB] },
      new Set(["oldA", "oldB"]),
      null,
      [newC, newC],
    );

    expect(replaced.entries).toEqual([newC]);
  });

  it("returns at least one page for an empty result", () => {
    expect(pageCount(0)).toBe(1);
    expect(pageCount(121)).toBe(3);
  });
});
