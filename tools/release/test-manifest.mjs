import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import test from "node:test";

const exec = promisify(execFile);

test("production manifest generator uses the signed rolling wire contract", async () => {
  const root = await mkdtemp(join(tmpdir(), "harmonia-manifest-"));
  const output = join(root, "manifest.json");
  await exec(process.execPath, [
    "tools/release/create-manifest.mjs",
    "--platform",
    "linux",
    "--target-commit",
    "0123456789abcdef0123456789abcdef01234567",
    "--product-version",
    "1.0.11-SNAPSHOT",
    "--min-installer-version",
    "0.1.0",
    "--generation",
    "1",
    "--out",
    output,
  ], { cwd: join(import.meta.dirname, "..", "..") });
  const manifest = JSON.parse(await readFile(output, "utf8"));
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.channel, "rolling");
  assert.equal(manifest.targetCommit, "0123456789abcdef0123456789abcdef01234567");
  assert.equal(manifest.minInstallerVersion, "0.1.0");
  assert.equal(manifest.jdk.homeDir, "jdk-21.0.12+8");
  assert.equal(manifest.node.homeDir, "node-v24.15.0-linux-x64");
  assert.equal(manifest.schema_version, undefined);
  assert.equal(manifest.target_commit, undefined);
});

test("rolling manifest keeps Maven application version separate from identity fields", async () => {
  const repo = join(import.meta.dirname, "..", "..");
  const root = await mkdtemp(join(tmpdir(), "harmonia-manifest-version-"));
  const fixture = await readFile(
    join(repo, "tools/release/fixtures/rolling-version/pom.xml"),
    "utf8",
  );
  const applicationVersion = fixture.match(/<version>([^<]+)<\/version>/)?.[1];
  assert.equal(applicationVersion, "1.0.11-SNAPSHOT");
  const output = join(root, "manifest.json");
  await exec(process.execPath, [
    "tools/release/create-manifest.mjs",
    "--platform",
    "windows",
    "--target-commit",
    "fedcba9876543210fedcba9876543210fedcba98",
    "--product-version",
    applicationVersion,
    "--min-installer-version",
    "0.1.0",
    "--generation",
    "125",
    "--out",
    output,
  ], { cwd: repo });
  const manifest = JSON.parse(await readFile(output, "utf8"));
  assert.equal(manifest.productVersion, "1.0.11-SNAPSHOT");
  assert.equal(manifest.targetCommit, "fedcba9876543210fedcba9876543210fedcba98");
  assert.equal(manifest.generation, 125);
});
