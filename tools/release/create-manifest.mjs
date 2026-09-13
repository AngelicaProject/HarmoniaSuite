#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { isStableSemVer, parseSemVer } from "../version/check-versions.mjs";

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key?.startsWith("--") || !value) throw new Error("manifest arguments must be --name value pairs");
  args.set(key.slice(2), value);
}

const platform = args.get("platform");
const targetCommit = args.get("target-commit");
const productVersion = args.get("product-version");
const minInstallerVersion = args.get("min-installer-version");
const output = args.get("out");
const generation = Number(args.get("generation") ?? "0");
if (!new Set(["windows", "linux"]).has(platform)) throw new Error("platform must be windows or linux");
if (!/^[0-9a-f]{40}$/i.test(targetCommit ?? "")) throw new Error("target-commit must be a full SHA");
if (!productVersion?.trim() || !Number.isSafeInteger(generation) || generation < 1) {
  throw new Error("product-version and positive generation are required");
}
parseSemVer(productVersion, "product-version");
if (minInstallerVersion !== undefined && !isStableSemVer(minInstallerVersion)) {
  throw new Error(`min-installer-version must be stable SemVer: ${minInstallerVersion}`);
}

const catalogPath = join(dirname(fileURLToPath(import.meta.url)), "production-toolchain-catalog.json");
const catalog = JSON.parse(await readFile(catalogPath, "utf8"));
if (catalog.schemaVersion !== 1 || !catalog.descriptors?.[platform]) {
  throw new Error("production toolchain catalog is invalid");
}

const common = {
  schemaVersion: 1,
  channel: "rolling",
  generation,
  productVersion,
  targetCommit: targetCommit.toLowerCase(),
};
if (minInstallerVersion) common.minInstallerVersion = minInstallerVersion;
const descriptors = catalog.descriptors[platform];

if (!output) throw new Error("--out is required");
await mkdir(dirname(output), { recursive: true });
await writeFile(output, `${JSON.stringify({ ...common, jdk: descriptors.jdk, node: descriptors.node }, null, 2)}\n`, { flag: "wx" });
