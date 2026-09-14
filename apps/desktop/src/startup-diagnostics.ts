import { appendFile, mkdir } from "node:fs/promises";
import { isAbsolute, join } from "node:path";

import type { DesktopRuntimeContext } from "./runtime-context.js";

const MAX_DIAGNOSTIC_TEXT_LENGTH = 16_000;

export async function writeDesktopStartupFailure(
  error: unknown,
  options: {
    environment?: NodeJS.ProcessEnv;
    runtimeContext?: DesktopRuntimeContext;
    stateRoot?: string;
    productVersion?: string;
  } = {},
): Promise<boolean> {
  const environment = options.environment || process.env;
  const stateRoot = options.runtimeContext?.stateRoot
    || options.stateRoot
    || environment.HARMONIA_INSTALL_STATE_ROOT;
  if (!stateRoot || !isAbsolute(stateRoot)) {
    return false;
  }

  const record: Record<string, unknown> = {
    timestamp_ms: Date.now(),
    level: "error",
    event: "desktop_startup_failed",
    runtime_mode: options.runtimeContext?.mode || environment.HARMONIA_RUNTIME_MODE || "unknown",
    error_message: safeDiagnosticText(errorMessage(error)),
  };
  const stack = errorStack(error);
  if (stack) {
    record.stack = safeDiagnosticText(stack);
  }
  if (options.productVersion) {
    record.product_version = safeDiagnosticText(options.productVersion);
  }

  try {
    const diagnosticsDirectory = join(stateRoot, "diagnostics");
    await mkdir(diagnosticsDirectory, { recursive: true });
    await appendFile(
      join(diagnosticsDirectory, "desktop.jsonl"),
      `${JSON.stringify(record)}\n`,
      "utf8",
    );
    return true;
  } catch {
    // Startup diagnostics are best effort and must never hide the original failure.
    return false;
  }
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message || error.name;
  }
  return typeof error === "string" ? error : "Unknown desktop startup failure";
}

function errorStack(error: unknown): string | undefined {
  return error instanceof Error ? error.stack : undefined;
}

function safeDiagnosticText(value: string): string {
  return value
    .replace(/((?:password|token|secret|api[_-]?key)\s*[=:]\s*)("[^"]*"|'[^']*'|\S+)/gi, "$1<redacted>")
    .slice(0, MAX_DIAGNOSTIC_TEXT_LENGTH);
}
