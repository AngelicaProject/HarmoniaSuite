#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { join } from "node:path";

const args = process.argv.slice(2);
const command = args.shift();
const values = new Map();
for (let index = 0; index < args.length; index += 2) {
  const key = args[index];
  const value = args[index + 1];
  if (!key?.startsWith("--") || value === undefined) throw new Error("arguments must be --name value pairs");
  values.set(key.slice(2), value);
}

const root = values.get("root");
const sha = values.get("sha")?.toLowerCase();
if (!root) throw new Error("--root is required");
const indexPath = join(root, "rolling", "generations", "index.json");

async function readIndex() {
  try {
    return JSON.parse(await readFile(indexPath, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return { schemaVersion: 1, latestGeneration: 0, commits: {} };
    throw error;
  }
}

const index = await readIndex();
if (index.schemaVersion !== 1 || typeof index.commits !== "object") throw new Error("rolling index schema is invalid");

function latestCommit(value) {
  return Object.entries(value.commits)
    .filter(([, entry]) => Number.isSafeInteger(Number(entry?.generation)) && Number(entry.generation) > 0)
    .sort(([, left], [, right]) => Number(right.generation) - Number(left.generation))[0]?.[0] ?? "";
}

function assertExpectedLatest() {
  const expected = values.get("expected-latest-sha");
  if (expected !== undefined && expected.toLowerCase() !== latestCommit(index)) {
    throw new Error("rolling publication state changed since policy decision");
  }
}

if (command === "latest") {
  if (values.size !== 1 || !values.has("root")) throw new Error("latest accepts only --root");
  process.stdout.write(`${latestCommit(index)}\n`);
  process.exit(0);
}

if (!/^[0-9a-f]{40}$/.test(sha ?? "")) throw new Error("full --sha is required");

if (command === "allocate") {
  assertExpectedLatest();
  const existing = index.commits[sha];
  process.stdout.write(`${existing?.generation ?? (Number(index.latestGeneration) + 1)}\n`);
  process.exit(0);
}

if (command !== "publish") throw new Error("command must be allocate or publish");
const generation = Number(values.get("generation"));
if (!Number.isSafeInteger(generation) || generation < 1) throw new Error("positive --generation is required");
assertExpectedLatest();
const platforms = ["windows", "linux"];
const incoming = {};
for (const platform of platforms) {
  const manifestPath = values.get(`${platform}-manifest`);
  const signaturePath = values.get(`${platform}-signature`);
  if (!manifestPath || !signaturePath) throw new Error(`missing ${platform} manifest/signature`);
  const manifest = await readFile(manifestPath);
  const signature = await readFile(signaturePath);
  const parsed = JSON.parse(manifest);
  if (parsed.schemaVersion !== 1 || parsed.channel !== "rolling" || parsed.generation !== generation || parsed.targetCommit !== sha) {
    throw new Error(`${platform} manifest identity does not match publication`);
  }
  incoming[platform] = { manifest, signature, manifestSha256: createHash("sha256").update(manifest).digest("hex") };
}

const existing = index.commits[sha];
if (existing && existing.generation !== generation) throw new Error("commit is already mapped to a different generation");
if (!existing && generation <= Number(index.latestGeneration)) throw new Error("generation is not monotonic");
const generationOwner = Object.entries(index.commits).find(
  ([candidate, entry]) => candidate !== sha && Number(entry?.generation) === generation,
);
if (generationOwner) throw new Error(`generation is already mapped to ${generationOwner[0]}`);

const generationDir = join(root, "rolling", "generations", String(generation).padStart(8, "0"));
for (const platform of platforms) {
  const destination = join(generationDir, platform);
  await mkdir(destination, { recursive: true });
  for (const [name, bytes] of [["manifest.json", incoming[platform].manifest], ["manifest.json.sig", incoming[platform].signature]]) {
    const path = join(destination, name);
    try {
      const current = await readFile(path);
      if (!current.equals(bytes)) throw new Error(`immutable generation file differs: ${path}`);
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      await writeFile(path, bytes, { flag: "wx" });
    }
  }
}

index.latestGeneration = Math.max(Number(index.latestGeneration), generation);
index.commits[sha] = {
  generation,
  manifests: Object.fromEntries(platforms.map((platform) => [platform, incoming[platform].manifestSha256])),
};
await mkdir(join(root, "rolling", "generations"), { recursive: true });
await writeFile(indexPath, `${JSON.stringify(index, null, 2)}\n`);

// Stable aliases are deliberately written last. A consumer can therefore never observe a
// stable alias pointing at a generation which is absent from the immutable history.
for (const platform of platforms) {
  const stableDir = join(root, "rolling", platform);
  await mkdir(stableDir, { recursive: true });
  await writeFile(join(stableDir, "manifest.json"), incoming[platform].manifest);
  await writeFile(join(stableDir, "manifest.json.sig"), incoming[platform].signature);
}
process.stdout.write(`${generation}\n`);
