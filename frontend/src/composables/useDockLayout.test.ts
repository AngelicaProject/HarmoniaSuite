import { describe, expect, it } from "vitest";
import {
  getDropIndicatorClass,
  moveDockView,
  type DockActiveMap,
  type DockLayoutMap,
} from "./useDockLayout";

function layout(overrides: Partial<DockLayoutMap> = {}): DockLayoutMap {
  return {
    left: [],
    right: [],
    bottom: [],
    ...overrides,
  };
}

function active(overrides: Partial<DockActiveMap> = {}): DockActiveMap {
  return {
    left: null,
    right: null,
    bottom: null,
    ...overrides,
  };
}

describe("dock layout helpers", () => {
  it("moves a tab within a zone", () => {
    const currentLayout = layout({ left: ["a", "b", "c"] });
    const currentActive = active({ left: "a" });

    moveDockView(currentLayout, currentActive, "a", "left", "left", 2);

    expect(currentLayout.left).toEqual(["b", "a", "c"]);
    expect(currentActive.left).toBe("a");
  });

  it("moves a tab between zones and updates the active tab", () => {
    const currentLayout = layout({ left: ["a", "b"], right: ["c"] });
    const currentActive = active({ left: "a", right: "c" });

    moveDockView(currentLayout, currentActive, "b", "left", "right", 1);

    expect(currentLayout.left).toEqual(["a"]);
    expect(currentLayout.right).toEqual(["c", "b"]);
    expect(currentActive.left).toBe("a");
    expect(currentActive.right).toBe("b");
  });

  it("returns the CSS class for the current drop position", () => {
    expect(getDropIndicatorClass({ zone: "left", index: 1 }, "left", 1)).toBe(
      "drop-before",
    );
    expect(getDropIndicatorClass({ zone: "left", index: 1 }, "right", 1)).toBe(
      "",
    );
  });
});
