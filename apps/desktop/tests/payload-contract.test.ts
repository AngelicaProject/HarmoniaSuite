import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { verifyPackagedPayload } from "../scripts/payload-contract.mjs";

const temporaryRoots: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryRoots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("packaged desktop payload", () => {
  it("enforces the Windows Electron and ESM payload contract", async () => {
    const output = await mkdtemp(join(tmpdir(), "harmonia-desktop-payload-"));
    temporaryRoots.push(output);
    await mkdir(join(output, "resources", "app", "dist"), { recursive: true });
    await mkdir(join(output, "frontend", "dist"), { recursive: true });
    await writeFile(join(output, "electron.exe"), "electron");
    await writeFile(
      join(output, "resources", "app", "package.json"),
      JSON.stringify({ type: "module", main: "dist/main.js" }),
    );
    await writeFile(join(output, "resources", "app", "dist", "main.js"), "import './preload.js';\n");
    await writeFile(join(output, "resources", "app", "dist", "preload.js"), "export {};\n");
    await writeFile(
      join(output, "resources", "app", "dist", "runtime-paths.js"),
      `export const moduleDir = ${JSON.stringify(join(output, "resources", "app", "dist"))};\n`,
    );
    await writeFile(join(output, "frontend", "dist", "index.html"), "<!doctype html>");

    await expect(verifyPackagedPayload(output, "electron.exe")).resolves.toBeUndefined();
  });

  it("rejects a compiled main that reintroduces CommonJS globals", async () => {
    const output = await mkdtemp(join(tmpdir(), "harmonia-desktop-payload-"));
    temporaryRoots.push(output);
    await mkdir(join(output, "resources", "app", "dist"), { recursive: true });
    await mkdir(join(output, "frontend", "dist"), { recursive: true });
    await writeFile(join(output, "electron.exe"), "electron");
    await writeFile(
      join(output, "resources", "app", "package.json"),
      JSON.stringify({ type: "module", main: "dist/main.js" }),
    );
    await writeFile(join(output, "resources", "app", "dist", "main.js"), "resolve(__dirname, 'preload.js');\n");
    await writeFile(join(output, "resources", "app", "dist", "preload.js"), "export {};\n");
    await writeFile(
      join(output, "resources", "app", "dist", "runtime-paths.js"),
      `export const moduleDir = ${JSON.stringify(join(output, "resources", "app", "dist"))};\n`,
    );
    await writeFile(join(output, "frontend", "dist", "index.html"), "<!doctype html>");

    await expect(verifyPackagedPayload(output, "electron.exe"))
      .rejects.toThrow("must not use CommonJS runtime globals");
  });
});
