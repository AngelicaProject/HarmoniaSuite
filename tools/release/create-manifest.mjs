#!/usr/bin/env node

import { mkdir, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key?.startsWith("--") || !value) throw new Error("manifest arguments must be --name value pairs");
  args.set(key.slice(2), value);
}

const platform = args.get("platform");
const targetCommit = args.get("target-commit");
const productVersion = args.get("product-version");
const output = args.get("out");
const generation = Number(args.get("generation") ?? "0");
if (!new Set(["windows", "linux"]).has(platform)) throw new Error("platform must be windows or linux");
if (!/^[0-9a-f]{40}$/i.test(targetCommit ?? "")) throw new Error("target-commit must be a full SHA");
if (!productVersion?.trim() || !Number.isSafeInteger(generation) || generation < 1) {
  throw new Error("product-version and positive generation are required");
}

const common = {
  schema_version: 1,
  channel: "rolling-main",
  generation,
  product_version: productVersion,
  target_commit: targetCommit.toLowerCase(),
};
const descriptors = platform === "linux"
  ? {
      jdk: {
        kind: "jdk", version: "21.0.12+8", platform: "Linux", architecture: "X64",
        url: "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz",
        sha256: "e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370", archive: "tar.gz",
        home_dir: "jdk-21.0.12+8", executables: { java: "jdk-21.0.12+8/bin/java" },
      },
      node: {
        kind: "node", version: "24.15.0", platform: "Linux", architecture: "X64",
        url: "https://nodejs.org/download/release/v24.15.0/node-v24.15.0-linux-x64.tar.gz",
        sha256: "44836872d9aec49f1e6b52a9a922872db9a2b02d235a616a5681b6a85fec8d89", archive: "tar.gz",
        home_dir: "node-v24.15.0-linux-x64", executables: { node: "node-v24.15.0-linux-x64/bin/node", npm: "node-v24.15.0-linux-x64/bin/npm", npx: "node-v24.15.0-linux-x64/bin/npx" },
      },
    }
  : {
      jdk: {
        kind: "jdk", version: "21.0.12+8", platform: "Windows", architecture: "X64",
        url: "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip",
        sha256: "9ba963ee2371874a74185d18bc7bb2ab9407df7683300855ed7606e0662321d0", archive: "zip",
        home_dir: "jdk-21.0.12+8", executables: { java: "jdk-21.0.12+8/bin/java.exe" },
      },
      node: {
        kind: "node", version: "24.15.0", platform: "Windows", architecture: "X64",
        url: "https://nodejs.org/download/release/v24.15.0/node-v24.15.0-win-x64.zip",
        sha256: "cc5149eabd53779ce1e7bdc5401643622d0c7e6800ade18928a767e940bb0e62", archive: "zip",
        home_dir: "node-v24.15.0-win-x64", executables: { node: "node-v24.15.0-win-x64/node.exe", npm: "node-v24.15.0-win-x64/npm.cmd", npx: "node-v24.15.0-win-x64/npx.cmd" },
      },
    };

if (!output) throw new Error("--out is required");
await mkdir(dirname(output), { recursive: true });
await writeFile(output, `${JSON.stringify({ ...common, jdk: descriptors.jdk, node: descriptors.node }, null, 2)}\n`, { flag: "wx" });
