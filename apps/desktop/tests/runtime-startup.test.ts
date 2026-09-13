import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

import { moduleDir } from "../src/runtime-paths.js";

describe("desktop ESM startup paths", () => {
  it("resolves the preload beside the compiled main module", () => {
    expect(moduleDir).toBe(resolve("src"));
    expect(resolve(moduleDir, "preload.js")).toBe(resolve("src", "preload.js"));
  });

  it("does not retain CommonJS runtime globals in desktop sources", async () => {
    for (const sourceFile of ["main.ts", "local-gateway.ts"]) {
      const source = await readFile(resolve("src", sourceFile), "utf8");
      expect(source).not.toMatch(/\b(?:__dirname|__filename)\b/);
      expect(source).not.toMatch(/\brequire\s*\(/);
    }
  });
});
