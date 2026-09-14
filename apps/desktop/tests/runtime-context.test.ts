import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
  createDesktopRuntimeContext,
  type DesktopRuntimePlatform,
} from "../src/runtime-context.js";
import {
  launchRequestFromAdditionalData,
  readLaunchAcknowledgement,
  writeLaunchAcknowledgement,
  type LaunchRequest,
} from "../src/launch-ack.js";

const temporaryRoots: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryRoots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

async function installedLayout(platform: DesktopRuntimePlatform = currentRuntimePlatform()) {
  const root = await mkdtemp(join(tmpdir(), "harmonia-runtime-context-"));
  temporaryRoots.push(root);
  const commit = "a".repeat(40);
  const version = join(root, "versions", commit);
  const resources = join(version, "desktop", "resources");
  const desktop = join(version, "desktop", platform === "win32" ? "HarmoniaSuite.exe" : "harmonia-suite");
  const backend = join(version, "backend", "harmonia-suite.jar");
  const java = join(root, "toolchain", "jdk", "bin", platform === "win32" ? "java.exe" : "java");
  await mkdir(resources, { recursive: true });
  await mkdir(join(version, "backend"), { recursive: true });
  await mkdir(join(java, ".."), { recursive: true });
  await mkdir(join(root, "bin"), { recursive: true });
  await writeFile(desktop, "electron");
  await writeFile(backend, "backend");
  await writeFile(java, "java");
  await writeFile(join(root, "bin", platform === "win32" ? "HarmoniaSetup.exe" : "harmonia-setup"), "setup");
  await writeFile(
    join(version, "metadata.json"),
    JSON.stringify({
      schema_version: 1,
      target_commit: commit,
      runtime: {
        desktop_executable: `desktop/${platform === "win32" ? "HarmoniaSuite.exe" : "harmonia-suite"}`,
        backend_jar: "backend/harmonia-suite.jar",
        managed_java_binary: `toolchain/jdk/bin/${platform === "win32" ? "java.exe" : "java"}`,
      },
    }),
  );
  return { root, commit, resources, desktop, backend, java };
}

describe("DesktopRuntimeContext", () => {
  it("resolves the installed layout from Electron resources without launcher variables", async () => {
    const layout = await installedLayout();
    const context = createDesktopRuntimeContext({
      resourcesPath: layout.resources,
      env: {
        APPDATA: join(layout.root, "roaming"),
        XDG_DATA_HOME: join(layout.root, "xdg-data"),
        XDG_STATE_HOME: join(layout.root, "xdg-state"),
        HARMONIA_RUNTIME_MODE: "installed",
        HARMONIA_ACTIVE_VERSION_DIR: join(layout.root, "wrong-version"),
        HARMONIA_BACKEND_JAR: join(layout.root, "wrong.jar"),
        HARMONIA_JAVA_BINARY: join(layout.root, "wrong-java"),
        HARMONIA_INSTALL_STATE_ROOT: join(layout.root, "wrong-state"),
      },
    });

    expect(context.mode).toBe("installed");
    expect(context.appRoot).toBe(layout.root);
    expect(context.activeVersionDir).toBe(join(layout.root, "versions", layout.commit));
    expect(context.backendJar).toBe(layout.backend);
    expect(context.javaBinary).toBe(layout.java);
    expect(context.installerBinary).toBe(
      join(layout.root, "bin", currentRuntimePlatform() === "win32" ? "HarmoniaSetup.exe" : "harmonia-setup"),
    );
    expect(context.stateRoot).toBe(currentRuntimePlatform() === "win32"
      ? join(layout.root, "state")
      : join(layout.root, "xdg-state", "harmonia-suite"));
    expect(context.userDataRoot).toBe(
      join(layout.root, currentRuntimePlatform() === "win32" ? "roaming" : "xdg-data", currentRuntimePlatform() === "win32" ? "HarmoniaSuite" : "harmonia-suite-data"),
    );
  });

  it("uses the Linux installer state root and accepts its launch acknowledgement", async () => {
    const layout = await installedLayout("linux");
    const dataHome = join(layout.root, "xdg-data");
    const stateHome = join(layout.root, "xdg-state");
    const context = createDesktopRuntimeContext({
      platform: "linux",
      resourcesPath: layout.resources,
      env: {
        XDG_DATA_HOME: dataHome,
        XDG_STATE_HOME: stateHome,
        APPDATA: join(layout.root, "wrong-roaming"),
        HARMONIA_INSTALL_STATE_ROOT: join(layout.root, "wrong-state"),
      },
    });

    expect(context.appRoot).toBe(layout.root);
    expect(context.userDataRoot).toBe(join(dataHome, "harmonia-suite-data"));
    expect(context.stateRoot).toBe(join(stateHome, "harmonia-suite"));
    expect(context.appRoot).not.toBe(context.stateRoot);

    const request: LaunchRequest = {
      ackPath: join(context.stateRoot, "bootstrap-acks", "operation-nonce.json"),
      stateRoot: context.stateRoot,
      operationId: "operation",
      targetCommit: layout.commit,
      nonce: "nonce",
    };
    expect(launchRequestFromAdditionalData({ harmoniaLaunch: request }, {}, context.stateRoot))
      .toEqual(request);

    await writeLaunchAcknowledgement(request);
    expect(await readLaunchAcknowledgement(request)).toMatchObject(request);
  });

  it("rejects an installed version whose directory is not a full commit", async () => {
    const layout = await installedLayout();
    const invalidResources = join(layout.root, "versions", "not-a-commit", "desktop", "resources");
    await mkdir(invalidResources, { recursive: true });
    await writeFile(join(layout.root, "versions", "not-a-commit", "metadata.json"), await readFile(join(layout.root, "versions", layout.commit, "metadata.json"), "utf8"));
    expect(() => createDesktopRuntimeContext({ resourcesPath: invalidResources })).toThrow(
      "version directory and metadata target_commit disagree",
    );
  });
});

function currentRuntimePlatform(): DesktopRuntimePlatform {
  return process.platform === "win32" ? "win32" : "linux";
}
