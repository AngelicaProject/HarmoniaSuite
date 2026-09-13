#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const SEMVER_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;
const STABLE_SEMVER_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;

export class VersionContractError extends Error {
  constructor(message, mismatches = []) {
    super(message);
    this.name = "VersionContractError";
    this.mismatches = mismatches;
  }
}

export function parseSemVer(value, label = "version") {
  if (typeof value !== "string") throw new VersionContractError(`${label} must be a string`);
  const match = SEMVER_PATTERN.exec(value);
  if (!match) throw new VersionContractError(`${label} is not valid SemVer: ${value}`);
  const prerelease = match[4] ? match[4].split(".") : [];
  if (prerelease.some((part) => /^\d+$/.test(part) && part.length > 1 && part.startsWith("0"))) {
    throw new VersionContractError(`${label} is not valid SemVer: ${value}`);
  }
  return {
    value,
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    prerelease,
    build: match[5] ? match[5].split(".") : [],
  };
}

export function isStableSemVer(value) {
  return typeof value === "string" && STABLE_SEMVER_PATTERN.test(value);
}

export function compareSemVer(left, right) {
  const a = typeof left === "string" ? parseSemVer(left) : left;
  const b = typeof right === "string" ? parseSemVer(right) : right;
  for (const key of ["major", "minor", "patch"]) {
    if (a[key] !== b[key]) return a[key] < b[key] ? -1 : 1;
  }
  if (a.prerelease.length === 0 && b.prerelease.length === 0) return 0;
  if (a.prerelease.length === 0) return 1;
  if (b.prerelease.length === 0) return -1;
  for (let index = 0; index < Math.max(a.prerelease.length, b.prerelease.length); index += 1) {
    if (index >= a.prerelease.length) return -1;
    if (index >= b.prerelease.length) return 1;
    const leftPart = a.prerelease[index];
    const rightPart = b.prerelease[index];
    if (leftPart === rightPart) continue;
    const leftNumeric = /^\d+$/.test(leftPart);
    const rightNumeric = /^\d+$/.test(rightPart);
    if (leftNumeric && rightNumeric) return Number(leftPart) < Number(rightPart) ? -1 : 1;
    if (leftNumeric !== rightNumeric) return leftNumeric ? -1 : 1;
    return leftPart < rightPart ? -1 : 1;
  }
  return 0;
}

async function readJson(path, label) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    throw new VersionContractError(`cannot read ${label}: ${error.message}`);
  }
}

export async function readVersionContract(root = SCRIPT_ROOT) {
  const contract = await readJson(resolve(root, "versions.json"), "versions.json");
  if (!contract || contract.schemaVersion !== 1) {
    throw new VersionContractError("versions.json schemaVersion must be 1");
  }
  parseSemVer(contract.productVersion, "productVersion");
  if (!isStableSemVer(contract.installerVersion)) {
    throw new VersionContractError(`installerVersion must be stable SemVer: ${contract.installerVersion}`);
  }
  if (!isStableSemVer(contract.minimumInstallerVersion)) {
    throw new VersionContractError(
      `minimumInstallerVersion must be stable SemVer: ${contract.minimumInstallerVersion}`,
    );
  }
  if (compareSemVer(contract.minimumInstallerVersion, contract.installerVersion) > 0) {
    throw new VersionContractError(
      `minimumInstallerVersion ${contract.minimumInstallerVersion} is greater than installerVersion ${contract.installerVersion}`,
    );
  }
  return contract;
}

function projectVersionFromPom(xml) {
  const project = /<project\b[^>]*>([\s\S]*?)<\/project>/.exec(xml)?.[1];
  if (!project) throw new VersionContractError("pom.xml has no project element");
  const withoutParent = project.replace(/<parent\b[\s\S]*?<\/parent>/, "");
  return /<version>\s*([^<\s]+)\s*<\/version>/.exec(withoutParent)?.[1];
}

async function packageVersion(root, relativePath) {
  const value = await readJson(resolve(root, relativePath), relativePath);
  if (typeof value.version !== "string") {
    throw new VersionContractError(`${relativePath} has no package version`);
  }
  return value.version;
}

