import { existsSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
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
import { moduleDir } from "./runtime-paths.js";
import { registerUpdaterIpc } from "./updater-ipc.js";
import { writeDesktopStartupFailure } from "./startup-diagnostics.js";
import {
  isMatchingShutdownRequest,
  shutdownAcknowledgement,
  type DesktopSession,
} from "./shutdown-control.js";
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
  let desktopSession: DesktopSession | undefined;
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
      let productVersion: string | undefined;
      try {
        productVersion = app.getVersion();
      } catch {
        // The original startup error remains authoritative if version lookup is unavailable.
      }
      const diagnosticWritten = await writeDesktopStartupFailure(error, {
        productVersion,
      });
      if (!diagnosticWritten) {
        console.warn("Harmonia desktop startup diagnostic could not be written");
      }
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
        preload: resolve(moduleDir, "preload.js"),
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
            resolve(moduleDir, "../../../frontend/dist"),
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
        const stateRoot = process.env.HARMONIA_INSTALL_STATE_ROOT;
        if (!stateRoot || !desktopSession) {
          throw new Error("shutdown acknowledgement requires the current installed desktop session");
        }
        writeJsonAtomically(
          join(stateRoot, "desktop-shutdown-ack.json"),
          shutdownAcknowledgement({ ...desktopSession, requestId }),
        );
        try {
          unlinkSync(join(stateRoot, "desktop-shutdown-request.json"));
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
        }
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
    desktopSession = {
      sessionId: randomUUID(),
      pid: process.pid,
      startedAtMs: Date.now(),
    };
    writeJsonAtomically(join(stateRoot, "desktop-session.json"), desktopSession);
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
        const request: unknown = JSON.parse(
          readFileSync(join(stateRoot, "desktop-shutdown-request.json"), "utf8"),
        );
        if (desktopSession && isMatchingShutdownRequest(request, desktopSession)) {
          void shutdownForUpdate(0, request.requestId);
        }
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
