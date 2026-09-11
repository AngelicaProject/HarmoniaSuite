import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";

import { findFreeLoopbackPort } from "./ports.js";

type LocalGatewayOptions = {
  jarPath?: string;
  javaBinary?: string;
  workspace?: string;
  readinessTimeoutMs?: number;
  log?: (message: string) => void;
  fetchImpl?: typeof fetch;
};

export class LocalGateway {
  private child: ChildProcess | undefined;
  private port: number | undefined;
  private stopping = false;
  private readonly options: LocalGatewayOptions;

  constructor(options: LocalGatewayOptions = {}) {
    this.options = options;
  }

  async start(): Promise<string> {
    if (this.child) {
      throw new Error("local gateway is already running");
    }
    const port = await findFreeLoopbackPort();
    const jarPath = this.options.jarPath || defaultGatewayJar();
    if (!jarPath || !existsSync(jarPath)) {
      throw new Error(
        "Spring gateway JAR was not found; build the backend or set HARMONIA_GATEWAY_JAR",
      );
    }
    const java = this.options.javaBinary || defaultJavaBinary();
    const workspace = this.options.workspace || process.env.HARMONIA_WORKSPACE;
    const args = [
      "-jar",
      jarPath,
      "--server.address=127.0.0.1",
      `--server.port=${port}`,
    ];
    if (workspace) {
      args.push(`--harmonia.workspace=${workspace}`);
    }
    this.options.log?.(`Запуск local gateway на 127.0.0.1:${port}`);
    const child = spawn(java, args, {
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
    this.port = port;
    child.stdout?.setEncoding("utf8");
    child.stderr?.setEncoding("utf8");
    child.stdout?.on("data", (chunk: string) => this.logLines(chunk));
    child.stderr?.on("data", (chunk: string) => this.logLines(chunk));
    const exited = new Promise<never>((_, reject) => {
      child.once("error", (error) => reject(new Error(`local gateway process failed: ${error.message}`)));
      child.once("exit", (code, signal) => {
        if (!this.stopping) {
          reject(new Error(`local gateway exited before readiness (code=${code}, signal=${signal ?? "none"})`));
        }
      });
    });
    try {
      await Promise.race([this.waitUntilReady(port), exited]);
      return `http://127.0.0.1:${port}`;
    } catch (error) {
      await this.stop();
      throw error;
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

  private logLines(chunk: string): void {
    chunk.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).forEach((line) => {
      this.options.log?.(`[gateway] ${line}`);
    });
  }
}

export function defaultGatewayJar(): string | undefined {
  const explicit = process.env.HARMONIA_GATEWAY_JAR;
  if (explicit) {
    return explicit;
  }
  const candidates = [
    resolve(process.cwd(), "target", "harmonia-suite.jar"),
    resolve(__dirname, "../../../target/harmonia-suite.jar"),
  ];
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
