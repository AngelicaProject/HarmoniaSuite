import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
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

test("rolling manifest keeps product version separate from identity fields", async () => {
  const repo = join(import.meta.dirname, "..", "..");
  const root = await mkdtemp(join(tmpdir(), "harmonia-manifest-version-"));
  const productVersion = "1.0.11-SNAPSHOT";
  const output = join(root, "manifest.json");
  await exec(process.execPath, [
    "tools/release/create-manifest.mjs",
    "--platform",
    "windows",
    "--target-commit",
    "fedcba9876543210fedcba9876543210fedcba98",
    "--product-version",
    productVersion,
    "--min-installer-version",
    "0.1.0",
    "--generation",
    "125",
    "--out",
    output,
  ], { cwd: repo });
  const manifest = JSON.parse(await readFile(output, "utf8"));
  assert.equal(manifest.productVersion, productVersion);
  assert.equal(manifest.targetCommit, "fedcba9876543210fedcba9876543210fedcba98");
  assert.equal(manifest.generation, 125);
});

test("rolling manifest keeps installer engine and compatibility floor independent", async () => {
  const root = await mkdtemp(join(tmpdir(), "harmonia-manifest-installer-floor-"));
  const output = join(root, "manifest.json");
  await exec(process.execPath, [
    "tools/release/create-manifest.mjs",
    "--platform",
    "linux",
    "--target-commit",
    "0123456789abcdef0123456789abcdef01234567",
    "--product-version",
    "1.0.11",
    "--min-installer-version",
    "0.1.0",
    "--generation",
    "2",
    "--out",
    output,
  ], { cwd: join(import.meta.dirname, "..", "..") });
  const manifest = JSON.parse(await readFile(output, "utf8"));
  assert.equal(manifest.minInstallerVersion, "0.1.0");
  assert.notEqual("0.2.0", manifest.minInstallerVersion);
});

test("manifest signing and verification default to the current key id", async () => {
  const repo = join(import.meta.dirname, "..", "..");
  const root = await mkdtemp(join(tmpdir(), "harmonia-manifest-signing-"));
  const manifestPath = join(root, "manifest.json");
  const signaturePath = join(root, "manifest.json.sig");
  const keyPath = join(root, "fixture-ed25519-key.pem");
  await writeFile(
    keyPath,
    "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIPTbj1QOnOfs/NEu9Bbd/aQxEBWtyXdzibqMIxJmyY7j\n-----END PRIVATE KEY-----\n",
  );
  await exec(process.execPath, [
    "tools/release/create-manifest.mjs",
    "--platform",
    "linux",
    "--target-commit",
    "0123456789abcdef0123456789abcdef01234567",
    "--product-version",
    "1.0.11-SNAPSHOT",
    "--generation",
    "1",
    "--out",
    manifestPath,
  ], { cwd: repo });

  const environment = {
    ...process.env,
    HARMONIA_MANIFEST_SIGNING_KEY_FILE: keyPath,
  };
  await exec(process.execPath, [
    "tools/release/sign-manifest.mjs",
    manifestPath,
    signaturePath,
  ], { cwd: repo, env: environment });
  const envelope = JSON.parse(await readFile(signaturePath, "utf8"));
  assert.equal(envelope.keyId, "primary-2026-09");

  await exec(process.execPath, [
    "tools/release/verify-manifest.mjs",
    manifestPath,
    signaturePath,
  ], {
    cwd: repo,
    env: {
      ...process.env,
      HARMONIA_MANIFEST_PUBLIC_KEY_HEX:
        "69b02488d9b687a23cb0918614519c97f1618f5d8c005437e82de9b997835192",
    },
  });
});
