import { describe, expect, it } from "vitest";
import { buildFileTree } from "./fileTree";

describe("buildFileTree", () => {
  const files = [
    { path: "a/ready.csv", total: 2, translated: 2, untranslated: 0 },
    { path: "a/work.csv", total: 4, translated: 1, untranslated: 3 },
    { path: "empty.csv", total: 0, translated: 0, untranslated: 0 },
  ];

  it("builds nested directory rows and folds their totals", () => {
    const rows = buildFileTree(files, { expanded: { a: true } });
    expect(rows.map((row) => row.key)).toEqual([
      "d:a",
      "f:a/ready.csv",
      "f:a/work.csv",
      "f:empty.csv",
    ]);
    expect(rows[0]).toMatchObject({ type: "dir", need: 3 });
  });

  it("applies query and ready/empty filters", () => {
    expect(
      buildFileTree(files, {
        query: "csv",
        hideEmpty: true,
        hideReady: true,
      }).map((row) => row.key),
    ).toEqual(["d:a", "f:a/work.csv"]);
  });

  it("sorts by remaining work", () => {
    const rows = buildFileTree(
      [...files, { path: "top.csv", total: 5, translated: 0, untranslated: 5 }],
      { sort: "need" },
    );
    expect(rows.map((row) => row.key)).toEqual([
      "f:top.csv",
      "d:a",
      "f:empty.csv",
    ]);
  });
});
