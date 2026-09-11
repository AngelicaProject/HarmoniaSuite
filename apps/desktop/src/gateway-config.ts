import { readFile } from "node:fs/promises";

import type { DesktopConfig, GatewayProfile } from "./types.js";
import { DEFAULT_GATEWAY_PROFILE } from "./types.js";

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
const SECRET_FIELD = /(?:api[-_]?key|authorization|password|secret|token)/i;

export class GatewayConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GatewayConfigError";
  }
}

export function parseGatewayConfig(
  value: unknown,
  options: { allowInsecureRemote?: boolean } = {},
): DesktopConfig {
  if (value === undefined) {
    value = {};
  }
  rejectPlaintextCredentials(value);
  if (!isRecord(value)) {
    throw new GatewayConfigError("desktop config must be a JSON object");
  }

  const profilesValue = value.profiles;
  const profiles = profilesValue === undefined
    ? [DEFAULT_GATEWAY_PROFILE]
    : parseProfiles(profilesValue, options.allowInsecureRemote === true);
  if (profiles.length === 0) {
    throw new GatewayConfigError("desktop config must contain at least one gateway profile");
  }

  const activeGatewayId = value.activeGatewayId;
  if (activeGatewayId !== undefined && typeof activeGatewayId !== "string") {
    throw new GatewayConfigError("activeGatewayId must be a string");
  }
  const active = activeGatewayId || profiles[0].id;
  if (!profiles.some((profile) => profile.id === active)) {
    throw new GatewayConfigError(`active gateway profile not found: ${active}`);
  }

  return {
    profiles,
    activeGatewayId: active,
    allowInsecureRemote: options.allowInsecureRemote === true,
  };
}

export async function loadGatewayConfig(
  configPath: string,
  options: { allowInsecureRemote?: boolean } = {},
): Promise<DesktopConfig> {
  try {
    const text = await readFile(configPath, "utf8");
    return parseGatewayConfig(JSON.parse(text), options);
  } catch (error) {
    if (isMissingFile(error)) {
      return parseGatewayConfig(undefined, options);
    }
    if (error instanceof GatewayConfigError || error instanceof SyntaxError) {
      throw new GatewayConfigError(
        error instanceof SyntaxError
          ? `desktop config is not valid JSON: ${configPath}`
          : error.message,
      );
    }
    throw new GatewayConfigError(`cannot read desktop config: ${configPath}`);
  }
}

export function activeGateway(config: DesktopConfig): GatewayProfile {
  const profile = config.profiles.find((candidate) => candidate.id === config.activeGatewayId);
  if (!profile) {
    throw new GatewayConfigError(`active gateway profile not found: ${config.activeGatewayId}`);
  }
  return profile;
}

export function validateRemoteUrl(url: string, allowInsecureRemote = false): string {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new GatewayConfigError("remote gateway URL is invalid");
  }
  if (parsed.username || parsed.password) {
    throw new GatewayConfigError("remote gateway URL must not contain credentials");
  }
  if (parsed.hash || parsed.search) {
    throw new GatewayConfigError("remote gateway URL must not contain query or fragment");
  }
  const isLoopback = LOOPBACK_HOSTS.has(parsed.hostname.toLowerCase());
  if (parsed.protocol !== "https:" && !(parsed.protocol === "http:" && (isLoopback || allowInsecureRemote))) {
    throw new GatewayConfigError(
      "remote gateway must use HTTPS (HTTP is limited to loopback or an explicit development override)",
    );
  }
  return parsed.toString().replace(/\/+$/, "");
}

function parseProfiles(value: unknown, allowInsecureRemote: boolean): GatewayProfile[] {
  if (!Array.isArray(value)) {
    throw new GatewayConfigError("profiles must be an array");
  }
  const ids = new Set<string>();
  return value.map((raw, index) => {
    if (!isRecord(raw) || typeof raw.id !== "string" || raw.id.trim() === "") {
      throw new GatewayConfigError(`gateway profile ${index} must have a non-empty id`);
    }
    const id = raw.id.trim();
    if (ids.has(id)) {
      throw new GatewayConfigError(`duplicate gateway profile id: ${id}`);
    }
    ids.add(id);
    if (raw.mode === "local") {
      return { id, mode: "local" };
    }
    if (raw.mode !== "remote" || typeof raw.url !== "string") {
      throw new GatewayConfigError(`gateway profile ${id} must be local or remote with a URL`);
    }
    const credentialRef = raw.credentialRef;
    if (credentialRef !== undefined && typeof credentialRef !== "string") {
      throw new GatewayConfigError(`credentialRef for ${id} must be a string`);
    }
    return {
      id,
      mode: "remote",
      url: validateRemoteUrl(raw.url, allowInsecureRemote),
      ...(credentialRef && credentialRef.trim() ? { credentialRef: credentialRef.trim() } : {}),
    };
  });
}

function rejectPlaintextCredentials(value: unknown, path = "desktop config"): void {
  if (Array.isArray(value)) {
    value.forEach((item, index) => rejectPlaintextCredentials(item, `${path}[${index}]`));
    return;
  }
  if (!isRecord(value)) {
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (SECRET_FIELD.test(key) && key !== "credentialRef") {
      throw new GatewayConfigError(`${path}.${key} is not allowed; store credentials in the OS keyring`);
    }
    rejectPlaintextCredentials(child, `${path}.${key}`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMissingFile(error: unknown): boolean {
  return isRecord(error) && error.code === "ENOENT";
}
