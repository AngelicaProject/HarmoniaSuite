import assert from "node:assert/strict";
import { mkdtemp, readFile, unlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import test from "node:test";

const exec = promisify(execFile);
const script = join(import.meta.dirname, "publish-rolling.mjs");
const workflow = join(import.meta.dirname, "../../.github/workflows/rolling.yml");
const A = "a".repeat(40);
const B = "b".repeat(40);
const C = "c".repeat(40);

async function allocate(root, sha, expectedLatest = "") {
  const { stdout } = await exec(process.execPath, [
    script,
    "allocate",
    "--root",
    root,
    "--sha",
    sha,
    "--expected-latest-sha",
    expectedLatest,
  ]);
  return stdout.trim();
}

async function publish(root, sha, generation, expectedLatest = "", fields = {}) {
  const manifestPaths = {};
  const signaturePaths = {};
  for (const platform of ["linux", "windows"]) {
    const manifestPath = join(root, `${platform}-${generation}.json`);
    const signaturePath = join(root, `${platform}-${generation}.json.sig`);
    await writeFile(
      manifestPath,
      `${JSON.stringify({ schemaVersion: 1, channel: "rolling", ...fields, generation, targetCommit: sha })}\n`,
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

test("empty ledger allocates generation one for the first SHA", async () => {
  const root = await mkdtemp(join(tmpdir(), "harmonia-rolling-"));
  assert.equal(await allocate(root, A), "1");
  await publish(root, A, 1);

  const index = JSON.parse(await readFile(join(root, "rolling/generations/index.json"), "utf8"));
  assert.equal(index.latestGeneration, 1);
  assert.equal(index.commits[A].generation, 1);
});

test("a new SHA advances the durable generation after generation one", async () => {
  const root = await mkdtemp(join(tmpdir(), "harmonia-rolling-"));
  await publish(root, A, 1);
  assert.equal(await allocate(root, B, A), "2");
  await publish(root, B, 2, A);

  const index = JSON.parse(await readFile(join(root, "rolling/generations/index.json"), "utf8"));
  assert.equal(index.latestGeneration, 2);
  assert.equal(index.commits[A].generation, 1);
  assert.equal(index.commits[B].generation, 2);
});

test("same-SHA reruns reuse the existing generation and preserve immutable files", async () => {
  const root = await mkdtemp(join(tmpdir(), "harmonia-rolling-"));
  await publish(root, A, 1);
  const first = await readFile(join(root, "rolling/linux/manifest.json"));
  await publish(root, A, 1, A);
  assert.deepEqual(await readFile(join(root, "rolling/linux/manifest.json")), first);
  assert.equal(await allocate(root, A, A), "1");
});

test("missing index with existing immutable history fails closed", async () => {
  const root = await mkdtemp(join(tmpdir(), "harmonia-rolling-"));
  await publish(root, A, 1);
  await unlink(join(root, "rolling/generations/index.json"));

  await assert.rejects(
    () => exec(process.execPath, [script, "latest", "--root", root]),
    /rolling ledger is inconsistent \/ index missing/,
  );
});

test("product version and release tag changes do not reset the lifetime generation sequence", async () => {
  const root = await mkdtemp(join(tmpdir(), "harmonia-rolling-"));
  await publish(root, A, 1, "", { productVersion: "1.0.0", releaseTag: "v1.0.0" });
  await publish(root, B, 2, A, { productVersion: "1.1.0", releaseTag: "v1.1.0" });
  await publish(root, C, 3, B, { productVersion: "2.0.0", releaseTag: "v2.0.0" });

  const index = JSON.parse(await readFile(join(root, "rolling/generations/index.json"), "utf8"));
  assert.equal(index.latestGeneration, 3);
  assert.equal(index.commits[C].generation, 3);
  assert.equal(JSON.parse(await readFile(join(root, "rolling/linux/manifest.json"), "utf8")).productVersion, "2.0.0");
});

test("Pages workflow deploys the exact committed ledger without its git directory", async () => {
  const source = await readFile(workflow, "utf8");
  const publishStart = source.indexOf("  publish-ledger:");
  const deployStart = source.indexOf("  deploy-pages:");
  assert.ok(publishStart >= 0 && deployStart > publishStart);
  const publishJob = source.slice(publishStart, deployStart);
  const deployJob = source.slice(deployStart);

  assert.match(publishJob, /permissions:\s+contents: write/);
  assert.match(publishJob, /ledger_sha=/);
  assert.match(deployJob, /needs: publish-ledger/);
  assert.match(deployJob, /if: needs\.publish-ledger\.result == 'success'/);
  assert.doesNotMatch(deployJob, /steps\.policy\.outputs\.action/);
  assert.match(deployJob, /pages:\s+write/);
  assert.match(deployJob, /id-token:\s+write/);
  assert.match(deployJob, /actions\/configure-pages@v5/);
  assert.match(deployJob, /actions\/upload-pages-artifact@v3/);
  assert.match(deployJob, /path: pages-root/);
  assert.match(deployJob, /cp -R rolling-site\/rolling pages-root\/rolling/);
  assert.match(deployJob, /test -f pages-root\/rolling\/generations\/index\.json/);
  assert.match(deployJob, /missing immutable generation history/);
  assert.match(deployJob, /for platform in windows linux/);
  assert.match(deployJob, /pages-root\/rolling\/\$platform\/manifest\.json/);
  assert.match(deployJob, /pages-root\/rolling\/\$platform\/manifest\.json\.sig/);
  assert.match(deployJob, /test ! -e pages-root\/rolling\/\.git/);
  assert.match(deployJob, /actions\/deploy-pages@v4/);
  assert.match(deployJob, /environment:\s+name: github-pages\s+url: \$\{\{ steps\.deployment\.outputs\.page_url \}\}/s);
});
