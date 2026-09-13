import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { writeDesktopStartupFailure } from "../src/startup-diagnostics.js";

const temporaryRoots: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryRoots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("desktop startup diagnostics", () => {
  it("writes a durable structured failure record", async () => {
    const stateRoot = await mkdtemp(join(tmpdir(), "harmonia-desktop-diagnostics-"));
    temporaryRoots.push(stateRoot);

    expect(await writeDesktopStartupFailure(
      new Error("failed to load token=secret-value"),
      {
        environment: {
          HARMONIA_RUNTIME_MODE: "installed",
          HARMONIA_INSTALL_STATE_ROOT: stateRoot,
        },
        productVersion: "1.0.12-SNAPSHOT",
      },
    )).toBe(true);

    const lines = (await readFile(join(stateRoot, "diagnostics", "desktop.jsonl"), "utf8"))
      .trim()
      .split("\n");
    const record = JSON.parse(lines[0]) as Record<string, unknown>;
    expect(record.event).toBe("desktop_startup_failed");
    expect(record.runtime_mode).toBe("installed");
    expect(record.product_version).toBe("1.0.12-SNAPSHOT");
    expect(record.error_message).toBe("failed to load token=<redacted>");
    expect(record.timestamp_ms).toEqual(expect.any(Number));
  });

  it("does not turn a missing state root into another startup failure", async () => {
    expect(await writeDesktopStartupFailure(new Error("startup failed"), {
      environment: { HARMONIA_RUNTIME_MODE: "installed" },
    })).toBe(false);
  });
});
