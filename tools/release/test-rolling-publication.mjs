import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import test from "node:test";

const exec = promisify(execFile);
const script = join(import.meta.dirname, "publish-rolling.mjs");
const A = "a".repeat(40);
const B = "b".repeat(40);

async function publish(root, sha, generation, expectedLatest = "") {
  const manifestPaths = {};
  const signaturePaths = {};
  for (const platform of ["linux", "windows"]) {
    const manifestPath = join(root, `${platform}-${generation}.json`);
    const signaturePath = join(root, `${platform}-${generation}.json.sig`);
    await writeFile(
      manifestPath,
      `${JSON.stringify({ schemaVersion: 1, channel: "rolling", generation, targetCommit: sha })}\n`,
      { flag: "w" },
    );
    await writeFile(signaturePath, `${sha}-${platform}-${generation}`);
    manifestPaths[platform] = manifestPath;
    signaturePaths[platform] = signaturePath;
  }
  await exec(process.execPath, [
    script,
    "publish",
    "--root",
    root,
    "--sha",
    sha,
    "--generation",
    String(generation),
    "--expected-latest-sha",
    expectedLatest,
    "--linux-manifest",
    manifestPaths.linux,
    "--linux-signature",
    signaturePaths.linux,
    "--windows-manifest",
    manifestPaths.windows,
    "--windows-signature",
    signaturePaths.windows,
  ]);
}

test("rolling publication is immutable, monotonic, and same-SHA rerun-safe", async () => {
  const root = await mkdtemp(join(tmpdir(), "harmonia-rolling-"));
  await publish(root, A, 1, "");
  const first = await readFile(join(root, "rolling/linux/manifest.json"));
  await publish(root, A, 1, A);
  assert.deepEqual(await readFile(join(root, "rolling/linux/manifest.json")), first);

  await publish(root, B, 2, A);
  const index = JSON.parse(await readFile(join(root, "rolling/generations/index.json"), "utf8"));
  assert.equal(index.latestGeneration, 2);
  assert.equal(index.commits[A].generation, 1);
  assert.equal(index.commits[B].generation, 2);
  assert.equal(JSON.parse(await readFile(join(root, "rolling/linux/manifest.json"), "utf8")).targetCommit, B);
});
