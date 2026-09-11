import { describe, expect, it } from "vitest";
import { fuzzyScore } from "./useCommandPalette";

describe("fuzzyScore", () => {
  it("ranks a prefix first", () => {
    expect(fuzzyScore("app", "Application")).toBe(0);
  });

  it("matches a substring after a prefix", () => {
    const score = fuzzyScore("cat", "A cat");
    expect(score).toBeGreaterThan(1);
    expect(score).toBeLessThan(2);
  });

  it("matches characters in order with gaps", () => {
    const score = fuzzyScore("apl", "Application");
    expect(score).toBeGreaterThanOrEqual(2);
    expect(score).toBeLessThan(3);
  });

  it("returns infinity when there is no match", () => {
    expect(fuzzyScore("xyz", "Application")).toBe(Infinity);
  });
});
