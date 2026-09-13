#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { readFile, rename, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  assertVersions,
  compareSemVer,
  isStableSemVer,
  parseSemVer,
  readVersionContract,
} from "./check-versions.mjs";

const SCRIPT_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));

async function writeAtomic(path, contents) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  await writeFile(temporary, contents, "utf8");
  await rename(temporary, path);
}

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

async function setProduct(root, version) {
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
  await Promise.all([
    writeAtomic(resolve(root, "versions.json"), `${JSON.stringify(next, null, 2)}\n`),
    writeAtomic(pomPath, replaceProjectVersion(pom, version)),
    writeAtomic(frontendPath, replacePackageVersion(frontend, version)),
    writeAtomic(frontendLockPath, replaceLockVersions(frontendLock, version)),
    writeAtomic(desktopPath, replacePackageVersion(desktop, version)),
    writeAtomic(desktopLockPath, replaceLockVersions(desktopLock, version)),
  ]);
}

async function setInstaller(root, version) {
  if (!isStableSemVer(version)) throw new Error(`installerVersion must be stable SemVer: ${version}`);
  const contract = await readVersionContract(root);
  if (compareSemVer(contract.minimumInstallerVersion, version) > 0) {
    throw new Error(`minimumInstallerVersion ${contract.minimumInstallerVersion} is greater than installerVersion ${version}`);
  }
  const versionsPath = resolve(root, "versions.json");
  const cargoPath = resolve(root, "apps/installer/Cargo.toml");
  const lockPath = resolve(root, "apps/installer/Cargo.lock");
  const [cargo, lock] = await Promise.all([readFile(cargoPath, "utf8"), readFile(lockPath, "utf8").catch((error) => error.code === "ENOENT" ? null : Promise.reject(error))]);
  await Promise.all([
    writeAtomic(versionsPath, `${JSON.stringify({ ...contract, installerVersion: version }, null, 2)}\n`),
    writeAtomic(cargoPath, replaceCargoPackageVersion(cargo, version)),
    ...(lock === null ? [] : [writeAtomic(lockPath, replaceCargoLockVersion(lock, version))]),
  ]);
}

async function setMinimumInstaller(root, version) {
  if (!isStableSemVer(version)) throw new Error(`minimumInstallerVersion must be stable SemVer: ${version}`);
  const contract = await readVersionContract(root);
  if (compareSemVer(version, contract.installerVersion) > 0) {
    throw new Error(`minimumInstallerVersion ${version} is greater than installerVersion ${contract.installerVersion}`);
  }
  await writeAtomic(resolve(root, "versions.json"), `${JSON.stringify({ ...contract, minimumInstallerVersion: version }, null, 2)}\n`);
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
  await assertVersions(root);
  if (kind === "product") await setProduct(root, version);
  else if (kind === "installer") await setInstaller(root, version);
  else await setMinimumInstaller(root, version);
  await assertVersions(root);
  process.stdout.write(`updated ${kind} version to ${version}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
