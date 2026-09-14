import { readFile, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

export async function verifyPackagedPayload(output, executableName) {
  await assertFile(join(output, executableName), "canonical Electron runtime executable");
  await assertFile(join(output, "resources", "app", "package.json"), "runtime package manifest");
  await assertFile(join(output, "resources", "app", "dist", "main.js"), "compiled Electron main process");
  await assertFile(join(output, "resources", "app", "dist", "preload.js"), "compiled Electron preload");
  await assertFile(join(output, "resources", "app", "dist", "runtime-paths.js"), "compiled ESM runtime paths");
  await assertFile(join(output, "frontend", "dist", "index.html"), "frontend production output");

  const packageJson = JSON.parse(
    await readFile(join(output, "resources", "app", "package.json"), "utf8"),
  );
  if (packageJson.type !== "module" || packageJson.main !== "dist/main.js") {
    throw new Error("packaged runtime must be an ESM package with main=dist/main.js");
  }

  const mainSource = await readFile(
    join(output, "resources", "app", "dist", "main.js"),
    "utf8",
  );
  if (/\b(?:__dirname|__filename)\b/.test(mainSource) || /\brequire\s*\(/.test(mainSource)) {
    throw new Error("packaged Electron main must not use CommonJS runtime globals");
  }

  const runtimePaths = await import(
    pathToFileURL(join(output, "resources", "app", "dist", "runtime-paths.js")).href,
  );
  const compiledModuleDir = resolve(output, "resources", "app", "dist");
  if (runtimePaths.moduleDir !== compiledModuleDir) {
    throw new Error("compiled ESM runtime paths must resolve beside dist/main.js");
  }
  if (resolve(runtimePaths.moduleDir, "preload.js") !== join(compiledModuleDir, "preload.js")) {
    throw new Error("compiled preload path must resolve beside dist/main.js");
  }
}

async function assertFile(path, label) {
  try {
    const info = await stat(path);
    if (!info.isFile()) throw new Error(`${label} is not a file: ${path}`);
  } catch (error) {
    throw new Error(`${label} is missing: ${path}`, { cause: error });
  }
}
