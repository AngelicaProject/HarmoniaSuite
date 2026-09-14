import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { verifyPackagedPayload } from "../scripts/payload-contract.mjs";

const temporaryRoots: string[] = [];
const packagePayloadSource = await readFile(
  join(import.meta.dirname, "..", "scripts", "package-payload.mjs"),
  "utf8",
);
const windowsIconSource = await readFile(
  join(import.meta.dirname, "..", "scripts", "windows-icon.mjs"),
  "utf8",
);

afterEach(async () => {
  await Promise.all(temporaryRoots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("packaged desktop payload", () => {
  it("renames and brands the canonical Windows executable without an alias", async () => {
    const icon = await readFile(
      join(import.meta.dirname, "..", "..", "..", "assets", "branding", "harmonia-suite.ico"),
    );
    expect(icon.byteLength).toBeGreaterThan(0);
    expect(packagePayloadSource).toContain(
      'join(checkoutRoot, "assets", "branding", "harmonia-suite.ico")',
    );
    expect(packagePayloadSource).toContain('if (platform === "windows")');
    expect(windowsIconSource).toContain('import rcedit from "rcedit"');

    const copied = packagePayloadSource.indexOf(
      "await cp(electronDistribution, output, { recursive: true, dereference: true })",
    );
    const renamed = packagePayloadSource.indexOf(
      "await rename(join(output, sourceExecutableName), join(output, canonicalExecutableName))",
    );
    const branded = packagePayloadSource.indexOf("await applyWindowsIcon(");
    expect(copied).toBeGreaterThanOrEqual(0);
    expect(renamed).toBeGreaterThan(copied);
    expect(branded).toBeGreaterThan(renamed);
    expect(packagePayloadSource).not.toContain("await link(");
    expect(packagePayloadSource).not.toContain("compatibility alias");
  });

  it("enforces the Windows Electron and ESM payload contract", async () => {
    const output = await mkdtemp(join(tmpdir(), "harmonia-desktop-payload-"));
    temporaryRoots.push(output);
    await mkdir(join(output, "resources", "app", "dist"), { recursive: true });
    await mkdir(join(output, "frontend", "dist"), { recursive: true });
    await writeFile(join(output, "HarmoniaSuite.exe"), "electron");
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

    await expect(
      verifyPackagedPayload(output, "HarmoniaSuite.exe"),
    ).resolves.toBeUndefined();
    await expect(stat(join(output, "electron.exe"))).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("enforces the Linux canonical executable without an alias", async () => {
    const output = await mkdtemp(join(tmpdir(), "harmonia-desktop-payload-"));
    temporaryRoots.push(output);
    await mkdir(join(output, "resources", "app", "dist"), { recursive: true });
    await mkdir(join(output, "frontend", "dist"), { recursive: true });
    await writeFile(join(output, "harmonia-suite"), "electron");
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

    await expect(verifyPackagedPayload(output, "harmonia-suite")).resolves.toBeUndefined();
    await expect(stat(join(output, "electron"))).rejects.toMatchObject({ code: "ENOENT" });
  });

  it("rejects a compiled main that reintroduces CommonJS globals", async () => {
    const output = await mkdtemp(join(tmpdir(), "harmonia-desktop-payload-"));
    temporaryRoots.push(output);
    await mkdir(join(output, "resources", "app", "dist"), { recursive: true });
    await mkdir(join(output, "frontend", "dist"), { recursive: true });
    await writeFile(join(output, "HarmoniaSuite.exe"), "electron");
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

    await expect(verifyPackagedPayload(output, "HarmoniaSuite.exe"))
      .rejects.toThrow("must not use CommonJS runtime globals");
  });
});
