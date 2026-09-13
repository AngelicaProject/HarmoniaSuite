#!/usr/bin/env node

import {
  copyFile,
  mkdtemp,
  mkdir,
  readFile,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  assertVersions,
  compareSemVer,
  isStableSemVer,
  parseSemVer,
  readVersionContract,
} from "./check-versions.mjs";

const SCRIPT_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));

const VALIDATION_FILES = [
  "versions.json",
  "pom.xml",
  "frontend/package.json",
  "frontend/package-lock.json",
  "apps/desktop/package.json",
  "apps/desktop/package-lock.json",
  "apps/installer/Cargo.toml",
  "apps/installer/Cargo.lock",
];

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"));
}

function replaceProjectVersion(xml, version) {
  const project = /(<project\b[^>]*>)([\s\S]*?)(<\/project>)/.exec(xml);
  if (!project) throw new Error("pom.xml has no project element");
  const body = project[2];
  const parent = /<parent\b[\s\S]*?<\/parent>/.exec(body);
  const searchStart = parent ? parent.index + parent[0].length : 0;
  const match = /<version>\s*([^<\s]+)\s*<\/version>/.exec(body.slice(searchStart));
  if (!match) throw new Error("pom.xml has no root project.version");
  const offset = project.index + project[1].length + searchStart + match.index;
  const original = xml.slice(offset, offset + match[0].length);
  const replacement = original.replace(match[1], version);
  return `${xml.slice(0, offset)}${replacement}${xml.slice(offset + original.length)}`;
}

function replacePackageVersion(json, version) {
  if (typeof json.version !== "string") throw new Error("package.json has no version");
  const text = JSON.stringify(json, null, 2);
  return `${text.replace(/("version"\s*:\s*")[^"]+(")/, `$1${version}$2`)}\n`;
}

function replaceLockVersions(json, version) {
  let replaced = false;
  if (typeof json.version === "string") {
    json.version = version;
    replaced = true;
  }
  if (typeof json.packages?.[""]?.version === "string") {
    json.packages[""].version = version;
    replaced = true;
  }
  return `${JSON.stringify(json, null, 2)}\n`;
}

