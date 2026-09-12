import { cp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const desktopRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const checkoutRoot = resolve(desktopRoot, "..", "..");
const platform = process.platform === "win32" ? "windows" : process.platform === "linux" ? "linux" : null;
const architecture = process.arch === "x64" ? "x64" : null;

if (!platform || !architecture) {
  throw new Error(`unsupported desktop packaging target: ${process.platform}/${process.arch}`);
}

const electronDistribution = join(desktopRoot, "node_modules", "electron", "dist");
const compiledShell = join(desktopRoot, "dist");
const frontendDistribution = join(checkoutRoot, "frontend", "dist");
const output = join(desktopRoot, "artifacts", `${platform}-${architecture}`);
const appPayload = join(output, "resources", "app");

await assertDirectory(electronDistribution, "Electron runtime");
await assertFile(join(compiledShell, "main.js"), "compiled Electron main process");
await assertFile(join(frontendDistribution, "index.html"), "frontend production output");

await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });

// The Electron distribution is copied as-is so the result is runnable without another build
// step. The app itself is placed in resources/app, which is the unpacked Electron payload
// contract consumed by Phase 5 activation.
await cp(electronDistribution, output, { recursive: true, dereference: true });
await mkdir(appPayload, { recursive: true });
await cp(compiledShell, join(appPayload, "dist"), { recursive: true, dereference: true });
await cp(frontendDistribution, join(output, "frontend", "dist"), {
  recursive: true,
  dereference: true,
});

const packageJson = JSON.parse(await readFile(join(desktopRoot, "package.json"), "utf8"));
const runtimePackage = {
  name: packageJson.name,
  version: packageJson.version,
  private: true,
  type: "module",
  main: "dist/main.js",
};
await writeFile(
  join(appPayload, "package.json"),
  `${JSON.stringify(runtimePackage, null, 2)}\n`,
  "utf8",
);

async function assertFile(path, label) {
  try {
    const info = await stat(path);
    if (!info.isFile()) throw new Error(`${label} is not a file: ${path}`);
  } catch (error) {
    throw new Error(`${label} is missing: ${path}`, { cause: error });
  }
}

async function assertDirectory(path, label) {
  try {
    const info = await stat(path);
    if (!info.isDirectory()) throw new Error(`${label} is not a directory: ${path}`);
  } catch (error) {
    throw new Error(`${label} is missing: ${path}`, { cause: error });
  }
}
