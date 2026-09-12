import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { randomUUID } from "node:crypto";

type LocalGatewayOptions = {
  jarPath?: string;
  javaBinary?: string;
  workspace?: string;
  readinessTimeoutMs?: number;
  log?: (message: string) => void;
  fetchImpl?: typeof fetch;
  spawnImpl?: SpawnGateway;
};

type SpawnGateway = (
  command: string,
  args: string[],
  options: Parameters<typeof spawn>[2],
) => ChildProcess;

export class LocalGateway {
  private child: ChildProcess | undefined;
  private port: number | undefined;
  private stopping = false;
  private readyResolve: ((port: number) => void) | undefined;
  private readyReject: ((error: Error) => void) | undefined;
  private instanceId: string | undefined;
  private outputBuffer = "";
  private readonly options: LocalGatewayOptions;

  constructor(options: LocalGatewayOptions = {}) {
    this.options = options;
  }

  async start(): Promise<string> {
    if (this.child) {
      throw new Error("local gateway is already running");
    }
    const jarPath = this.options.jarPath || defaultGatewayJar();
    if (!jarPath || !existsSync(jarPath)) {
      throw new Error(
        "Spring gateway JAR was not found; build the backend or set HARMONIA_GATEWAY_JAR",
      );
    }
    const java = this.options.javaBinary || defaultJavaBinary();
    const workspace = this.options.workspace || process.env.HARMONIA_WORKSPACE;
    const instanceId = randomUUID().replaceAll("-", "");
    this.instanceId = instanceId;
    this.stopping = false;
    this.outputBuffer = "";
    const args = [
      "-jar",
      jarPath,
      "--server.address=127.0.0.1",
      "--server.port=0",
      `--harmonia.gateway-instance=${instanceId}`,
    ];
    if (workspace) {
      args.push(`--harmonia.workspace=${workspace}`);
    }
    this.options.log?.("Запуск local gateway на динамическом loopback-порту");
    const spawnGateway = this.options.spawnImpl || spawn;
    const child = spawnGateway(java, args, {
      cwd: resolve(jarPath, ".."),
      env: {
        ...process.env,
        // Kept while old source checkouts are still supported during migration.
        HARMONIA_NO_BROWSER: "1",
      },
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });
    this.child = child;
    child.stdout?.setEncoding("utf8");
    child.stderr?.setEncoding("utf8");
    child.stdout?.on("data", (chunk: string) => this.handleOutput(chunk));
    child.stderr?.on("data", (chunk: string) => this.handleOutput(chunk));
    const readyPort = new Promise<number>((resolveReady, rejectReady) => {
      this.readyResolve = resolveReady;
      this.readyReject = rejectReady;
    });
    const exited = new Promise<never>((_, reject) => {
      child.once("error", (error) => reject(new Error(`local gateway process failed: ${error.message}`)));
      child.once("exit", (code, signal) => {
        if (!this.stopping) {
          reject(new Error(`local gateway exited before readiness (code=${code}, signal=${signal ?? "none"})`));
        }
      });
    });
    try {
      const port = await Promise.race([readyPort, exited]);
      await this.waitUntilReady(port);
      return `http://127.0.0.1:${port}`;
    } catch (error) {
      await this.stop();
      throw error;
    } finally {
      this.readyResolve = undefined;
      this.readyReject = undefined;
    }
  }

  async stop(): Promise<void> {
    const child = this.child;
    if (!child) {
      return;
    }
    this.stopping = true;
    this.child = undefined;
    this.port = undefined;
    this.readyReject?.(new Error("local gateway stopped"));
    this.readyResolve = undefined;
    this.readyReject = undefined;
    if (child.exitCode !== null || child.signalCode !== null) {
      return;
    }
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        resolve();
      }, 5000);
      child.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
      child.kill("SIGTERM");
    });
  }

  private async waitUntilReady(port: number): Promise<void> {
    const fetchImpl = this.options.fetchImpl || fetch;
    const timeout = this.options.readinessTimeoutMs || 30_000;
    const deadline = Date.now() + timeout;
    const url = `http://127.0.0.1:${port}/api/status`;
    let lastError = "not started";
    while (Date.now() < deadline) {
      try {
        const response = await fetchImpl(url, { signal: AbortSignal.timeout(1000) });
        if (response.ok) {
          this.options.log?.("Local gateway готов");
          return;
        }
        lastError = `HTTP ${response.status}`;
      } catch (error) {
        lastError = error instanceof Error ? error.message : String(error);
      }
      await delay(100);
    }
    throw new Error(`local gateway readiness timeout (${lastError})`);
  }

  private handleOutput(chunk: string): void {
    this.outputBuffer += chunk;
    const lines = this.outputBuffer.split(/\r?\n/);
    this.outputBuffer = lines.pop() || "";
    lines.map((line) => line.trim()).filter(Boolean).forEach((line) => {
      this.options.log?.(`[gateway] ${line}`);
      const port = parseGatewayReadyLine(line, this.instanceId);
      if (port !== undefined) {
        this.port = port;
        this.readyResolve?.(port);
      }
    });
  }
}

export function parseGatewayReadyLine(line: string, expectedInstance?: string): number | undefined {
  const match = /^HARMONIA_GATEWAY_READY instance=([A-Za-z0-9_-]+) port=(\d+)$/.exec(line.trim());
  if (!match || !expectedInstance || match[1] !== expectedInstance) {
    return undefined;
  }
  const port = Number(match[2]);
  return Number.isInteger(port) && port > 0 && port <= 65535 ? port : undefined;
}

export function defaultGatewayJar(): string | undefined {
  const explicit = process.env.HARMONIA_BACKEND_JAR || process.env.HARMONIA_GATEWAY_JAR;
  const activeVersion = process.env.HARMONIA_ACTIVE_VERSION_DIR;
  const installed = process.env.HARMONIA_RUNTIME_MODE === "installed";
  const candidates = [
    explicit,
    activeVersion ? join(activeVersion, "backend", "harmonia-suite.jar") : undefined,
    ...(installed
      ? []
      : [
          resolve(process.cwd(), "target", "harmonia-suite.jar"),
          resolve(__dirname, "../../../target/harmonia-suite.jar"),
        ]),
  ].filter((candidate): candidate is string => Boolean(candidate));
  return candidates.find((candidate) => existsSync(candidate));
}

function defaultJavaBinary(): string {
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    return join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

function delay(ms: number): Promise<void> {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, ms));
}