function replaceCargoPackageVersion(toml, version) {
  const packageStart = toml.indexOf("[package]");
  if (packageStart < 0) throw new Error("Cargo.toml has no [package] section");
  const nextSection = toml.indexOf("\n[", packageStart + 9);
  const packageEnd = nextSection < 0 ? toml.length : nextSection;
  const block = toml.slice(packageStart, packageEnd);
  if (!/name\s*=\s*"harmonia-installer"/.test(block)) throw new Error("Cargo.toml package is not harmonia-installer");
  if (!/version\s*=\s*"[^"]+"/.test(block)) throw new Error("Cargo.toml package has no version");
  const updated = block.replace(/(version\s*=\s*")[^"]+(")/, `$1${version}$2`);
  return `${toml.slice(0, packageStart)}${updated}${toml.slice(packageEnd)}`;
}

function replaceCargoLockVersion(lockfile, version) {
  const match = /(^\[\[package\]\]\s*\n(?:(?!\n\[\[package\]\]).)*?name\s*=\s*"harmonia-installer"(?:(?!\n\[\[package\]\]).)*?version\s*=\s*")[^"]+(")/ms.exec(lockfile);
  return match ? `${lockfile.slice(0, match.index)}${match[0].replace(match[0].slice(match[1].length, -1), version)}${lockfile.slice(match.index + match[0].length)}` : lockfile;
}

function relativePath(root, path) {
  const value = relative(root, path);
  if (!value || value.startsWith("..") || value.includes(":")) {
    throw new Error(`version transaction target is outside repository: ${path}`);
  }
  return value;
}

async function applyVersionTransaction(root, updates, { failAfterReplacement } = {}) {
  if (
    failAfterReplacement !== undefined
    && (!Number.isSafeInteger(failAfterReplacement) || failAfterReplacement < 0)
  ) {
    throw new Error("failAfterReplacement must be a non-negative safe integer");
  }

  const transactionRoot = await mkdtemp(resolve(root, ".versions-transaction-"));
  const stagedRoot = resolve(transactionRoot, "staged");
  const backupRoot = resolve(transactionRoot, "backups");
  const displacedRoot = resolve(transactionRoot, "displaced");
  try {
    const stagedContents = new Map();
    for (const validationPath of VALIDATION_FILES) {
      try {
        stagedContents.set(validationPath, await readFile(resolve(root, validationPath), "utf8"));
      } catch (error) {
        if (validationPath === "apps/installer/Cargo.lock" && error.code === "ENOENT") continue;
        throw error;
      }
    }
    for (const [path, contents] of updates) {
      stagedContents.set(relativePath(root, path), contents);
    }

    for (const [path, contents] of stagedContents) {
      const stagedPath = resolve(stagedRoot, path);
      await mkdir(dirname(stagedPath), { recursive: true });
      await writeFile(stagedPath, contents, "utf8");
    }
    await assertVersions(stagedRoot);

    for (const path of updates.keys()) {
      const relativeTarget = relativePath(root, path);
      const backupPath = resolve(backupRoot, relativeTarget);
      await mkdir(dirname(backupPath), { recursive: true });
      await copyFile(path, backupPath);
    }

    let replaced = 0;
    try {
      if (failAfterReplacement === 0) {
        throw new Error("injected version transaction failure before first replacement");
      }
      for (const path of updates.keys()) {
        const relativeTarget = relativePath(root, path);
        const displacedPath = resolve(displacedRoot, relativeTarget);
        await mkdir(dirname(displacedPath), { recursive: true });
        await rename(path, displacedPath);
        await rename(resolve(stagedRoot, relativeTarget), path);
        replaced += 1;
        if (failAfterReplacement === replaced) {
          throw new Error(`injected version transaction failure after replacement ${replaced}`);
        }
      }
    } catch (error) {
      try {
        for (const path of updates.keys()) {
          const relativeTarget = relativePath(root, path);
          await copyFile(resolve(backupRoot, relativeTarget), path);
        }
      } catch (rollbackError) {
        throw new Error(
          `version transaction failed and rollback failed: ${error.message}; ${rollbackError.message}`,
          { cause: rollbackError },
        );
      }
      throw error;
    }
  } finally {
    await rm(transactionRoot, { recursive: true, force: true });
  }
}

async function setProduct(root, version, transactionOptions) {
  parseSemVer(version, "productVersion");
  const contract = await readVersionContract(root);
  const next = { ...contract, productVersion: version };
  const pomPath = resolve(root, "pom.xml");
  const frontendPath = resolve(root, "frontend/package.json");
  const frontendLockPath = resolve(root, "frontend/package-lock.json");
  const desktopPath = resolve(root, "apps/desktop/package.json");
  const desktopLockPath = resolve(root, "apps/desktop/package-lock.json");
  const [pom, frontend, frontendLock, desktop, desktopLock] = await Promise.all([
    readFile(pomPath, "utf8"),
    readJson(frontendPath),
    readJson(frontendLockPath),
    readJson(desktopPath),
    readJson(desktopLockPath),
  ]);
  await applyVersionTransaction(
    root,
    new Map([
      [resolve(root, "versions.json"), `${JSON.stringify(next, null, 2)}\n`],
      [pomPath, replaceProjectVersion(pom, version)],
      [frontendPath, replacePackageVersion(frontend, version)],
      [frontendLockPath, replaceLockVersions(frontendLock, version)],
      [desktopPath, replacePackageVersion(desktop, version)],
      [desktopLockPath, replaceLockVersions(desktopLock, version)],
    ]),
    transactionOptions,
  );
}

async function setInstaller(root, version, transactionOptions) {
  if (!isStableSemVer(version)) throw new Error(`installerVersion must be stable SemVer: ${version}`);
  const contract = await readVersionContract(root);
  if (compareSemVer(contract.minimumInstallerVersion, version) > 0) {
    throw new Error(`minimumInstallerVersion ${contract.minimumInstallerVersion} is greater than installerVersion ${version}`);
  }
  const versionsPath = resolve(root, "versions.json");
  const cargoPath = resolve(root, "apps/installer/Cargo.toml");
  const lockPath = resolve(root, "apps/installer/Cargo.lock");
  const [cargo, lock] = await Promise.all([readFile(cargoPath, "utf8"), readFile(lockPath, "utf8").catch((error) => error.code === "ENOENT" ? null : Promise.reject(error))]);
  const updates = new Map([
    [versionsPath, `${JSON.stringify({ ...contract, installerVersion: version }, null, 2)}\n`],
    [cargoPath, replaceCargoPackageVersion(cargo, version)],
  ]);
  if (lock !== null) updates.set(lockPath, replaceCargoLockVersion(lock, version));
  await applyVersionTransaction(root, updates, transactionOptions);
}

async function setMinimumInstaller(root, version, transactionOptions) {
  if (!isStableSemVer(version)) throw new Error(`minimumInstallerVersion must be stable SemVer: ${version}`);
  const contract = await readVersionContract(root);
  if (compareSemVer(version, contract.installerVersion) > 0) {
    throw new Error(`minimumInstallerVersion ${version} is greater than installerVersion ${contract.installerVersion}`);
  }
  await applyVersionTransaction(
    root,
    new Map([
      [resolve(root, "versions.json"), `${JSON.stringify({ ...contract, minimumInstallerVersion: version }, null, 2)}\n`],
    ]),
    transactionOptions,
  );
}

export async function setVersion(root, kind, version, transactionOptions = {}) {
  await assertVersions(root);
  if (kind === "product") await setProduct(root, version, transactionOptions);
  else if (kind === "installer") await setInstaller(root, version, transactionOptions);
  else if (kind === "minimum-installer") await setMinimumInstaller(root, version, transactionOptions);
  else throw new Error(`unknown version domain: ${kind}`);
  return assertVersions(root);
}

function parseCli(argv) {
  const [kind, version, ...rest] = argv;
  let root = SCRIPT_ROOT;
  for (let index = 0; index < rest.length; index += 1) {
    if (rest[index] !== "--root") throw new Error(`unknown argument: ${rest[index]}`);
    root = resolve(rest[++index] ?? "");
  }
  if (!kind || !version || !["product", "installer", "minimum-installer"].includes(kind)) {
    throw new Error("usage: node tools/version/set-version.mjs <product|installer|minimum-installer> <version> [--root path]");
  }
  return { kind, version, root };
}

async function main(argv) {
  const { kind, version, root } = parseCli(argv);
  await setVersion(root, kind, version);
  process.stdout.write(`updated ${kind} version to ${version}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
