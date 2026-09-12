import { spawn } from "node:child_process";
import { isAbsolute } from "node:path";

import { app, ipcMain } from "electron";

export type UpdateStatus =
  | { state: "idle" }
  | { state: "checking" }
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
      status = { state: "available", result };
      return result;
    } catch (error) {
      status = { state: "error", error: error instanceof Error ? error.message : String(error) };
      throw error;
    }
  });
  ipcMain.handle("harmonia:update:installUpdate", async () => {
    const installer = installerBinary();
    // The updater is an independent process. It owns the build/activation lock and the desktop
    // exits immediately after handoff, so it is never placed in the desktop's containment group.
    const child = spawn(installer, ["update"], {
      detached: true,
      shell: false,
      windowsHide: true,
      stdio: "ignore",
      env: updaterEnvironment(),
    });
    child.unref();
    status = { state: "installing" };
    app.quit();
    return { accepted: true };
  });
}

function installerBinary(): string {
  const value = process.env.HARMONIA_INSTALLER_BINARY;
  if (!value || !isAbsolute(value)) {
    throw new Error("installed runtime requires an absolute HARMONIA_INSTALLER_BINARY");
  }
  return value;
}

function updaterEnvironment(): NodeJS.ProcessEnv {
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
  ]) {
    if (process.env[name]) environment[name] = process.env[name];
  }
  return environment;
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
