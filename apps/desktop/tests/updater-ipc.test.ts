import { EventEmitter } from "node:events";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { afterEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  handlers: new Map<string, (...args: never[]) => unknown>(),
  spawn: vi.fn(),
}));

vi.mock("electron", () => ({
  ipcMain: {
    handle: (name: string, handler: (...args: never[]) => unknown) => {
      mocks.handlers.set(name, handler);
    },
  },
}));

vi.mock("node:child_process", () => ({
  spawn: mocks.spawn,
}));

import { registerUpdaterIpc } from "../src/updater-ipc.js";

describe("updater IPC lifecycle", () => {
  const originalEnvironment = { ...process.env };

  afterEach(() => {
    process.env = { ...originalEnvironment };
    mocks.handlers.clear();
    mocks.spawn.mockReset();
  });

  it("keeps the desktop alive after helper startup acceptance", async () => {
    const root = join(tmpdir(), `harmonia-updater-ipc-${Date.now()}`);
    const stateRoot = join(root, "state");
    mkdirSync(stateRoot, { recursive: true });
    const acceptancePath = join(stateRoot, "update-accepted.json");
    process.env.HARMONIA_RUNTIME_MODE = "installed";
    process.env.HARMONIA_INSTALLER_BINARY = join(root, "bin", "HarmoniaSetup.exe");
    process.env.HARMONIA_INSTALL_STATE_ROOT = stateRoot;

    const child = new EventEmitter() as EventEmitter & { unref: () => void };
    child.unref = vi.fn();
    mocks.spawn.mockImplementation((_program: string, _args: string[], options: { env?: Record<string, string> }) => {
      setTimeout(() => {
        writeFileSync(acceptancePath, JSON.stringify({ accepted: true }));
      }, 10);
      expect(options.env?.HARMONIA_UPDATE_FROM_DESKTOP).toBe("1");
      return child;
    });

    registerUpdaterIpc();
    const install = mocks.handlers.get("harmonia:update:installUpdate");
    expect(install).toBeDefined();
    await expect(install!()).resolves.toEqual({ accepted: true });
    expect(child.unref).toHaveBeenCalledOnce();
    expect(mocks.handlers.get("harmonia:update:getUpdateStatus")!()).toEqual({
      state: "installing",
    });
  });
});
