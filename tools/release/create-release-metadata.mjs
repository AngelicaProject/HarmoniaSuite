#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { assertVersions } from "../version/check-versions.mjs";

const SCRIPT_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));

function git(root) {
  return execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
}

function parseCli(argv) {
  let root = SCRIPT_ROOT;
  let output;
  let commit;
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--root") root = resolve(argv[++index] ?? "");
    else if (arg === "--out") output = resolve(argv[++index] ?? "");
    else if (arg === "--commit") commit = argv[++index];
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (!output) throw new Error("--out is required");
  return { root, output, commit };
}

const { root, output, commit: requestedCommit } = parseCli(process.argv.slice(2));
const contract = await assertVersions(root);
const commit = requestedCommit ?? git(root);
if (!/^[0-9a-f]{40}$/i.test(commit)) throw new Error("commit must be a full SHA");
await mkdir(dirname(output), { recursive: true });
await writeFile(output, `${JSON.stringify({
  schemaVersion: 1,
  productVersion: contract.productVersion,
  installerVersion: contract.installerVersion,
  commit: commit.toLowerCase(),
}, null, 2)}\n`, { flag: "wx" });
