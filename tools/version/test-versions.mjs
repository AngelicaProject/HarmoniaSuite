import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import test from "node:test";

import {
  assertVersions,
  checkVersions,
  compareSemVer,
} from "./check-versions.mjs";
import { setVersion } from "./set-version.mjs";
import {
  validateInstallerReleaseBump,
  validateProductReleaseProgression,
  validateReleaseTag,
} from "../release/check-release.mjs";

const exec = promisify(execFile);
const repo = join(import.meta.dirname, "..", "..");

async function fixture({ product = "1.0.12-SNAPSHOT", installer = "0.1.0", minimum = "0.1.0" } = {}) {
  const root = await mkdtemp(join(tmpdir(), "harmonia-versions-"));
  await writeFile(join(root, "versions.json"), `${JSON.stringify({
    schemaVersion: 1,
    productVersion: product,
    installerVersion: installer,
    minimumInstallerVersion: minimum,
  })}\n`);
  await writeFile(join(root, "pom.xml"), `<project><parent><version>9.9.9</version></parent><version>${product}</version></project>\n`);
  const packageJson = (name) => JSON.stringify({ name, version: product, private: true });
  const packageLock = (name) => JSON.stringify({ name, version: product, lockfileVersion: 3, packages: { "": { name, version: product } } });
  await mkdir(join(root, "frontend"), { recursive: true });
  await mkdir(join(root, "apps/desktop"), { recursive: true });
  await mkdir(join(root, "apps/installer"), { recursive: true });
  await writeFile(join(root, "frontend/package.json"), packageJson("frontend"));
  await writeFile(join(root, "frontend/package-lock.json"), packageLock("frontend"));
  await writeFile(join(root, "apps/desktop/package.json"), packageJson("desktop"));
  await writeFile(join(root, "apps/desktop/package-lock.json"), packageLock("desktop"));
  await writeFile(join(root, "apps/installer/Cargo.toml"), `[package]\nname = "harmonia-installer"\nversion = "${installer}"\n`);
  await writeFile(join(root, "apps/installer/Cargo.lock"), `[[package]]\nname = "harmonia-installer"\nversion = "${installer}"\n`);
  return root;
}

test("valid development contract accepts independent installer version", async () => {
  const root = await fixture({ installer: "0.2.0", minimum: "0.1.0" });
  const contract = await assertVersions(root);
  assert.equal(contract.productVersion, "1.0.12-SNAPSHOT");
  assert.equal(compareSemVer("0.1.0", "0.2.0") < 0, true);
});

test("component drift is reported", async () => {
  const root = await fixture();
  const packagePath = join(root, "apps/desktop/package.json");
  await writeFile(packagePath, JSON.stringify({ name: "desktop", version: "0.1.0", private: true }));
  const result = await checkVersions(root);
  assert.ok(result.mismatches.some((value) => value.includes("apps/desktop/package.json version")));
});

test("all canonical component mismatches fail the contract", async () => {
  for (const relative of [
    "pom.xml",
    "frontend/package.json",
    "apps/desktop/package.json",
    "apps/installer/Cargo.toml",
  ]) {
    const root = await fixture();
    const path = join(root, relative);
    const original = await readFile(path, "utf8");
    const mismatched = relative.includes("installer")
      ? original.replace('version = "0.1.0"', 'version = "0.1.1"')
      : original.replaceAll("1.0.12-SNAPSHOT", "1.0.10");
    await writeFile(path, mismatched);
    await assert.rejects(() => assertVersions(root), /version contract mismatch/);
  }
});

test("minimum installer cannot exceed current installer", async () => {
  const root = await fixture({ installer: "0.1.0", minimum: "0.2.0" });
  await assert.rejects(() => assertVersions(root), /greater than installerVersion/);
});

test("stable release tag must equal stable product version", () => {
  assert.equal(validateReleaseTag("v1.0.11", {
    productVersion: "1.0.11",
  }), "1.0.11");
  assert.throws(() => validateReleaseTag("v1.0.11", { productVersion: "1.0.12-SNAPSHOT" }), /prerelease/);
  assert.throws(() => validateReleaseTag("v0.1.0", { productVersion: "1.0.11" }), /does not match/);
});

