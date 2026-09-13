#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function decideRollingPublication(candidate, latest, isAncestor = gitIsAncestor) {
  if (!/^[0-9a-f]{40}$/.test(candidate ?? "")) throw new Error("candidate must be a full SHA");
  if (!latest || latest === candidate) return "publish";
  if (isAncestor(latest, candidate)) return "publish";
  if (isAncestor(candidate, latest)) return "noop";
  throw new Error(`unrelated rolling history: candidate=${candidate} latest=${latest}`);
}

function gitIsAncestor(ancestor, descendant) {
  try {
    execFileSync("git", ["merge-base", "--is-ancestor", ancestor, descendant], { stdio: "ignore" });
    return true;
  } catch (error) {
    if (error.status === 1) return false;
    throw error;
  }
}

function parseArguments(argumentsList) {
  const values = new Map();
  for (let index = 0; index < argumentsList.length; index += 2) {
    const key = argumentsList[index];
    const value = argumentsList[index + 1];
    if (!key?.startsWith("--") || value === undefined) throw new Error("arguments must be --name value pairs");
    values.set(key.slice(2), value);
  }
  return values;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const values = parseArguments(process.argv.slice(2));
  const candidate = values.get("candidate");
  const latest = values.get("latest");
  if (!candidate || values.size !== 2) throw new Error("--candidate and --latest are required");
  process.stdout.write(`${decideRollingPublication(candidate, latest)}\n`);
}
