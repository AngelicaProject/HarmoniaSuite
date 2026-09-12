import { existsSync } from "node:fs";
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
import { userDataRoot } from "./paths.js";
import { registerHarmoniaProtocol } from "./protocol.js";
import type { ActiveGateway } from "./types.js";

protocol.registerSchemesAsPrivileged([
  {
    scheme: "harmonia",
    privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true },
  },
]);

const hasLock = app.requestSingleInstanceLock();
if (!hasLock) {
  app.quit();
} else {
  app.setPath("userData", userDataRoot());

  let window: BrowserWindow | undefined;
  let localGateway: LocalGateway | undefined;
  let shuttingDown = false;

  app.on("second-instance", () => {
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
    } catch (error) {
      console.error("Harmonia desktop startup failed", error);
      await shutdown(1);
    }
  });

  async function startLocalGateway(): Promise<string> {
    localGateway = new LocalGateway({
      workspace: process.env.HARMONIA_WORKSPACE || app.getPath("userData"),
      log: (message) => console.log(message),
    });
    return localGateway.start();
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
    if (shuttingDown) return;
    shuttingDown = true;
    try {
      await localGateway?.stop();
    } finally {
      app.exit(code);
    }
  }
}
