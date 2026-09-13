#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  assertVersions,
  compareSemVer,
  isStableSemVer,
} from "../version/check-versions.mjs";

const SCRIPT_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const STABLE_TAG = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;

export function validateReleaseTag(tag, contract) {
  const match = STABLE_TAG.exec(tag ?? "");
  if (!match) throw new Error(`release tag must be vX.Y.Z: ${tag ?? "<missing>"}`);
  const productVersion = tag.slice(1);
  if (!isStableSemVer(contract.productVersion)) {
    throw new Error(`stable release cannot use a prerelease productVersion: ${contract.productVersion}`);
  }
  if (productVersion !== contract.productVersion) {
    throw new Error(`release tag ${tag} does not match productVersion ${contract.productVersion}`);
  }
  return productVersion;
}

function git(root, args) {
  return execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
}

function previousProductTag(root, currentTag) {
  const tags = git(root, ["tag", "--merged", "HEAD", "--list", "v*", "--sort=-v:refname"])
    .split("\n")
    .map((value) => value.trim())
    .filter((value) => value && value !== currentTag && STABLE_TAG.test(value));
  for (const tag of tags) {
    try {
      const contract = JSON.parse(git(root, ["show", `${tag}:versions.json`]));
      if (
        contract.schemaVersion === 1
        && isStableSemVer(contract.productVersion)
        && contract.productVersion === tag.slice(1)
        && isStableSemVer(contract.installerVersion)
      ) {
        return { tag, contract };
      }
    } catch {
      // Pre-contract distribution tags are not product releases and cannot establish an engine baseline.
    }
  }
  return null;
}

export function installerChangedSince(root, previousTag) {
  try {
    execFileSync("git", ["diff", "--quiet", `${previousTag}..HEAD`, "--", "apps/installer"], {
      cwd: root,
      stdio: "ignore",
    });
    return false;
  } catch (error) {
    if (error.status === 1) return true;
    throw error;
  }
}

export function validateInstallerReleaseBump(current, previous, changed) {
  if (changed && compareSemVer(current, previous) <= 0) {
    throw new Error(`installer source changed since previous product release, but installerVersion ${current} is not greater than ${previous}`);
  }
}

export function validateProductReleaseProgression(current, previous) {
  if (compareSemVer(current, previous) <= 0) {
    throw new Error(`productVersion ${current} is not greater than previous product release ${previous}`);
  }
}

export async function validateRelease(root = SCRIPT_ROOT, tag, { checkPrevious = true } = {}) {
  const contract = await assertVersions(root);
  validateReleaseTag(tag, contract);
  const head = git(root, ["rev-parse", "HEAD"]);
  const tagged = git(root, ["rev-parse", `${tag}^{commit}`]);
  if (head !== tagged) throw new Error(`checked-out commit ${head} is not the release tag commit ${tagged}`);

  if (checkPrevious) {
    const previous = previousProductTag(root, tag);
    if (previous) {
      validateProductReleaseProgression(contract.productVersion, previous.contract.productVersion);
      validateInstallerReleaseBump(
        contract.installerVersion,
        previous.contract.installerVersion,
        installerChangedSince(root, previous.tag),
      );
    }
  }
  return contract;
}

function parseCli(argv) {
  let root = SCRIPT_ROOT;
  let tag;
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--root") root = resolve(argv[++index] ?? "");
    else if (!tag) tag = arg;
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!tag) throw new Error("usage: node tools/release/check-release.mjs vX.Y.Z [--root path]");
  return { root, tag };
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  const { root, tag } = parseCli(process.argv.slice(2));
  validateRelease(root, tag).then((contract) => {
    process.stdout.write(`release contract is valid: HarmoniaSuite ${contract.productVersion}, installer ${contract.installerVersion}\n`);
  }).catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
