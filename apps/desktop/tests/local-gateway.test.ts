import { EventEmitter } from "node:events";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PassThrough } from "node:stream";

import { describe, expect, it, vi } from "vitest";

import { LocalGateway, parseGatewayReadyLine } from "../src/local-gateway.js";

class FakeChild extends EventEmitter {
  readonly stdout = new PassThrough();
  readonly stderr = new PassThrough();
  exitCode: number | null = null;
  signalCode: NodeJS.Signals | null = null;

  kill = vi.fn((signal?: NodeJS.Signals) => {
    this.signalCode = signal || "SIGTERM";
    this.exitCode = 0;
    queueMicrotask(() => this.emit("exit", this.exitCode, this.signalCode));
    return true;
  });

  override emit(event: string | symbol, ...args: unknown[]): boolean {
    return super.emit(event, ...args);
  }
}

async function fakeJar(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "harmonia-desktop-test-"));
  const path = join(directory, "harmonia-suite.jar");
  await writeFile(path, "test");
  return path;
}

describe("local gateway lifecycle", () => {
  it("accepts only the owned Spring port-0 readiness handshake", () => {
    expect(parseGatewayReadyLine("HARMONIA_GATEWAY_READY instance=other port=4321", "mine")).toBeUndefined();
    expect(parseGatewayReadyLine("HARMONIA_GATEWAY_READY instance=mine port=0", "mine")).toBeUndefined();
    expect(parseGatewayReadyLine("HARMONIA_GATEWAY_READY instance=mine port=4321", "mine")).toBe(4321);
  });

  it("starts from Spring's port 0 and uses the reported bound port", async () => {
    const jarPath = await fakeJar();
    const child = new FakeChild();
    const spawnImpl = vi.fn((_command: string, args: string[]) => {
      expect(args).toContain("--server.port=0");
      const instance = args.find((arg) => arg.startsWith("--harmonia.gateway-instance="))?.split("=")[1];
      queueMicrotask(() => child.stdout.write(`HARMONIA_GATEWAY_READY instance=${instance} port=4321\n`));
      return child;
    });
    const gateway = new LocalGateway({
      jarPath,
      spawnImpl,
      fetchImpl: async () => new Response("{}", { status: 200 }),
    });

    await expect(gateway.start()).resolves.toBe("http://127.0.0.1:4321");
    expect(spawnImpl).toHaveBeenCalledOnce();
    await gateway.stop();
  });

  it("fails cleanly when the owned process reports a bind collision", async () => {
    const jarPath = await fakeJar();
    const child = new FakeChild();
    const gateway = new LocalGateway({
      jarPath,
      spawnImpl: (_command, args) => {
        const instance = args.find((arg) => arg.startsWith("--harmonia.gateway-instance="))?.split("=")[1];
        queueMicrotask(() => {
          child.stdout.write(`HARMONIA_GATEWAY_READY instance=someone-else port=4322\n`);
          child.stderr.write("java.net.BindException: Address already in use\n");
          child.exitCode = 1;
          child.emit("exit", 1, null);
        });
        expect(instance).toBeTruthy();
        return child;
      },
      readinessTimeoutMs: 100,
    });

    await expect(gateway.start()).rejects.toThrow("exited before readiness");
  });

  it("fails closed without managed Java in installed mode", async () => {
    const jarPath = await fakeJar();
    const previousMode = process.env.HARMONIA_RUNTIME_MODE;
    const previousJava = process.env.HARMONIA_JAVA_BINARY;
    process.env.HARMONIA_RUNTIME_MODE = "installed";
    delete process.env.HARMONIA_JAVA_BINARY;
    try {
      await expect(new LocalGateway({ jarPath }).start()).rejects.toThrow(
        "managed Java is required",
      );
    } finally {
      if (previousMode === undefined) delete process.env.HARMONIA_RUNTIME_MODE;
      else process.env.HARMONIA_RUNTIME_MODE = previousMode;
      if (previousJava === undefined) delete process.env.HARMONIA_JAVA_BINARY;
      else process.env.HARMONIA_JAVA_BINARY = previousJava;
    }
  });
});
