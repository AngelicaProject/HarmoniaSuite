import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";

export function userDataRoot(env: NodeJS.ProcessEnv = process.env): string {
  if (env.HARMONIA_RUNTIME_MODE === "installed") {
    const explicit = env.HARMONIA_USER_DATA_ROOT;
    if (!explicit) {
      throw new Error("installed runtime requires HARMONIA_USER_DATA_ROOT");
    }
    if (!isAbsolute(explicit)) {
      throw new Error("HARMONIA_USER_DATA_ROOT must be an absolute path");
    }
    return explicit;
  }
  if (process.platform === "win32") {
    return join(env.APPDATA || join(homedir(), "AppData", "Roaming"), "HarmoniaSuite");
  }
  return join(env.XDG_DATA_HOME || join(homedir(), ".local", "share"), "harmonia-suite-data");
}
