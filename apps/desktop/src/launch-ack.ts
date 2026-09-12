import { randomUUID } from "node:crypto";
import { dirname, isAbsolute, relative, resolve, sep } from "node:path";
import { mkdir, open, readFile, rename, rm } from "node:fs/promises";

export type LaunchRequest = {
  ackPath: string;
  stateRoot: string;
  operationId: string;
  targetCommit: string;
  nonce: string;
};

type LaunchAcknowledgement = LaunchRequest & {
  schemaVersion: 1;
  acknowledgedAtMs: number;
};

const ACK_SCHEMA_VERSION = 1;

export function launchRequestFromEnvironment(
  env: NodeJS.ProcessEnv = process.env,
): LaunchRequest | undefined {
  const values = {
    ackPath: env.HARMONIA_LAUNCH_ACK,
    stateRoot: env.HARMONIA_INSTALL_STATE_ROOT,
    operationId: env.HARMONIA_LAUNCH_OPERATION_ID,
    targetCommit: env.HARMONIA_LAUNCH_COMMIT,
    nonce: env.HARMONIA_LAUNCH_NONCE,
  };
  const launchValues = [
    values.ackPath,
    values.operationId,
    values.targetCommit,
    values.nonce,
  ];
  if (launchValues.every((value) => value === undefined)) {
    return undefined;
  }
  return parseLaunchRequest(values);
}

export function launchRequestFromAdditionalData(
  value: unknown,
  env: NodeJS.ProcessEnv = process.env,
): LaunchRequest | undefined {
  if (!isRecord(value) || !isRecord(value.harmoniaLaunch)) {
    return undefined;
  }
  const request = parseLaunchRequest(value.harmoniaLaunch);
  const trustedStateRoot = env.HARMONIA_INSTALL_STATE_ROOT;
  if (!trustedStateRoot || resolve(trustedStateRoot) !== resolve(request.stateRoot)) {
    return undefined;
  }
  return request;
}

export async function writeLaunchAcknowledgement(request: LaunchRequest): Promise<void> {
  validateLaunchRequest(request);
  const parent = dirname(request.ackPath);
  await mkdir(parent, { recursive: true });
  const temporary = `${request.ackPath}.${randomUUID()}.tmp`;
  const acknowledgement: LaunchAcknowledgement = {
    ...request,
    schemaVersion: ACK_SCHEMA_VERSION,
    acknowledgedAtMs: Date.now(),
  };
  const handle = await open(temporary, "wx");
  try {
    await handle.writeFile(`${JSON.stringify(acknowledgement)}\n`, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
  try {
    await rename(temporary, request.ackPath);
    await syncDirectory(parent);
  } catch (error) {
    await rm(temporary, { force: true });
    throw error;
  }
}

export function isMatchingLaunchAcknowledgement(
  value: unknown,
  request: LaunchRequest,
): boolean {
  if (!isRecord(value)) return false;
  return value.schemaVersion === ACK_SCHEMA_VERSION
    && value.ackPath === request.ackPath
    && value.stateRoot === request.stateRoot
    && value.operationId === request.operationId
    && value.targetCommit === request.targetCommit
    && value.nonce === request.nonce
    && typeof value.acknowledgedAtMs === "number";
}

export async function readLaunchAcknowledgement(
  request: LaunchRequest,
): Promise<LaunchAcknowledgement | undefined> {
  validateLaunchRequest(request);
  try {
    const parsed: unknown = JSON.parse(await readFile(request.ackPath, "utf8"));
    return isMatchingLaunchAcknowledgement(parsed, request)
      ? parsed as LaunchAcknowledgement
      : undefined;
  } catch (error) {
    if (isRecord(error) && error.code === "ENOENT") return undefined;
    if (error instanceof SyntaxError) return undefined;
    throw error;
  }
}

function parseLaunchRequest(value: Record<string, unknown>): LaunchRequest {
  const request = {
    ackPath: value.ackPath,
    stateRoot: value.stateRoot,
    operationId: value.operationId,
    targetCommit: value.targetCommit,
    nonce: value.nonce,
  };
  if (Object.values(request).some((item) => typeof item !== "string" || item.length === 0)) {
    throw new Error("desktop launch acknowledgement metadata is incomplete");
  }
  const parsed = request as LaunchRequest;
  validateLaunchRequest(parsed);
  return parsed;
}

function validateLaunchRequest(request: LaunchRequest): void {
  if (!isAbsolute(request.stateRoot) || !isAbsolute(request.ackPath)) {
    throw new Error("desktop launch acknowledgement paths must be absolute");
  }
  const root = resolve(request.stateRoot);
  const ackPath = resolve(request.ackPath);
  const relativePath = relative(root, ackPath);
  if (
    relativePath.length === 0
    || relativePath === ".."
    || relativePath.startsWith(`..${sep}`)
    || isAbsolute(relativePath)
  ) {
    throw new Error("desktop launch acknowledgement must stay inside installer state");
  }
}

async function syncDirectory(path: string): Promise<void> {
  try {
    const handle = await open(path, "r");
    try {
      await handle.sync();
    } finally {
      await handle.close();
    }
  } catch (error) {
    const code = isRecord(error) ? error.code : undefined;
    if (code !== "EINVAL" && code !== "EISDIR" && code !== "ENOTSUP" && code !== "EPERM") {
      throw error;
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