test("changed installer source requires a higher engine version", () => {
  assert.throws(() => validateInstallerReleaseBump("0.1.0", "0.1.0", true), /not greater/);
  assert.doesNotThrow(() => validateInstallerReleaseBump("0.2.0", "0.1.0", true));
  assert.doesNotThrow(() => validateInstallerReleaseBump("0.1.0", "0.1.0", false));
});

test("product releases must progress beyond the previous product release", () => {
  for (const current of ["1.0.12", "1.1.0", "2.0.0"]) {
    assert.doesNotThrow(() => validateProductReleaseProgression(current, "1.0.11"));
  }
  for (const current of ["1.0.11", "1.0.10"]) {
    assert.throws(
      () => validateProductReleaseProgression(current, "1.0.11"),
      /not greater/,
    );
  }
});

test("version setters update only their contract domain", async () => {
  const root = await fixture();
  const script = join(repo, "tools/version/set-version.mjs");
  await exec(process.execPath, [script, "product", "1.0.13-SNAPSHOT", "--root", root], { cwd: repo });
  await exec(process.execPath, [script, "installer", "0.2.0", "--root", root], { cwd: repo });
  await exec(process.execPath, [script, "minimum-installer", "0.1.0", "--root", root], { cwd: repo });
  const contract = await assertVersions(root);
  assert.deepEqual(contract, {
    schemaVersion: 1,
    productVersion: "1.0.13-SNAPSHOT",
    installerVersion: "0.2.0",
    minimumInstallerVersion: "0.1.0",
  });
});

test("lockfile package metadata is optional when absent", async () => {
  const root = await fixture();
  await writeFile(join(root, "frontend/package-lock.json"), '{"lockfileVersion":3}\n');
  await writeFile(join(root, "apps/desktop/package-lock.json"), '{"lockfileVersion":3}\n');
  await exec(process.execPath, [
    join(repo, "tools/version/set-version.mjs"),
    "product",
    "1.0.13-SNAPSHOT",
    "--root",
    root,
  ], { cwd: repo });
  assert.equal((await assertVersions(root)).productVersion, "1.0.13-SNAPSHOT");
});

test("version transactions roll back every domain after injected failures", async () => {
  const files = [
    "versions.json",
    "pom.xml",
    "frontend/package.json",
    "frontend/package-lock.json",
    "apps/desktop/package.json",
    "apps/desktop/package-lock.json",
    "apps/installer/Cargo.toml",
    "apps/installer/Cargo.lock",
  ];
  const cases = [
    { kind: "product", version: "1.0.13-SNAPSHOT", failAfterReplacement: 0 },
    { kind: "product", version: "1.0.13-SNAPSHOT", failAfterReplacement: 3 },
    { kind: "installer", version: "0.2.0", failAfterReplacement: 1 },
    { kind: "minimum-installer", version: "0.1.0", failAfterReplacement: 0 },
    { kind: "minimum-installer", version: "0.1.0", failAfterReplacement: 1 },
  ];
  for (const transactionCase of cases) {
    const root = await fixture();
    const before = new Map(
      await Promise.all(files.map(async (file) => [file, await readFile(join(root, file), "utf8")])),
    );
    await assert.rejects(
      () => setVersion(root, transactionCase.kind, transactionCase.version, transactionCase),
      /injected version transaction failure/,
    );
    for (const file of files) assert.equal(await readFile(join(root, file), "utf8"), before.get(file));
    await setVersion(root, transactionCase.kind, transactionCase.version);
    await assertVersions(root);
  }
});

test("release metadata records product, engine, and exact commit", async () => {
  const root = await fixture({ installer: "0.2.0" });
  const output = join(root, "release-metadata.json");
  await exec(process.execPath, [
    join(repo, "tools/release/create-release-metadata.mjs"),
    "--root",
    root,
    "--commit",
    "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
    "--out",
    output,
  ], { cwd: repo });
  assert.deepEqual(JSON.parse(await readFile(output, "utf8")), {
    schemaVersion: 1,
    productVersion: "1.0.12-SNAPSHOT",
    installerVersion: "0.2.0",
    commit: "abcdef0123456789abcdef0123456789abcdef01",
  });
});
