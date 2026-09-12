import { existsSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

import {
  app,
  BrowserWindow,
  protocol,
  shell,
} from "electron";

import { OsCredentialStore } from "./credentials.js";
import { activeGateway, loadGatewayConfig } from "./gateway-config.js";
import { LocalGateway } from "./local-gateway.js";
import {
  launchRequestFromAdditionalData,
  launchRequestFromEnvironment,
  writeLaunchAcknowledgement,
  type LaunchRequest,
} from "./launch-ack.js";
import { userDataRoot } from "./paths.js";
import { registerHarmoniaProtocol } from "./protocol.js";
import { registerUpdaterIpc } from "./updater-ipc.js";
import type { ActiveGateway } from "./types.js";

protocol.registerSchemesAsPrivileged([
  {
    scheme: "harmonia",
    privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true },
  },
]);

const initialLaunchRequest = launchRequestFromEnvironment();
const hasLock = app.requestSingleInstanceLock(
  initialLaunchRequest ? { harmoniaLaunch: initialLaunchRequest } : undefined,
);
if (!hasLock) {
  app.quit();
} else {
  app.setPath("userData", userDataRoot());
  registerUpdaterIpc();

  let window: BrowserWindow | undefined;
  let localGateway: LocalGateway | undefined;
  let shuttingDown = false;
  let shutdownWatcher: NodeJS.Timeout | undefined;
  let startupReady = false;
  const pendingLaunchRequests = new Map<string, LaunchRequest>();

  app.on("second-instance", (_event, _commandLine, _workingDirectory, additionalData) => {
    const request = launchRequestFromAdditionalData(additionalData);
    if (request) {
      if (startupReady) {
        void acknowledgeLaunchRequest(request);
      } else {
        pendingLaunchRequests.set(request.nonce, request);
      }
    }
    if (window) {
      if (window.isMinimized()) window.restore();
      window.focus();
    }
  });

  app.on("before-quit", (event) => {
    if (shuttingDown) return;
    event.preventDefault();
    void shutdown(0);
  });

  app.on("window-all-closed", () => {
    app.quit();
  });

  app.whenReady().then(async () => {
    try {
      registerDesktopSession();
      const allowInsecureRemote = process.env.HARMONIA_ALLOW_INSECURE_REMOTE === "1";
      const config = await loadGatewayConfig(join(app.getPath("userData"), "desktop.json"), {
        allowInsecureRemote,
      });
      const profile = activeGateway(config);
      const baseUrl = profile.mode === "local"
        ? await startLocalGateway()
        : profile.url;
      const gateway: ActiveGateway = { profile, baseUrl };
      registerHarmoniaProtocol(frontendDist(), gateway, new OsCredentialStore());
      window = createWindow();
      await window.loadURL("harmonia://app/");
      startupReady = true;
      if (initialLaunchRequest) {
        await acknowledgeLaunchRequest(initialLaunchRequest, true);
      }
      for (const request of pendingLaunchRequests.values()) {
        void acknowledgeLaunchRequest(request);
      }
      pendingLaunchRequests.clear();
      startShutdownWatcher();
    } catch (error) {
      console.error("Harmonia desktop startup failed", error);
      await shutdown(1);
    }
  });

  async function startLocalGateway(): Promise<string> {
    localGateway = new LocalGateway({
      javaBinary: process.env.HARMONIA_JAVA_BINARY,
      workspace: process.env.HARMONIA_WORKSPACE || app.getPath("userData"),
      log: (message) => console.log(message),
    });
    return localGateway.start();
  }

  async function acknowledgeLaunchRequest(
    request: LaunchRequest,
    failStartup = false,
  ): Promise<void> {
    try {
      await writeLaunchAcknowledgement(request);
    } catch (error) {
      console.error("Harmonia desktop launch acknowledgement failed", error);
      if (failStartup) throw error;
    }
  }

  function createWindow(): BrowserWindow {
    const created = new BrowserWindow({
      width: 1440,
      height: 960,
      minWidth: 960,
      minHeight: 640,
      webPreferences: {
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
        webviewTag: false,
        preload: resolve(__dirname, "preload.js"),
      },
    });
    created.webContents.setWindowOpenHandler(({ url }) => {
      void openExternal(url);
      return { action: "deny" };
    });
    created.webContents.on("will-navigate", (event, url) => {
      if (url.startsWith("harmonia://app/")) return;
      event.preventDefault();
      void openExternal(url);
    });
    return created;
  }

  async function openExternal(url: string): Promise<void> {
    try {
      const parsed = new URL(url);
      if (parsed.protocol === "http:" || parsed.protocol === "https:") {
        await shell.openExternal(url);
      }
    } catch (error) {
      console.warn("Rejected external navigation", { url, error });
    }
  }

  function frontendDist(): string {
    const configured = process.env.HARMONIA_FRONTEND_DIST;
    const activeVersion = process.env.HARMONIA_ACTIVE_VERSION_DIR;
    const installed = process.env.HARMONIA_RUNTIME_MODE === "installed";
    const candidates = [
      configured,
      activeVersion ? join(activeVersion, "desktop", "frontend", "dist") : undefined,
      ...(installed
        ? []
        : [
            resolve(__dirname, "../../../frontend/dist"),
            resolve(process.cwd(), "frontend/dist"),
          ]),
    ].filter((candidate): candidate is string => Boolean(candidate));
    const dist = candidates.find((candidate) => existsSync(join(candidate, "index.html")));
    if (!dist) {
      throw new Error("frontend/dist/index.html was not found; run npm run build in frontend");
    }
    return dist;
  }

  async function shutdown(code: number): Promise<void> {
    return shutdownForUpdate(code);
  }

  async function shutdownForUpdate(code: number, requestId?: string): Promise<void> {
    if (shuttingDown) return;
    shuttingDown = true;
    if (shutdownWatcher) clearInterval(shutdownWatcher);
    try {
      await localGateway?.stop();
      if (requestId) {
        writeJsonAtomically(join(process.env.HARMONIA_INSTALL_STATE_ROOT || "", "desktop-shutdown-ack.json"), {
          requestId,
          acknowledgedAtMs: Date.now(),
        });
      }
    } finally {
      clearDesktopSession();
      app.exit(code);
    }
  }

  function registerDesktopSession(): void {
    if (process.env.HARMONIA_RUNTIME_MODE !== "installed") return;
    const stateRoot = process.env.HARMONIA_INSTALL_STATE_ROOT;
    if (!stateRoot || !isAbsolutePath(stateRoot)) {
      throw new Error("installed runtime requires an absolute HARMONIA_INSTALL_STATE_ROOT");
    }
    writeJsonAtomically(join(stateRoot, "desktop-session.json"), {
      pid: process.pid,
      startedAtMs: Date.now(),
    });
  }

  function clearDesktopSession(): void {
    const stateRoot = process.env.HARMONIA_INSTALL_STATE_ROOT;
    if (!stateRoot || !isAbsolutePath(stateRoot)) return;
    try {
      unlinkSync(join(stateRoot, "desktop-session.json"));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") console.warn(error);
    }
  }

  function startShutdownWatcher(): void {
    const stateRoot = process.env.HARMONIA_INSTALL_STATE_ROOT;
    if (process.env.HARMONIA_RUNTIME_MODE !== "installed" || !stateRoot) return;
    shutdownWatcher = setInterval(() => {
      if (shuttingDown) return;
      try {
        const request = JSON.parse(
          readFileSync(join(stateRoot, "desktop-shutdown-request.json"), "utf8"),
        ) as { requestId?: string };
        if (request.requestId) void shutdownForUpdate(0, request.requestId);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") console.warn(error);
      }
    }, 100);
    shutdownWatcher.unref();
  }

  function writeJsonAtomically(path: string, value: unknown): void {
    const temporary = `${path}.${process.pid}.tmp`;
    writeFileSync(temporary, `${JSON.stringify(value)}\n`, { encoding: "utf8", flag: "w" });
    try {
      unlinkSync(path);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    renameSync(temporary, path);
  }

  function isAbsolutePath(path: string): boolean {
    return path.startsWith("/") || /^[A-Za-z]:[\\/]/.test(path) || path.startsWith("\\\\");
  }
}
