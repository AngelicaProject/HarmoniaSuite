import { existsSync, readFileSync, realpathSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";

import { moduleDir } from "./runtime-paths.js";

const FULL_SHA = /^[0-9a-fA-F]{40}$/;

export type DesktopRuntimeMode = "dev" | "installed";
export type DesktopRuntimePlatform = "win32" | "linux";

export type DesktopRuntimeContext = {
  mode: DesktopRuntimeMode;
  appRoot: string;
  activeVersionDir?: string;
  backendJar?: string;
  javaBinary?: string;
  userDataRoot: string;
  workspace: string;
  stateRoot: string;
  installerBinary: string;
};

export type RuntimeContextOptions = {
  env?: NodeJS.ProcessEnv;
  resourcesPath?: string;
  platform?: DesktopRuntimePlatform;
};

type RuntimeMetadata = {
  desktop_executable: string;
  backend_jar: string;
  managed_java_binary: string;
};

type VersionMetadata = {
  schema_version: number;
  target_commit: string;
  runtime: RuntimeMetadata;
};

export function createDesktopRuntimeContext(
  options: RuntimeContextOptions = {},
): DesktopRuntimeContext {
  const env = options.env || process.env;
  const platform = options.platform || currentRuntimePlatform();
  const resourcesPath = options.resourcesPath
    ?? (process as NodeJS.Process & { resourcesPath?: string }).resourcesPath;
  if (resourcesPath) {
    const installed = resolveInstalledContext(resourcesPath, env, platform);
    if (installed) return installed;
  }
  return resolveDevelopmentContext(env, platform);
}

function resolveInstalledContext(
  resourcesPath: string,
  env: NodeJS.ProcessEnv,
  platform: DesktopRuntimePlatform,
): DesktopRuntimeContext | undefined {
  const canonicalResources = realpathOrThrow(resourcesPath, "Electron resources path");
  if (dirname(canonicalResources).split(/[\\/]/).pop() !== "desktop") return undefined;

  if (!statDirectory(canonicalResources)) {
    throw new Error(`Electron resources path is not a directory: ${canonicalResources}`);
  }
  const desktopDir = dirname(canonicalResources);
  const versionDir = dirname(desktopDir);
  const versionsDir = dirname(versionDir);
  const appRoot = dirname(versionsDir);
  const metadataPath = join(versionDir, "metadata.json");
  if (!existsSync(metadataPath)) return undefined;

  const metadata = readVersionMetadata(metadataPath);
  const canonicalVersion = realpathOrThrow(versionDir, "active version directory");
  const canonicalVersions = realpathOrThrow(versionsDir, "versions directory");
  const commit = versionDirName(canonicalVersion);
  if (!FULL_SHA.test(commit) || metadata.target_commit !== commit) {
    throw new Error("installed runtime version directory and metadata target_commit disagree");
  }
  if (!isDirectChild(canonicalVersions, canonicalVersion)) {
    throw new Error("installed runtime version is outside the managed versions directory");
  }

  const canonicalAppRoot = realpathOrThrow(appRoot, "application root");
  const backendJar = safeRuntimePath(canonicalVersion, metadata.runtime.backend_jar, "backend JAR");
  const desktopExecutable = safeRuntimePath(
    canonicalVersion,
    metadata.runtime.desktop_executable,
    "desktop executable",
  );
  const javaBinary = safeRuntimePath(canonicalAppRoot, metadata.runtime.managed_java_binary, "managed Java");
  const canonicalBackend = realpathOrThrow(backendJar, "backend JAR");
  const canonicalDesktop = realpathOrThrow(desktopExecutable, "desktop executable");
  const canonicalJava = realpathOrThrow(javaBinary, "managed Java");
  if (!isWithin(canonicalVersion, canonicalBackend)) {
    throw new Error("backend JAR is outside the active version");
  }
  if (!isWithin(canonicalVersion, canonicalDesktop)) {
    throw new Error("desktop executable is outside the active version");
  }
  if (!isWithin(canonicalAppRoot, canonicalJava)) {
    throw new Error("managed Java executable is outside the application root");
  }
  if (!statFile(backendJar) || !statFile(desktopExecutable) || !statFile(javaBinary)) {
    throw new Error("installed runtime metadata references a missing executable or backend JAR");
  }

  const installerBinary = join(
    appRoot,
    "bin",
    platform === "win32" ? "HarmoniaSetup.exe" : "harmonia-setup",
  );
  const stateRoot = installedStateRoot(appRoot, env, platform);
  return {
    mode: "installed",
    appRoot: canonicalAppRoot,
    activeVersionDir: canonicalVersion,
    backendJar: canonicalBackend,
    javaBinary: canonicalJava,
    userDataRoot: installedUserDataRoot(env, platform),
    workspace: installedUserDataRoot(env, platform),
    stateRoot,
    installerBinary,
  };
}

function resolveDevelopmentContext(
  env: NodeJS.ProcessEnv,
  platform: DesktopRuntimePlatform,
): DesktopRuntimeContext {
  const checkoutRoot = resolve(moduleDir, "..", "..", "..");
  const userData = env.HARMONIA_USER_DATA_ROOT && isAbsolute(env.HARMONIA_USER_DATA_ROOT)
    ? env.HARMONIA_USER_DATA_ROOT
    : installedUserDataRoot(env, platform);
  const activeVersionDir = absoluteOverride(env.HARMONIA_ACTIVE_VERSION_DIR);
  const backendJar = firstExisting([
    absoluteOverride(env.HARMONIA_BACKEND_JAR),
    absoluteOverride(env.HARMONIA_GATEWAY_JAR),
    activeVersionDir ? join(activeVersionDir, "backend", "harmonia-suite.jar") : undefined,
    resolve(process.cwd(), "target", "harmonia-suite.jar"),
    resolve(moduleDir, "..", "..", "..", "target", "harmonia-suite.jar"),
  ]);
  const javaBinary = absoluteOverride(env.HARMONIA_JAVA_BINARY)
    || (env.JAVA_HOME
      ? join(env.JAVA_HOME, "bin", platform === "win32" ? "java.exe" : "java")
      : undefined);
  const stateRoot = absoluteOverride(env.HARMONIA_INSTALL_STATE_ROOT) || join(checkoutRoot, ".harmonia-state");
  const installerBinary = absoluteOverride(env.HARMONIA_INSTALLER_BINARY)
    || join(checkoutRoot, "apps", "installer", "target", "debug", platform === "win32" ? "HarmoniaSetup.exe" : "harmonia-setup");
  return {
    mode: "dev",
    appRoot: checkoutRoot,
    activeVersionDir,
    backendJar,
    javaBinary,
    userDataRoot: userData,
    workspace: env.HARMONIA_WORKSPACE && isAbsolute(env.HARMONIA_WORKSPACE)
      ? env.HARMONIA_WORKSPACE
      : userData,
    stateRoot,
    installerBinary,
  };
}

export function installedUserDataRoot(
  env: NodeJS.ProcessEnv = process.env,
  platform: DesktopRuntimePlatform = currentRuntimePlatform(),
): string {
  if (platform === "win32") {
    return join(absoluteEnvironmentRoot(env.APPDATA) || join(homedir(), "AppData", "Roaming"), "HarmoniaSuite");
  }
  return join(absoluteEnvironmentRoot(env.XDG_DATA_HOME) || join(homedir(), ".local", "share"), "harmonia-suite-data");
}

export function installedStateRoot(
  appRoot: string,
  env: NodeJS.ProcessEnv = process.env,
  platform: DesktopRuntimePlatform = currentRuntimePlatform(),
): string {
  if (platform === "win32") return join(appRoot, "state");
  return join(
    absoluteEnvironmentRoot(env.XDG_STATE_HOME) || join(homedir(), ".local", "state"),
    "harmonia-suite",
  );
}

function currentRuntimePlatform(): DesktopRuntimePlatform {
  return process.platform === "win32" ? "win32" : "linux";
}

function absoluteEnvironmentRoot(value: string | undefined): string | undefined {
  return value && isAbsolute(value) ? value : undefined;
}

function readVersionMetadata(path: string): VersionMetadata {
  let value: unknown;
  try {
    value = JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    throw new Error(`installed runtime metadata could not be read: ${path}`, { cause: error });
  }
  if (!isRecord(value)
    || value.schema_version !== 1
    || typeof value.target_commit !== "string"
    || !isRecord(value.runtime)
    || typeof value.runtime.desktop_executable !== "string"
    || typeof value.runtime.backend_jar !== "string"
    || typeof value.runtime.managed_java_binary !== "string") {
    throw new Error("installed runtime metadata has an invalid schema");
  }
  return value as unknown as VersionMetadata;
}

function safeRuntimePath(root: string, child: string, label: string): string {
  if (isAbsolute(child)) throw new Error(`${label} path must be relative to its managed root`);
  const resolved = resolve(root, child);
  if (!isWithin(root, resolved)) throw new Error(`${label} path escapes its managed root`);
  return resolved;
}

function realpathOrThrow(path: string, label: string): string {
  try {
    return realpathSync(path);
  } catch (error) {
    throw new Error(`${label} is missing or cannot be canonicalized: ${path}`, { cause: error });
  }
}

function statFile(path: string): boolean {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

function statDirectory(path: string): boolean {
  try {
    return statSync(path).isDirectory();
  } catch {
    return false;
  }
}

function isWithin(root: string, child: string): boolean {
  const value = relative(resolve(root), resolve(child));
  return value === "" || (value !== ".." && !value.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`) && !isAbsolute(value));
}

function isDirectChild(root: string, child: string): boolean {
  const value = relative(resolve(root), resolve(child));
  return value !== ""
    && !value.includes("/")
    && !value.includes("\\")
    && value !== "."
    && value !== ".."
    && !value.startsWith("..")
    && !isAbsolute(value);
}

function versionDirName(path: string): string {
  return path.split(/[\\/]/).pop() || "";
}

function absoluteOverride(value: string | undefined): string | undefined {
  return value && isAbsolute(value) ? value : undefined;
}

function firstExisting(candidates: Array<string | undefined>): string | undefined {
  return candidates.find((candidate) => candidate && existsSync(candidate));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
