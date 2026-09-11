import { describe, expect, it, vi } from "vitest";
import { buildContextMenuItems } from "./contextMenu";

describe("context menu item generation", () => {
  it("builds file actions from current selection state", () => {
    const run = vi.fn();
    const items = buildContextMenuItems(
      "file",
      { path: "a.csv" },
      {
        openFile: run,
        toggleExpand: run,
        isExpanded: () => false,
        toggleTranslate: run,
        isTranslateSelected: () => true,
        toggleExport: run,
        isExportSelected: () => false,
        focusPhrase: run,
        entryById: () => null,
        copyText: run,
        logText: () => "",
      },
    );

    expect(items?.map((item) => item.t)).toEqual([
      "Открыть",
      "Показать строки",
      "Копировать путь",
      "В перевод",
      "В сборку",
    ]);
    expect(items?.[3].sel).toBe(true);
    items?.[4].run();
    expect(run).toHaveBeenCalledWith("a.csv", true);
  });

  it("adds copy actions only when a phrase is available", () => {
    const items = buildContextMenuItems(
      "phrase",
      { id: "missing" },
      {
        openFile: vi.fn(),
        toggleExpand: vi.fn(),
        isExpanded: () => false,
        toggleTranslate: vi.fn(),
        isTranslateSelected: () => false,
        toggleExport: vi.fn(),
        isExportSelected: () => false,
        focusPhrase: vi.fn(),
        entryById: () => null,
        copyText: vi.fn(),
        logText: () => "",
      },
    );

    expect(items).toHaveLength(1);
  });
});
