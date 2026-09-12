import { spawn } from "node:child_process";
import { existsSync, rmSync, readFileSync } from "node:fs";
import { isAbsolute, join } from "node:path";

import { app, ipcMain } from "electron";

export type UpdateStatus =
  | { state: "idle" }
  | { state: "checking" }
  | { state: "up-to-date"; result: unknown }
  | { state: "available"; result: unknown }
  | { state: "installing" }
  | { state: "error"; error: string };

let status: UpdateStatus = { state: "idle" };

export function registerUpdaterIpc(): void {
  ipcMain.handle("harmonia:update:getUpdateStatus", () => status);
  ipcMain.handle("harmonia:update:checkForUpdates", async () => {
    status = { state: "checking" };
    try {
      const result = await runInstaller(["check", "--json"]);
      status = { state: updateResultState(result), result };
      return result;
    } catch (error) {
      status = { state: "error", error: error instanceof Error ? error.message : String(error) };
      throw error;
    }
  });
  ipcMain.handle("harmonia:update:installUpdate", async () => {
    try {
      const installer = installerBinary();
      const acceptancePath = updateAcceptancePath();
      rmSync(acceptancePath, { force: true });
      // The updater is an independent process. It owns the build/activation lock and the desktop
      // exits only after the helper has acknowledged startup.
      const child = spawn(installer, ["update"], {
        detached: true,
        shell: false,
        windowsHide: true,
        stdio: "ignore",
        env: updaterEnvironment({
          HARMONIA_UPDATE_ACCEPT_PATH: acceptancePath,
          HARMONIA_UPDATE_FROM_DESKTOP: "1",
        }),
      });
      await waitForUpdaterAcceptance(child, acceptancePath);
      child.unref();
      status = { state: "installing" };
      app.quit();
      return { accepted: true };
    } catch (error) {
      status = { state: "error", error: error instanceof Error ? error.message : String(error) };
      throw error;
    }
  });
}

function installerBinary(): string {
  const value = process.env.HARMONIA_INSTALLER_BINARY;
  if (!value || !isAbsolute(value)) {
    throw new Error("installed runtime requires an absolute HARMONIA_INSTALLER_BINARY");
  }
  return value;
}

function updaterEnvironment(overrides: Record<string, string> = {}): NodeJS.ProcessEnv {
  const environment = updaterEnvironmentBase();
  return { ...environment, ...overrides };
}

function updaterEnvironmentBase(): NodeJS.ProcessEnv {
  const allowed = [
    "SystemRoot",
    "SystemDrive",
    "TEMP",
    "TMP",
    "USERPROFILE",
    "APPDATA",
    "LOCALAPPDATA",
    "HOME",
    "USER",
    "LANG",
    "LC_ALL",
    "XDG_RUNTIME_DIR",
  ];
  const environment: NodeJS.ProcessEnv = {};
  for (const name of allowed) {
    if (process.env[name]) environment[name] = process.env[name];
  }
  for (const name of [
    "HARMONIA_RUNTIME_MODE",
    "HARMONIA_USER_DATA_ROOT",
    "HARMONIA_WORKSPACE",
    "HARMONIA_INSTALL_STATE_ROOT",
    "HARMONIA_INSTALLER_BINARY",
    "HARMONIA_UPDATE_FROM_DESKTOP",
  ]) {
    if (process.env[name]) environment[name] = process.env[name];
  }
  return environment;
}

function updateAcceptancePath(): string {
  const stateRoot = process.env.HARMONIA_INSTALL_STATE_ROOT;
  if (!stateRoot || !isAbsolute(stateRoot)) {
    throw new Error("installed runtime requires an absolute HARMONIA_INSTALL_STATE_ROOT");
  }
  return join(stateRoot, "update-accepted.json");
}

async function waitForUpdaterAcceptance(
  child: ReturnType<typeof spawn>,
  path: string,
): Promise<void> {
  const deadline = Date.now() + 5_000;
  let spawnError: Error | undefined;
  child.once("error", (error) => {
    spawnError = error;
  });
  let exited: number | null | undefined;
  child.once("exit", (code) => {
    exited = code;
  });
  while (Date.now() < deadline) {
    if (spawnError) throw spawnError;
    if (exited !== undefined) {
      throw new Error(`updater helper exited before startup acceptance (status ${exited ?? "unknown"})`);
    }
    if (existsSync(path)) {
      try {
        const value = JSON.parse(readFileSync(path, "utf8")) as { accepted?: boolean };
        if (value.accepted === true) return;
      } catch {
        // The helper is still atomically writing its acceptance record.
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("updater helper did not accept the operation during startup grace");
}

function updateResultState(result: unknown): "up-to-date" | "available" {
  if (result && typeof result === "object" && "status" in result) {
    const statusValue = (result as { status?: unknown }).status;
    if (statusValue && typeof statusValue === "object" && "UpToDate" in statusValue) {
      return "up-to-date";
    }
  }
  return "available";
}

async function runInstaller(args: string[]): Promise<unknown> {
  const installer = installerBinary();
  return new Promise((resolve, reject) => {
    const child = spawn(installer, args, {
      detached: false,
      shell: false,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
      env: updaterEnvironment(),
    });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      stdout = `${stdout}${chunk}`.slice(-1024 * 1024);
    });
    child.stderr.on("data", (chunk: string) => {
      stderr = `${stderr}${chunk}`.slice(-64 * 1024);
    });
    child.once("error", reject);
    child.once("exit", (code) => {
      const checkMayReportAvailable = args[0] === "check" && code === 10;
      if (code !== 0 && !checkMayReportAvailable) {
        reject(new Error(stderr.trim() || `installer exited with status ${code ?? "unknown"}`));
        return;
      }
      try {
        resolve(JSON.parse(stdout));
      } catch {
        reject(new Error("installer returned invalid JSON"));
      }
    });
  });
}
