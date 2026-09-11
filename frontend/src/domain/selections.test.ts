import { describe, expect, it } from "vitest";
import type { Entry } from "../api/types";
import {
  parsePersistedSelections,
  selectionStorageKey,
  serializeSelections,
  toggleSelection,
  toggleVisibleSelection,
  translationEstimate,
  updatePendingCount,
} from "./selections";

const baseEntry: Entry = {
  uuid: "u",
  id: "id",
  source: "source",
  translation: "",
  status: "untranslated",
  file: "a.csv",
  rowIndex: 0,
  columnIndex: 0,
};

describe("selection transforms", () => {
  it("uses the project-scoped storage key and safely parses values", () => {
    expect(selectionStorageKey("p1")).toBe("hs-sel-p1");
    expect(parsePersistedSelections('{"translate":["a",2]}')).toEqual({
      translate: ["a"],
      export: [],
    });
    expect(parsePersistedSelections("broken")).toEqual({
      translate: [],
      export: [],
    });
  });

  it("serializes unique selections and toggles individual or visible files", () => {
    expect(serializeSelections({ translate: ["a", "a"], export: ["b"] })).toBe(
      '{"translate":["a"],"export":["b"]}',
    );
    expect(toggleSelection(["a"], "b", true)).toEqual(["a", "b"]);
    expect(toggleVisibleSelection(["a"], ["a", "b"], false)).toEqual([]);
  });

  it("calculates estimates and reconciles pending counts", () => {
    expect(translationEstimate(["a.csv", "b.csv"], { "a.csv": 2 })).toEqual({
      entries: 2,
    });
    expect(
      updatePendingCount(
        { "a.csv": 1 },
        baseEntry,
        { ...baseEntry, translation: "done" },
        true,
      ),
    ).toEqual({});
  });
});