async function packageLockVersions(root, relativePath) {
  const value = await readJson(resolve(root, relativePath), relativePath);
  const versions = [];
  if (typeof value.version === "string") versions.push([`${relativePath} version`, value.version]);
  if (typeof value.packages?.[""]?.version === "string") {
    versions.push([`${relativePath} root package version`, value.packages[""].version]);
  }
  return versions;
}

function cargoPackageVersion(toml) {
  const packageBlock = /\[package\]([\s\S]*?)(?=\n\[|$)/.exec(toml)?.[1];
  if (!packageBlock) throw new VersionContractError("Cargo.toml has no [package] section");
  const name = /(?:^|\n)\s*name\s*=\s*"([^"]+)"/.exec(packageBlock)?.[1];
  const version = /(?:^|\n)\s*version\s*=\s*"([^"]+)"/.exec(packageBlock)?.[1];
  if (name !== "harmonia-installer" || !version) {
    throw new VersionContractError("Cargo.toml [package] is not harmonia-installer or has no version");
  }
  return version;
}

function cargoLockPackageVersion(lockfile) {
  const block = /\[\[package\]\]\s*\n(?:(?!\n\[\[package\]\]).)*?name\s*=\s*"harmonia-installer"(?:(?!\n\[\[package\]\]).)*?version\s*=\s*"([^"]+)"/s.exec(lockfile);
  return block?.[1];
}

export async function checkVersions(root = SCRIPT_ROOT) {
  const contract = await readVersionContract(root);
  const mismatches = [];
  const compare = (label, actual, expected) => {
    if (actual !== expected) mismatches.push(`${label}: expected ${expected}, found ${actual ?? "<missing>"}`);
  };

  const pom = await readFile(resolve(root, "pom.xml"), "utf8");
  compare("pom.xml project.version", projectVersionFromPom(pom), contract.productVersion);
  compare("frontend/package.json version", await packageVersion(root, "frontend/package.json"), contract.productVersion);
  compare("apps/desktop/package.json version", await packageVersion(root, "apps/desktop/package.json"), contract.productVersion);
  for (const [label, version] of [
    ...(await packageLockVersions(root, "frontend/package-lock.json")),
    ...(await packageLockVersions(root, "apps/desktop/package-lock.json")),
  ]) {
    compare(label, version, contract.productVersion);
  }

  const cargo = await readFile(resolve(root, "apps/installer/Cargo.toml"), "utf8");
  const cargoVersion = cargoPackageVersion(cargo);
  compare("apps/installer/Cargo.toml package.version", cargoVersion, contract.installerVersion);
  try {
    const lock = await readFile(resolve(root, "apps/installer/Cargo.lock"), "utf8");
    const lockVersion = cargoLockPackageVersion(lock);
    if (lockVersion !== undefined) compare("apps/installer/Cargo.lock package.version", lockVersion, contract.installerVersion);
  } catch (error) {
    if (error.code !== "ENOENT") throw new VersionContractError(`cannot read apps/installer/Cargo.lock: ${error.message}`);
  }
  return { contract, mismatches };
}

export async function assertVersions(root = SCRIPT_ROOT) {
  const result = await checkVersions(root);
  if (result.mismatches.length > 0) {
    throw new VersionContractError(`version contract mismatch:\n${result.mismatches.map((value) => `- ${value}`).join("\n")}`, result.mismatches);
  }
  return result.contract;
}

function parseCli(argv) {
  const options = { root: SCRIPT_ROOT, json: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--json") options.json = true;
    else if (arg === "--root") options.root = resolve(argv[++index] ?? "");
    else throw new VersionContractError(`unknown argument: ${arg}`);
  }
  return options;
}

async function main(argv) {
  const options = parseCli(argv);
  const contract = await assertVersions(options.root);
  if (options.json) process.stdout.write(`${JSON.stringify(contract)}\n`);
  else process.stdout.write(`version contract is consistent: product ${contract.productVersion}, installer ${contract.installerVersion}, minimum installer ${contract.minimumInstallerVersion}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
